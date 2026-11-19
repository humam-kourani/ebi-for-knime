#!/usr/bin/env python3
"""Verify a p2 site's version and generated node registrations before publishing."""
import io
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile


PLUGIN = re.compile(r"plugins/org\.ryoo\.knimeEbi_[^/]+\.jar")
FEATURE = re.compile(r"features/org\.ryoo\.knimeEbi\.feature_[^/]+\.jar")


def verify(site_path: Path, expected_version: str) -> int:
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", expected_version):
        raise ValueError("Expected a three-part release version")
    with zipfile.ZipFile(site_path) as site:
        files = site.namelist()
        plugins = [name for name in files if PLUGIN.fullmatch(name)]
        features = [name for name in files if FEATURE.fullmatch(name)]
        if len(plugins) != 1 or len(features) != 1:
            raise ValueError("Expected exactly one Ebi plug-in and feature")
        with zipfile.ZipFile(io.BytesIO(site.read(features[0]))) as feature:
            version = ET.fromstring(feature.read("feature.xml")).get("version", "")
            if not version.startswith(expected_version + "."):
                raise ValueError(f"Stale feature version: {version}")
        with zipfile.ZipFile(io.BytesIO(site.read(plugins[0]))) as bundle:
            manifest = bundle.read("META-INF/MANIFEST.MF").decode("utf-8")
            version = re.search(r"(?m)^Bundle-Version:\s*([^\r\n]+)", manifest)
            if not version or not version.group(1).startswith(expected_version + "."):
                raise ValueError("Stale plug-in bundle version")
            plugin = ET.fromstring(bundle.read("plugin.xml"))
            registrations = [node.get("factory-class") for node in plugin.findall(".//node")]
            with zipfile.ZipFile(io.BytesIO(bundle.read("knimeebi.jar"))) as classes:
                catalog = ET.fromstring(classes.read(
                    "org/ryoo/knimeEbi/scaffolder/commands.xml"))
                commands = catalog.findall("command")
                paths = set(classes.namelist())
                if not registrations or len(registrations) != len(commands):
                    raise ValueError("Registered nodes do not match the command catalog")
                if len(registrations) != len(set(registrations)) or any(
                    not name or name.replace(".", "/") + ".class" not in paths
                    for name in registrations
                ):
                    raise ValueError("Stale or missing generated node factory classes")
                return len(registrations)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: verify_update_site.py SITE.zip X.Y.Z")
    count = verify(Path(sys.argv[1]), sys.argv[2])
    print(f"Verified update site v{sys.argv[2]}: {count} compiled node factories")
