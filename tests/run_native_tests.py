#!/usr/bin/env python3
"""Compile JDK-only runtime tests, then optionally call the actual bundled JNI.

Usage: python tests/run_native_tests.py [--ecj /path/to/ecj.jar] [--native]
No download, KNIME mock, native compilation, or network is involved.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ecj", type=Path, help="ECJ compiler when only a JRE is installed")
    parser.add_argument("--native", action="store_true", help="exercise the real supplied JNI binary")
    parser.add_argument("--report", type=Path, help="write machine-readable results")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    source = root / "extension/src/org/processmining/ebi"
    tests = root / "tests/native/org/processmining/ebi"
    with tempfile.TemporaryDirectory(prefix="knime-ebi-test-", dir=root) as tmp:
        classes = Path(tmp) / "classes"
        classes.mkdir()
        if args.ecj:
            compiler = ["java", "-jar", str(args.ecj.resolve()), "-21"]
        else:
            javac = shutil.which("javac")
            if not javac:
                parser.error("JDK 21 javac is required, or pass --ecj /path/to/ecj.jar")
            compiler = [javac, "--release", "21"]
        subprocess.run(compiler + ["-d", str(classes)] + [str(p) for p in sorted(source.glob("*.java"))]
                       + [str(p) for p in sorted(tests.glob("*.java"))], check=True)
        command = ["java", "-Djava.io.tmpdir=" + str(Path(tmp).resolve()), "-cp", os.pathsep.join([str(classes), str(root / "extension")]),
                   "org.processmining.ebi.NativeBridgeRegressionTest"]
        results = {}
        runs = [("unit", command + [str(root)])]
        if args.native:
            runs.append(("native_smoke", command + ["--smoke"]))
            jar = Path(tmp) / "runtime.jar"
            with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as package:
                for path in classes.rglob("*.class"):
                    package.write(path, str(path.relative_to(classes)))
                plugin = root / "extension"
                for path in (plugin / "lib").rglob("*"):
                    if path.is_file():
                        package.write(path, str(path.relative_to(plugin)))
            packaged = ["java", "-Djava.io.tmpdir=" + str(Path(tmp).resolve()), "-cp", str(jar),
                        "org.processmining.ebi.NativeBridgeRegressionTest", "--packaged"]
            runs.append(("packaged_native", packaged))
        for group, invocation in runs:
            # Upstream JNI prints results; keep that out of CI/chat logs on success.
            result = subprocess.run(invocation, cwd=tmp, capture_output=True, text=True, timeout=90)
            if result.returncode:
                print(result.stdout[-5000:])
                print(result.stderr[-5000:])
                raise SystemExit(result.returncode)
            counts = [line.split("=", 1)[1] for line in result.stdout.splitlines()
                      if line.startswith("NATIVE_TESTS_PASSED=")]
            if len(counts) != 1:
                raise RuntimeError("Missing or ambiguous test result marker")
            if "Ebi received" in result.stdout or "Ebi sends" in result.stdout or "<log" in result.stdout:
                raise RuntimeError("Native stdout or input content escaped the isolated worker")
            results[group] = {"passed": int(counts[0]), "failed": 0}
        print(json.dumps(results, indent=2))
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
