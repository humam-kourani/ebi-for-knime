#!/usr/bin/env python3
"""Exercise every generated command through the actual production JNI worker.

These tests validate native commands/serializations, not KNIME port objects or
UI lifecycle. Known upstream defects remain visible in the JSON report. A known
issue is not a semantic pass; --fail-on-known makes it a failing release gate.
No network or native compiler is used.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
from fractions import Fraction
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PLUGIN = ROOT / "extension"
CATALOG = PLUGIN / "src/org/ryoo/knimeEbi/scaffolder/commands.xml"
FIXTURES = ROOT / "tests/fixtures"

SETTINGS = {
    "analyse cohort-analysis": ["0", "1/4"],
    "association all-trace-attributes": ["20"],
    "association trace-attribute": ["amount", "20"],
    "conformance markovian": ["2", "uemsc"],
    "discover alergia": ["1/20"],
    "discover directly-follows-graph": ["1"],
    "discover-non-stochastic inductive-miner-infrequent": ["0"],
    "filter log traces event activity": ["any", "C"],
    "filter log traces length": ["<", "2"],
    "sample folds": ["2", "42", "1"],
    "test bootstrap-test": ["20", "1/20"],
    "test log-categorical-attribute": ["segment", "20", "1/20"],
    "test permutation-test-log-model": ["20", "1/20"],
}
BOOL_EXPECTED = {
    "analyse-non-stochastic any-traces": True,
    "analyse-non-stochastic bounded": True,
    "analyse-non-stochastic empty-traces": False,
    "analyse-non-stochastic infinitely-many-traces": False,
    "analyse-non-stochastic timestamps-ordered": True,
}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def local_name(tag):
    return tag.rsplit("}", 1)[-1]


def traces(text):
    root = ET.fromstring(text)
    require(local_name(root.tag) == "log", "Output must be an XES log")
    result = []
    for trace in root:
        if local_name(trace.tag) != "trace":
            continue
        acts = []
        for event in trace:
            if local_name(event.tag) == "event":
                names = [p.get("value") for p in event if p.get("key") == "concept:name"]
                require(len(names) == 1, "Every event must preserve one activity")
                acts.append(names[0])
        result.append(tuple(acts))
    return result


def exact_fraction(text):
    values = [line.strip() for line in text.splitlines()
              if re.fullmatch(r"-?\d+(?:/\d+)?", line.strip())]
    require(len(values) == 1, "One unambiguous exact rational must be retained")
    return Fraction(values[0])


def validate_pnml(text):
    root = ET.fromstring(text)
    require(local_name(root.tag) == "pnml", "Requested PNML must be PNML")
    nodes = {n.get("id") for n in root.iter() if local_name(n.tag) in {"place", "transition"}}
    require(nodes, "PNML must contain places/transitions")
    labels = {n.findtext("name/text") for n in root.iter() if local_name(n.tag) == "transition"}
    require({"A", "B"} <= labels, "PNML must preserve A and B transitions")
    require(any(local_name(n.tag) == "initialMarking" for n in root.iter()), "PNML initial marking must survive")
    for edge in root.iter():
        if local_name(edge.tag) == "arc":
            require(edge.get("source") in nodes and edge.get("target") in nodes, "PNML arcs reference real nodes")


def validate_ptml(text):
    root = ET.fromstring(text)
    require(local_name(root.tag) == "ptml", "Requested PTML must be PTML")
    trees = [n for n in root if local_name(n.tag) == "processTree"]
    require(len(trees) == 1, "One process tree must be returned")
    tree = trees[0]
    nodes = {n.get("id"): n for n in tree if n.get("id") is not None}
    require(tree.get("root") in nodes, "PTML root must refer to a node")
    require({"A", "B"} <= {n.get("name") for n in nodes.values()}, "PTML must preserve activity labels")
    bad = [(n.get("sourceId"), n.get("targetId")) for n in tree if local_name(n.tag) == "parentsNode"
           and (n.get("sourceId") not in nodes or n.get("targetId") not in nodes)]
    return bad


def validate_slpn(text):
    lines = [s.strip() for s in text.splitlines() if s.strip() and not s.strip().startswith("#")]
    require(lines.pop(0) == "stochastic labelled Petri net", "Raw SLPN type marker must survive")
    tokens = iter(lines)
    places = int(next(tokens))
    require(places > 0, "SLPN has places")
    marking = [int(next(tokens)) for _ in range(places)]
    require(sum(marking) > 0 and min(marking) >= 0, "SLPN initial marking is nonempty and nonnegative")
    count = int(next(tokens))
    weights = {}
    for _ in range(count):
        activity = next(tokens)
        require(activity == "silent" or activity.startswith("label "), "SLPN transition activity syntax")
        weight = Fraction(next(tokens))
        require(weight > 0, "Stochastic transition weights must be retained and positive")
        weights[activity] = weight
        for _side in range(2):
            arcs = int(next(tokens))
            for _arc in range(arcs):
                place = int(next(tokens))
                require(0 <= place < places, "SLPN arc must refer to an existing place")
    require(list(tokens) == [], "SLPN parser consumed all structural fields")
    require({"label A", "label B"} <= set(weights), "SLPN keeps A and B")
    return weights


def validate(command, fmt, text, rich_traces):
    """Return concrete checks and upstream defects, keeping the two distinct."""
    short = command.removeprefix("Ebi ")
    checks, issues = [], []
    if fmt == ".frac":
        value = exact_fraction(text)
        expected = Fraction(0) if short == "analyse variety" else Fraction(1)
        require(value == expected, "Known fixture requires exact fraction " + str(expected))
        checks.append("exact rational equals " + str(expected) + " for the deterministic fixture")
    elif fmt == ".bool":
        require(text.strip() in {"true", "false"}, "Boolean must have exact true/false encoding")
        require((text.strip() == "true") == BOOL_EXPECTED[short], "Boolean semantics differ from fixture")
        checks.append("expected bounded/finite/nonempty/ordered-language predicate")
    elif fmt == ".rldiv":
        require("1-" in text and "log(1)" in text, "Identical languages require exact 1 - sqrt(log(1)/2)")
        require("Approximately 1" in text, "Identity conformance is one")
        checks.append("exact identity Jensen-Shannon conformance retained symbolically")
    elif fmt == ".logpol":
        require(text.splitlines()[0].strip() == "0", "Deterministic identical language has zero entropic relevance")
        checks.append("exact zero entropic relevance for deterministic identical language")
    elif fmt == ".croot":
        exact = text.splitlines()[0].strip()
        match = re.fullmatch(r"(1-)?√(\d+(?:/\d+)?)", exact)
        require(match is not None, "Exact association must retain its symbolic square root")
        value = math.sqrt(float(Fraction(match.group(2))))
        if match.group(1):
            value = 1 - value
        require(-1e-12 <= value <= 1 + 1e-12, "Exact association lies in [0,1]")
        checks.append("exact association expression is parseable and in [0,1]")
        approximate = re.search(r"Approximately\s+([^\s]+)", text)
        require(approximate is not None, "Approximation line present for diagnosis")
        try:
            rendered = float(Fraction(approximate.group(1)))
            if not math.isclose(rendered, value, rel_tol=1e-4, abs_tol=1e-4):
                issues.append("UP-CROOT: native approximation is inconsistent with the preserved exact expression")
        except (ValueError, ZeroDivisionError):
            issues.append("UP-CROOT: native approximation is truncated or unparsable")
    elif fmt == ".xes":
        actual = Counter(traces(text))
        if short == "convert log":
            expected = Counter({("A", "B"): 4})
        elif short == "filter log traces empty":
            expected = Counter(t for t in rich_traces if t)
        elif short == "filter log traces event activity":
            expected = Counter(t for t in rich_traces if "C" not in t)
        elif short == "filter log traces length":
            expected = Counter(t for t in rich_traces if len(t) >= 2)
        elif short == "sample folds":
            require(actual and not (actual - Counter(rich_traces)), "Fold is a nonempty submultiset of input")
            expected = actual
        else:
            raise AssertionError("Missing XES invariant for " + short)
        require(actual == expected, "Output trace multiset must match exact filter/round-trip semantics")
        checks.append("exact activity-trace multiset, including duplicate/empty traces where applicable")
    elif fmt == ".pnml":
        validate_pnml(text)
        checks.append("PNML labels, initial marking, and all arc endpoints")
    elif fmt == ".ptml":
        bad = validate_ptml(text)
        if bad and short == "discover-non-stochastic flower process-tree":
            issues.append("UP-PTML: upstream flower exporter emits dangling redo identifiers; KNIME adapter repair tested separately")
        else:
            require(not bad, "PTML edges reference existing nodes")
        checks.append("PTML type, root and activities; native edge validity checked explicitly")
    elif fmt in {".dfa", ".sdfa"}:
        obj = json.loads(text)
        edges = obj["transitions"]
        require({e["label"] for e in edges} == {"A", "B"}, "Automaton keeps activity alphabet A,B")
        require(isinstance(obj["initialState"], int), "Automaton initial state has numeric ID")
        if fmt == ".sdfa":
            outgoing = {}
            for edge in edges:
                probability = Fraction(edge["prob"])
                require(0 < probability <= 1, "Transition probabilities are exact and in (0,1]")
                outgoing[edge["from"]] = outgoing.get(edge["from"], 0) + probability
            require(all(total <= 1 for total in outgoing.values()), "Outgoing probability mass cannot exceed one")
        else:
            require(obj["finalStates"], "DFA preserves accepting states")
        checks.append("automaton alphabet, state IDs, accepting states/probability mass")
    elif fmt == ".dfg":
        graph = json.loads(text)
        require(graph["activities"] == {"A": "4", "B": "4"}, "DFG must retain exact activity frequencies")
        require(graph["start_activities"] == ["A"] and graph["end_activities"] == ["B"], "DFG must distinguish start/end")
        require(graph["directly_follows_relations"] == [[["A", "B"], "4"]], "DFG edge count must be four")
        checks.append("exact DFG activity/edge frequencies and distinct start/end activities")
    elif fmt == ".snfa":
        require(text.startswith("stochastic non-deterministic finite automaton"), "SNFA marker retained")
        require("label A" in text and "label B" in text, "SNFA activity labels retained")
        if re.search(r"(?m)^AutomatonState\(\d+\)$", text):
            issues.append("UP-SNFA: initial state debug token is not reimportable; KNIME adapter normalization tested separately")
        checks.append("SNFA format marker and alphabet; initial-state round-trip defect detected")
    elif fmt == ".slpn":
        weights = validate_slpn(text)
        if "uniform" in short:
            require(all(value == 1 for value in weights.values()), "Uniform stochastic weights are exactly one")
        checks.append("raw SLPN marking/arcs and positive exact stochastic weights")
    elif fmt == ".sptree":
        require(text.startswith("stochastic process tree"), "Stochastic tree marker must be retained")
        require("activity A" in text and "activity B" in text, "Stochastic tree activity labels retained")
        weights = re.findall(r"(?m)^\s*# (?:weight node \d+|termination weight)\s*\n\s*([^\s]+)", text)
        require(len(weights) == 3 and all(Fraction(w) > 0 for w in weights), "All node and termination weights retained")
        if "uniform" in short:
            require(all(Fraction(w) == 1 for w in weights), "Uniform tree weights are one")
        checks.append("raw stochastic tree node and termination weights retained as exact fractions")
    elif fmt == ".txt":
        if short == "analyse-non-stochastic activities":
            require(set(text.split()) == {"A", "B"}, "Activity set exactly A,B")
            checks.append("exact declared activity alphabet")
        elif short == "analyse cohort-analysis":
            require(all(s in text for s in ["Attribute", "EMSC", "segment", "premium", "standard"]), "Cohort report reflects fixture groups")
            checks.append("cohort report names the categorical groups and EMSC column")
        elif short == "association all-trace-attributes":
            require("segment" in text and "amount" in text and "Exact value:" in text, "Attribute names and exact association expressions retained")
            checks.append("categorical/numeric attribute association reports with exact values")
        elif short.startswith("test "):
            match = re.search(r"p-value\s+(\d+(?:/\d+)?)", text)
            require(match is not None and 0 <= Fraction(match.group(1)) <= 1, "Reported statistic must lie in [0,1]")
            require("Null-hypothesis:" in text and "null-hypothesis." in text, "Statistical report contains hypothesis and decision")
            checks.append("statistical report shape and statistic range; not a calibration test")
            if short == "test bootstrap-test":
                require(Fraction(match.group(1)) == 0 and "Do not reject" in text, "Native identity bootstrap score/report is stable")
                issues.append("UP-BOOTSTRAP: field labeled p-value is an upper-tail score rejected at >=1-alpha in upstream source; conventional p-value interpretation is unsafe")
            if short == "test log-categorical-attribute" and "`segment`" not in text:
                issues.append("UP-ATTRIBUTE: native report prints an internal attribute identifier (e.g. at1), not the requested label")
        elif short == "information":
            require(re.search(r"Number of traces\s+4", text) is not None and re.search(r"Number of events\s+8", text) is not None,
                    "Information must count four traces/eight events")
            checks.append("exact log counts: four traces, eight events")
        elif short == "visualise text":
            require(text.strip() == "event log with 4 traces", "Textual visualization identifies the four-trace log")
            checks.append("exact textual summary of the fixture")
        else:
            raise AssertionError("Missing text invariant for " + short)
    else:
        raise AssertionError("No validator for selected format " + fmt)
    return checks, issues


class Probe:
    def __init__(self, directory, ecj, timeout):
        self.directory, self.counter, self.timeout = directory, 0, timeout
        classes = directory / "classes"
        classes.mkdir()
        compiler = ["java", "-jar", str(ecj.resolve()), "-21"] if ecj else [shutil.which("javac") or "javac", "--release", "21"]
        sources = sorted((PLUGIN / "src/org/processmining/ebi").glob("*.java"))
        sources += sorted((ROOT / "tests/commands").rglob("*.java"))
        subprocess.run(compiler + ["-d", str(classes)] + [str(p) for p in sources], check=True)
        self.command = ["java", "-Djava.io.tmpdir=" + str(directory),
                        "-Dknime.ebi.timeoutMillis=" + str(timeout), "-cp",
                        os.pathsep.join([str(classes), str(PLUGIN)]), "org.processmining.ebi.CommandRegressionProbe"]

    def call(self, command, fmt, inputs):
        self.counter += 1
        name = "call-" + str(self.counter)
        result = self.directory / (name + fmt)
        paths = []
        for index, value in enumerate(inputs):
            if isinstance(value, Path):
                paths.append(str(value))
            else:
                path = self.directory / (name + "-input-" + str(index) + ".txt")
                path.write_text(value, encoding="utf-8")
                paths.append(str(path))
        started = time.monotonic()
        proc = subprocess.run(self.command + [command, fmt, str(result)] + paths, cwd=self.directory,
                              capture_output=True, text=True, timeout=self.timeout / 1000 + 15)
        if proc.returncode:
            error = result.with_name(result.name + ".error")
            raise RuntimeError(error.read_text(encoding="utf-8") if error.exists() else proc.stderr[-4000:])
        require(not proc.stdout.strip(), "Production native worker must not leak logs/results to stdout")
        return result, round(time.monotonic() - started, 4)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ecj", type=Path)
    parser.add_argument("--timeout-ms", type=int, default=10000)
    parser.add_argument("--report", type=Path, default=ROOT / "tests/results/command-regression.json")
    parser.add_argument("--fail-on-known", action="store_true")
    args = parser.parse_args()
    if args.timeout_ms <= 0:
        parser.error("--timeout-ms must be positive")
    if not args.ecj and not shutil.which("javac"):
        parser.error("JDK21 javac required, or supply --ecj /path/to/ecj.jar")
    catalog = ET.parse(CATALOG).getroot()
    report = {"generated_at_utc": datetime.now(timezone.utc).isoformat(),
              "scope": "Actual Ebi JNI commands through production isolated worker; not KNIME lifecycle/port-object verification",
              "catalog_sha256": hashlib.sha256(CATALOG.read_bytes()).hexdigest(),
              "catalog_commands": len(catalog), "commands": [], "supplemental": []}
    constant, rich = FIXTURES / "constant.xes", FIXTURES / "tiny-attributed.xes"
    rich_traces = traces(rich.read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory(prefix="ebi-command-test-", dir=ROOT) as temporary:
        work = Path(temporary).resolve()
        probe = Probe(work, args.ecj, args.timeout_ms)
        pnml, _ = probe.call("Ebi discover-non-stochastic inductive-miner", ".pnml", [constant])
        ptml, _ = probe.call("Ebi discover-non-stochastic inductive-miner", ".ptml", [constant])
        dfg, _ = probe.call("Ebi discover directly-follows-graph", ".dfg", [constant, "1"])
        validate_pnml(pnml.read_text())
        require(not validate_ptml(ptml.read_text()), "Seed process-tree has valid edges")
        portmap = {"XLogPortObject": constant, "PetriNetPortObject": pnml,
                   "ProcessTreePortObject": ptml, "DfgMsdPortObject": dfg}
        outputs = {}
        for command in catalog:
            name = command.get("name")
            short = name.removeprefix("Ebi ")
            fmt = command.find("variant").get("format")
            row = {"command": name, "format": fmt, "status": "failed", "checks": [], "upstream_issues": []}
            try:
                parameters = iter(SETTINGS.get(short, ["20"] if short.endswith("-sample") else []))
                inputs = []
                for slot in command.findall("input"):
                    if slot.get("port") == "true":
                        value = portmap[slot.get("default-port")]
                        if slot.get("default-port") == "XLogPortObject" and any(
                                s in short for s in ["association", "cohort-analysis", "filter ", "sample folds", "log-categorical"]):
                            value = rich
                        inputs.append(value)
                    else:
                        inputs.append(next(parameters))
                require(next(parameters, None) is None, "All fixture settings match the current catalog signature")
                result, elapsed = probe.call(name, fmt, inputs)
                outputs[name] = result
                text = result.read_text(encoding="utf-8")
                row["checks"], row["upstream_issues"] = validate(name, fmt, text, rich_traces)
                row.update({"status": "known_upstream_issue" if row["upstream_issues"] else "passed",
                            "native_call_succeeded": True, "elapsed_seconds": elapsed,
                            "output_sha256": hashlib.sha256(text.encode()).hexdigest(),
                            "output_excerpt": text[:600] if row["upstream_issues"] else text[:100]})
            except Exception as error:
                row["error"] = str(error)
            report["commands"].append(row)
        # Additional adversarial/round-trip checks exercise data semantics.
        def supplemental(name, test):
            row = {"name": name, "status": "passed"}
            try:
                issues = test() or []
                if issues:
                    row.update(status="known_upstream_issue", upstream_issues=issues)
            except Exception as error:
                row.update(status="failed", error=str(error))
            report["supplemental"].append(row)

        def unordered():
            out, _ = probe.call("Ebi analyse-non-stochastic timestamps-ordered", ".bool", [FIXTURES / "tiny-unordered.xes"])
            require(out.read_text().strip() == "false", "Descending event timestamps must be detected")
        supplemental("detect unordered timestamps", unordered)

        def folds():
            first = outputs["Ebi sample folds"].read_text()
            repeat, _ = probe.call("Ebi sample folds", ".xes", [rich, "2", "42", "1"])
            other, _ = probe.call("Ebi sample folds", ".xes", [rich, "2", "42", "0"])
            require(Counter(traces(first)) == Counter(traces(repeat.read_text())), "Same seed/build yields identical fold")
            require(Counter(traces(first)) + Counter(traces(other.read_text())) == Counter(rich_traces), "Complementary folds preserve every trace multiplicity")
        supplemental("seeded folds reproducible and conserve trace multiplicities", folds)

        for suffix, source in [(".sdfa", "Ebi convert stochastic-deterministic-finite-automaton"),
                               (".slpn", "Ebi convert stochastic-labelled-petri-net"),
                               (".sptree", "Ebi discover uniform stochastic-process-tree"),
                               (".dfa", "Ebi discover-non-stochastic prefix-tree deterministic-finite-automaton")]:
            def roundtrip(source=source, suffix=suffix):
                out, _ = probe.call("Ebi information", ".txt", [outputs[source]])
                require("Object was recognised as" in out.read_text() and suffix in out.read_text(), "Ebi must reimport its own typed model")
            supplemental("native model round-trip " + suffix, roundtrip)

        def snfa_roundtrip():
            source = outputs["Ebi convert stochastic-nondeterministic-finite-automaton"]
            try:
                probe.call("Ebi information", ".txt", [source])
                return []
            except RuntimeError:
                fixed = work / "normalized.snfa"
                fixed.write_text(re.sub(r"(?m)^AutomatonState\((\d+)\)$", r"\1", source.read_text()), encoding="utf-8")
                out, _ = probe.call("Ebi information", ".txt", [fixed])
                require("(.snfa)" in out.read_text(), "Narrow initial-state normalization must restore reimport")
                return ["UP-SNFA: unmodified native output rejected; decimal initial-state normalization restores native reimport"]
        supplemental("SNFA round-trip and narrow normalization proof", snfa_roundtrip)

        def weighted():
            out, _ = probe.call("Ebi probability log", ".frac", [FIXTURES / "weighted.slpn", FIXTURES / "single-a.xes"])
            require(exact_fraction(out.read_text()) == Fraction(3, 4), "Raw 3:1 weights imply P(A)=3/4")
            converted, _ = probe.call("Ebi convert stochastic-labelled-petri-net", ".slpn", [FIXTURES / "weighted.slpn"])
            require(validate_slpn(converted.read_text()) == {"label A": Fraction(3), "label B": Fraction(1)}, "Raw conversion preserves 3:1 weights exactly")
        supplemental("raw stochastic Petri-net weights retain exact probability", weighted)

        def dfg_semantics():
            graph = json.loads(dfg.read_text())
            require(graph["activities"] == {"A": "4", "B": "4"}, "DFG activity counts preserve multiplicity")
            require(graph["start_activities"] == ["A"] and graph["end_activities"] == ["B"], "DFG start/end activities are distinct and correct")
            require(graph["directly_follows_relations"] == [[["A", "B"], "4"]], "DFG edge frequency is exactly four")
        supplemental("DFG counts and distinct start/end activities", dfg_semantics)

        def invalid_parameter():
            try:
                probe.call("Ebi filter log traces length", ".xes", [rich, "invalid", "2"])
            except RuntimeError as error:
                require("Ebi: error:" in str(error), "Native invalid parameter produces explicit diagnostic")
                return
            raise AssertionError("Invalid filter operator was silently accepted")
        supplemental("invalid scalar rejected through worker error protocol", invalid_parameter)

    all_rows = report["commands"] + report["supplemental"]
    report["summary"] = {"catalog_commands_executed": len(report["commands"]),
                         "command_statuses": dict(Counter(row["status"] for row in report["commands"])),
                         "supplemental_statuses": dict(Counter(row["status"] for row in report["supplemental"])),
                         "failed": sum(row["status"] == "failed" for row in all_rows)}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(report["summary"], indent=2))
    for row in all_rows:
        if row["status"] == "failed":
            print("FAIL", row.get("command", row.get("name")), row["error"])
    if report["summary"]["failed"] or (args.fail_on_known and any(row["status"] == "known_upstream_issue" for row in all_rows)):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
