package org.ryoo.knimeEbi.util;

import java.io.*;
import java.util.*;
import org.ryoo.knimeEbi.port.EbiDocument;
import org.ryoo.knimeEbi.scaffolder.*;

/** Production-code regressions; no KNIME replacement classes or stubs. */
public final class PortRegressionTest {
    private static int passed;
    @FunctionalInterface interface Test { void run() throws Exception; }
    private static void test(String name, Test test) throws Exception {
        test.run(); passed++; System.out.println("PASS " + name);
    }
    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + " but received " + actual);
    }
    private static void fails(Test test) throws Exception {
        try { test.run(); } catch (IllegalArgumentException | IOException expected) { return; }
        throw new AssertionError("Invalid input was accepted.");
    }
    private static String ptml(String children, String root) { return "<ptml><processTree id=\"t\" root=\"" + root + "\">" + children + "</processTree></ptml>"; }
    private static String node(String tag, String id) { return "<" + tag + " id=\"" + id + "\" name=\"Å&amp;東\"/>"; }
    private static String edge(String source, String target) { return "<parentsNode sourceId=\"" + source + "\" targetId=\"" + target + "\"/>"; }
    private static EbiCommandMetadata contract() {
        var input = new EbiCommandMetadataParameter("model", "XLogPortObject", "", true,
            List.of("XLogPortObject", "PetriNetPortObject"));
        var output = new EbiCommandMetadataParameter("ProcessTree", "ProcessTreePortObject", "", true);
        return new EbiCommandMetadata("Ebi test contract", "Test", "Test", List.of(input, input), output,
            List.of(new EbiCommandVariant(List.of("XLogPortObject", "PetriNetPortObject"), "ProcessTreePortObject", ".ptml"),
                new EbiCommandVariant(List.of("PetriNetPortObject", "XLogPortObject"), "ProcessTreePortObject", ".ptml")));
    }
    public static void main(String[] args) throws Exception {
        test("mixed valid typed signature", () -> EbiPortContract.validate(contract(), List.of("XLogPortObject", "PetriNetPortObject"), "ProcessTreePortObject"));
        test("no Cartesian union broadening", () -> fails(() -> EbiPortContract.validate(contract(), List.of("XLogPortObject", "XLogPortObject"), "ProcessTreePortObject")));
        test("missing logical port rejected", () -> fails(() -> EbiPortContract.validate(contract(), List.of("XLogPortObject"), "ProcessTreePortObject")));
        test("extra logical port rejected", () -> fails(() -> EbiPortContract.validate(contract(), List.of("XLogPortObject", "PetriNetPortObject", "XLogPortObject"), "ProcessTreePortObject")));
        test("wrong output type rejected", () -> fails(() -> EbiPortContract.validate(contract(), List.of("XLogPortObject", "PetriNetPortObject"), "XLogPortObject")));
        test("unconnected port rejected", () -> fails(() -> EbiPortContract.validate(contract(), Arrays.asList(null, "PetriNetPortObject"), "ProcessTreePortObject")));
        test("raw document accepts explicit format", () -> EbiPortContract.validateDocumentFormat(List.of(".slpn", ".sdfa"), ".slpn", 0));
        test("raw document rejects incompatible format", () -> fails(() -> EbiPortContract.validateDocumentFormat(List.of(".slpn"), ".xes", 1)));
        test("raw document rejects missing whitelist", () -> fails(() -> EbiPortContract.validateDocumentFormat(List.of(), ".slpn", 0)));
        test("CSV escapes commas, quotes, Unicode, newlines and missing attributes", () -> {
            EbiCsv.Writer writer = new EbiCsv.Writer(List.of("case id", "activity", "note"));
            writer.append(List.of("case-1", "A,\"B\"", "line 1\nline 2"));
            writer.append(java.util.Arrays.asList("case-1", "Ü東", null));
            equal("\"case id\",\"activity\",\"note\"\n\"case-1\",\"A,\"\"B\"\"\",\"line 1\nline 2\"\n\"case-1\",\"Ü東\",\"\"\n", writer.finish());
        });
        test("CSV rejects missing trace/activity, bad columns, and too few events", () -> {
            fails(() -> new EbiCsv.Writer(List.of("activity")));
            fails(() -> new EbiCsv.Writer(List.of("same", "same")));
            EbiCsv.Writer writer = new EbiCsv.Writer(List.of("case", "activity"));
            fails(() -> writer.append(java.util.Arrays.asList("case-1", null)));
            fails(() -> writer.append(List.of("case-1")));
            writer.append(List.of("case-1", "A"));
            fails(writer::finish);
        });
        test("native document UTF8 large roundtrip", () -> {
            EbiDocument original = new EbiDocument(".slpn", "stochastic labelled Petri net\nÅ東𝛑\n".repeat(10000));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); original.write(new DataOutputStream(bytes));
            equal(original, EbiDocument.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        });
        test("document bad extension rejected", () -> fails(() -> new EbiDocument("../../file", "data")));
        test("document empty content rejected", () -> fails(() -> new EbiDocument(".slpn", "  ")));
        test("document corrupted magic rejected", () -> fails(() -> EbiDocument.read(new DataInputStream(new ByteArrayInputStream(new byte[8])))));
        test("document truncated data rejected", () -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); new EbiDocument(".slpn", "hello").write(new DataOutputStream(bytes));
            fails(() -> EbiDocument.read(new DataInputStream(new ByteArrayInputStream(Arrays.copyOf(bytes.toByteArray(), bytes.size() - 1)))));
        });
        test("document malicious length rejected", () -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(0x45424931); out.writeUTF(".slpn"); out.writeInt(Integer.MAX_VALUE);
            fails(() -> EbiDocument.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        });
        test("document malformed UTF8 rejected", () -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(0x45424931); out.writeUTF(".slpn"); out.writeInt(2); out.write(new byte[] {(byte)0xc3, 0x28});
            fails(() -> EbiDocument.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        });
        String actualDfg = "{\"activities\":{\"A\":\"2\",\"B\":\"2\"},\"directly_follows_relations\":[[[\"A\",\"B\"],\"2\"]],\"end_activities\":[\"B\"],\"start_activities\":[\"A\"]}";
        test("native DFG keeps distinct end boundary", () -> { var graph = DfgCodec.parse(actualDfg); equal(List.of("A"), graph.starts()); equal(List.of("B"), graph.ends()); });
        test("DFG roundtrip Unicode and escapes", () -> {
            Map<String, String> activities = new LinkedHashMap<>(); activities.put("A\"\\\nÅ東𝛑", "6/5"); activities.put("B", "4/5");
            var graph = new DfgCodec.Graph(activities, List.of(new DfgCodec.Edge("A\"\\\nÅ東𝛑", "B", "1/5")), List.of("A\"\\\nÅ東𝛑"), List.of("B"));
            equal(graph, DfgCodec.parse(DfgCodec.write(graph)));
        });
        test("fractional DFG count not rounded", () -> fails(() -> DfgCodec.exactCount("6/5")));
        test("integral rational DFG count accepted", () -> equal(3L, DfgCodec.exactCount("6/2")));
        test("DFG count overflow rejected", () -> fails(() -> DfgCodec.exactCount("9223372036854775808")));
        test("DFG zero denominator rejected", () -> fails(() -> DfgCodec.exactCount("1/0")));
        test("CSV standard positional headers produce no warning", () -> {
            equal(List.of(), EbiCsv.headerWarnings(List.of("case:concept:name", "concept:name")));
            equal(List.of(), EbiCsv.headerWarnings(List.of(
                "case:concept:name", "concept:name", "org:resource", "time:timestamp", "cost")));
        });
        test("CSV nonstandard first and second columns warn without rejecting", () -> {
            var warnings = EbiCsv.headerWarnings(List.of("concept:name", "case:concept:name"));
            equal(2, warnings.size());
            if (!warnings.get(0).contains("column 1 ('concept:name') as case ID")
                    || !warnings.get(1).contains("column 2 ('case:concept:name') as activity"))
                throw new AssertionError(warnings);
            equal(2, EbiCsv.headerWarnings(List.of("case", "activity")).size());
        });
        test("CSV optional resource and timestamp positions warn conditionally", () -> {
            var warnings = EbiCsv.headerWarnings(List.of("case:concept:name", "concept:name", "cost", "operator"));
            equal(2, warnings.size());
            if (!warnings.get(0).contains("column 3 ('cost') as resource")
                    || !warnings.get(1).contains("column 4 ('operator') as timestamp")
                    || !warnings.get(0).contains("event-attribute CSV imports"))
                throw new AssertionError(warnings);
        });
        test("CSV writer retains every column in original order with Unicode and quoting", () -> {
            var writer = new EbiCsv.Writer(List.of("case:concept:name", "concept:name", "cost", "org:resource"));
            writer.append(List.of("case,1", "A\"東", "100", "Alice"));
            writer.append(List.of("case,1", "B", "200", "Bob"));
            equal("\"case:concept:name\",\"concept:name\",\"cost\",\"org:resource\"\n"
                + "\"case,1\",\"A\"\"東\",\"100\",\"Alice\"\n"
                + "\"case,1\",\"B\",\"200\",\"Bob\"\n", writer.finish());
        });
        test("DFG boundary frequencies reconstructed across branches", () -> {
            var graph = new DfgCodec.Graph(Map.of("A", "3", "B", "2", "C", "3"),
                List.of(new DfgCodec.Edge("A", "B", "2"), new DfgCodec.Edge("A", "C", "1"),
                    new DfgCodec.Edge("B", "C", "2")), List.of("A"), List.of("C"));
            var boundaries = DfgCodec.boundaryCounts(graph);
            equal(Map.of("A", 3L), boundaries.starts());
            equal(Map.of("C", 3L), boundaries.ends());
        });
        test("DFG boundary frequencies support loops and multiple boundaries", () -> {
            var graph = new DfgCodec.Graph(Map.of("A", "4", "B", "3"),
                List.of(new DfgCodec.Edge("A", "A", "1"), new DfgCodec.Edge("A", "B", "2"),
                    new DfgCodec.Edge("B", "A", "1")), List.of("A", "B"), List.of("A", "B"));
            var boundaries = DfgCodec.boundaryCounts(graph);
            equal(Map.of("A", 2L, "B", 1L), boundaries.starts());
            equal(Map.of("A", 1L, "B", 2L), boundaries.ends());
        });
        test("DFG single-activity self-loop reconstructs both boundaries", () -> {
            var graph = new DfgCodec.Graph(Map.of("A", "6"),
                List.of(new DfgCodec.Edge("A", "A", "4")), List.of("A"), List.of("A"));
            var boundaries = DfgCodec.boundaryCounts(graph);
            equal(Map.of("A", 2L), boundaries.starts());
            equal(Map.of("A", 2L), boundaries.ends());
        });
        test("DFG self-loop overcount is rejected", () -> fails(() -> DfgCodec.boundaryCounts(
            new DfgCodec.Graph(Map.of("A", "3"),
                List.of(new DfgCodec.Edge("A", "A", "4")), List.of("A"), List.of("A")))));
        test("DFG integral fractions reconstruct exact boundaries", () -> {
            var graph = new DfgCodec.Graph(Map.of("A", "6/2", "B", "6/2"),
                List.of(new DfgCodec.Edge("A", "B", "6/2")), List.of("A"), List.of("B"));
            equal(Map.of("A", 3L), DfgCodec.boundaryCounts(graph).starts());
        });
        test("DFG missing start activity rejected", () -> fails(() -> DfgCodec.boundaryCounts(
            new DfgCodec.Graph(Map.of("A", "2", "B", "2"),
                List.of(new DfgCodec.Edge("A", "B", "2")), List.of(), List.of("B")))));
        test("DFG spurious end activity rejected", () -> fails(() -> DfgCodec.boundaryCounts(
            new DfgCodec.Graph(Map.of("A", "2", "B", "2"),
                List.of(new DfgCodec.Edge("A", "B", "2")), List.of("A"), List.of("A", "B")))));
        test("DFG too many incoming edges rejected", () -> fails(() -> DfgCodec.boundaryCounts(
            new DfgCodec.Graph(Map.of("A", "2", "B", "1"),
                List.of(new DfgCodec.Edge("A", "B", "2")), List.of("A"), List.of("B")))));
        test("DFG aggregate edge weights do not wrap long", () -> fails(() -> DfgCodec.boundaryCounts(
            new DfgCodec.Graph(Map.of("A", "9223372036854775807", "B", "9223372036854775807", "C", "9223372036854775807"),
                List.of(new DfgCodec.Edge("A", "C", "9223372036854775807"),
                    new DfgCodec.Edge("B", "C", "9223372036854775807")), List.of("A", "B"), List.of("C")))));
        test("DFG unknown edge endpoint rejected", () -> fails(() -> DfgCodec.parse(actualDfg.replace("[[\"A\",\"B\"]", "[[\"A\",\"C\"]"))));
        test("DFG duplicate JSON key rejected", () -> fails(() -> DfgCodec.parse(actualDfg.replace("\"A\":\"2\"", "\"A\":\"2\",\"A\":\"9\""))));
        test("DFG unexpected trailing output rejected", () -> fails(() -> DfgCodec.parse(actualDfg + " extra")));
        test("DFG unknown schema rejected", () -> fails(() -> DfgCodec.parse("{\"activities\":{}}")));
        test("DFG malformed JSON rejected", () -> fails(() -> DfgCodec.parse("{\"activities\":\"\\uXXXX\"}")));
        test("XML encoding declaration repaired", () -> equal("<?xml version=\"1.0\" encoding=\"UTF-8\"?><pnml name=\"Å東\"/>", XmlText.utf8("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><pnml name=\"Å東\"/>", "pnml")));
        test("XML wrong native fallback rejected", () -> fails(() -> XmlText.utf8("<ptml/>", "pnml")));
        test("XML malformed document rejected", () -> fails(() -> XmlText.utf8("<pnml>", "pnml")));
        test("XML external entity rejected", () -> fails(() -> XmlText.utf8("<!DOCTYPE pnml [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><pnml>&x;</pnml>", "pnml")));
        test("SVG 2 paint and textPath adapt without changing labels", () -> {
            String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"40\">"
                + "<defs><marker id=\"m\"><path d=\"M0 0L5 5\" fill=\"context-stroke\"/></marker></defs>"
                + "<path id=\"edge\" d=\"M0 10L100 10\" stroke=\"#000000ff\" fill=\"transparent\"/>"
                + "<rect width=\"5\" height=\"5\" fill=\"#80808080\"/>"
                + "<text><textPath href=\"#edge\">Ä東</textPath></text></svg>";
            String normalized = EbiSvgCompatibility.forKnime(svg);
            XmlText.utf8(normalized, "svg");
            for (String token : List.of("xmlns:xlink=", "xlink:href=\"#edge\"", "fill=\"none\"",
                    "stroke=\"#000000\"", "fill=\"currentColor\"", "fill=\"#808080\"",
                    "fill-opacity=\"0.50196078\"", "Ä東")) {
                if (!normalized.contains(token)) throw new AssertionError("SVG normalization lost " + token);
            }
            if (normalized.contains("#000000ff") || normalized.contains("<textPath href="))
                throw new AssertionError("SVG 2 attributes were not normalized.");
        });
        test("SVG normalization rejects external references and XML entities", () -> {
            fails(() -> EbiSvgCompatibility.forKnime(
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><text><textPath href=\"https://example.org/path\">x</textPath></text></svg>"));
            fails(() -> EbiSvgCompatibility.forKnime(
                "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><svg xmlns=\"http://www.w3.org/2000/svg\">&x;</svg>"));
        });
        test("PTML preserves Unicode and escapes", () -> { String repaired = PtmlCompatibilityUtil.toPm4KnimePtml(ptml(node("manualTask", "a"), "a")); if (!repaired.contains("Å&amp;東")) throw new AssertionError(repaired); });
        test("PTML duplicate nodes rejected", () -> fails(() -> PtmlCompatibilityUtil.toPm4KnimePtml(ptml(node("manualTask", "a") + node("manualTask", "a"), "a"))));
        test("PTML missing root rejected", () -> fails(() -> PtmlCompatibilityUtil.toPm4KnimePtml(ptml(node("manualTask", "a"), "z"))));
        test("PTML unknown node rejected", () -> fails(() -> PtmlCompatibilityUtil.toPm4KnimePtml(ptml(node("unknown", "a"), "a"))));
        test("PTML dangling edge rejected", () -> fails(() -> PtmlCompatibilityUtil.toPm4KnimePtml(ptml(node("sequence", "a") + edge("a", "z"), "a"))));
        test("PTML leaf with child rejected", () -> fails(() -> PtmlCompatibilityUtil.toPm4KnimePtml(ptml(node("manualTask", "a") + node("manualTask", "b") + edge("a", "b"), "a"))));
        test("PTML empty operator rejected", () -> fails(() -> PtmlCompatibilityUtil.toPm4KnimePtml(ptml(node("xor", "a"), "a"))));
        test("PTML disconnected tree rejected", () -> fails(() -> PtmlCompatibilityUtil.toPm4KnimePtml(ptml(node("manualTask", "a") + node("manualTask", "b"), "a"))));
        test("PTML multiple parents rejected", () -> fails(() -> PtmlCompatibilityUtil.toPm4KnimePtml(ptml(node("sequence", "a") + node("manualTask", "b") + edge("a", "b") + edge("a", "b"), "a"))));
        test("PTML bad loop arity rejected", () -> fails(() -> PtmlCompatibilityUtil.toPm4KnimePtml(ptml(node("xorLoop", "a") + node("manualTask", "b") + edge("a", "b"), "a"))));
        test("PTML external entity rejected", () -> fails(() -> PtmlCompatibilityUtil.toPm4KnimePtml("<!DOCTYPE ptml [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>" + ptml(node("manualTask", "a"), "a"))));
        test("PTML known flower defect repaired", () -> {
            String body = node("xorLoop", "r") + node("xor", "rredo") + node("automaticTask", "rexit")
                + node("automaticTask", "body") + node("manualTask", "a")
                + edge("r", "body") + edge("r", "rexit") + edge("r", "aredo");
            String repaired = PtmlCompatibilityUtil.toPm4KnimePtml(ptml(body, "r"));
            equal(4, repaired.split("<parentsNode ", -1).length - 1);
        });
        test("PTML similar nonmatching defect rejected", () -> fails(() -> PtmlCompatibilityUtil.toPm4KnimePtml(ptml(node("xorLoop", "r") + node("xor", "rredo") + node("automaticTask", "rexit") + node("manualTask", "a") + edge("r", "aredo"), "r"))));
        test("exact fraction drops leading approximation", () -> equal("1/3\n", EbiScalarResult.exactText(".frac", "Approximately 0.333333\n1/3\n")));
        test("exact root drops trailing wrong approximation", () -> equal("√32041/53601\n", EbiScalarResult.exactText(".croot", "√32041/53601\nApproximately 1063\n")));
        test("exact expression CRLF retained", () -> equal("log(2)/3\r\n", EbiScalarResult.exactText(".logpol", "Approximately 0.2\r\nlog(2)/3\r\n")));
        test("free-text approximation reports unchanged", () -> equal("Approximately 50 cases", EbiScalarResult.exactText(".txt", "Approximately 50 cases")));
        test("exact internal lexical text retained", () -> equal("1 + ApproximatelyVariable\n", EbiScalarResult.exactText(".rldiv", "1 + ApproximatelyVariable\n")));
        test("approximation-only result rejected", () -> fails(() -> EbiScalarResult.exactText(".frac", "Approximately 0.3\n")));
        test("actual native flower PTML repaired", () -> {
            String broken = java.nio.file.Files.readString(java.nio.file.Path.of(args[0], "ebi-flower-broken.ptml"));
            String repaired = PtmlCompatibilityUtil.toPm4KnimePtml(broken);
            equal(5, repaired.split("<parentsNode ", -1).length - 1);
            if (!repaired.contains("name=\"A\"") || !repaired.contains("name=\"B\"")) throw new AssertionError(repaired);
        });
        String brokenSnfa = "stochastic non-deterministic finite automaton\n# initial state\nAutomatonState(0)\n# number of states\n3\n# number of transitions\n0\n";
        test("SNFA initial state syntax repaired", () -> equal(brokenSnfa.replace("AutomatonState(0)", "0"), EbiNativeOutputCompatibility.normalize(".snfa", brokenSnfa)));
        test("SNFA valid output unchanged", () -> equal(brokenSnfa.replace("AutomatonState(0)", "0"), EbiNativeOutputCompatibility.normalize(".snfa", brokenSnfa.replace("AutomatonState(0)", "0"))));
        test("SNFA invalid initial state rejected", () -> fails(() -> EbiNativeOutputCompatibility.normalize(".snfa", brokenSnfa.replace("AutomatonState(0)", "AutomatonState(3)"))));
        test("SNFA repair does not touch arbitrary text", () -> equal(brokenSnfa, EbiNativeOutputCompatibility.normalize(".txt", brokenSnfa)));
        test("PNML writer escapes Unicode labels and weighted markings", () -> {
            String xml = PnmlWriter.write("Net & 東", List.of(new PnmlWriter.Place("p0", "start < & >", 2), new PnmlWriter.Place("p1", "end", 0)),
                List.of(new PnmlWriter.Transition("t0", "Ä & 東", true)),
                List.of(new PnmlWriter.Arc("p0", "t0", 2), new PnmlWriter.Arc("t0", "p1", 3)), List.of(Map.of("p1", 3)));
            if (!xml.contains("Ä &amp; 東") || !xml.contains("start &lt; &amp; &gt;") || !xml.contains("$invisible$")
                || !xml.contains("<initialMarking><text>2</text></initialMarking>") || !xml.contains("<place idref=\"p1\"><text>3</text></place>")) throw new AssertionError(xml);
            XmlText.utf8(xml, "pnml");
        });
        test("PNML writer rejects dangling or nonbipartite arc", () -> fails(() -> PnmlWriter.write("net", List.of(new PnmlWriter.Place("p", "p", 0)), List.of(), List.of(new PnmlWriter.Arc("p", "p", 1)), List.of())));
        test("PNML writer rejects foreign final marking place", () -> fails(() -> PnmlWriter.write("net", List.of(new PnmlWriter.Place("p", "p", 0)), List.of(), List.of(), List.of(Map.of("other", 1)))));
        test("PNML writer rejects invalid token counts", () -> fails(() -> PnmlWriter.write("net", List.of(new PnmlWriter.Place("p", "p", -1)), List.of(), List.of(), List.of())));
        test("PM4 PTML surrogate references become valid Unicode", () -> equal("<ptml name=\"😀\"/>", XmlText.utf8(XmlText.repairPm4SurrogateReferences("<ptml name=\"&#55357;&#56832;\"/>"), "ptml")));
        test("unpaired surrogate reference remains rejected", () -> fails(() -> XmlText.utf8(XmlText.repairPm4SurrogateReferences("<ptml name=\"&#55357;\"/>"), "ptml")));
        test("ordinary numeric entities are unchanged", () -> equal("&#12345;&#12346;", XmlText.repairPm4SurrogateReferences("&#12345;&#12346;")));
        System.out.println("Port/codec regression tests passed: " + passed);
    }
}
