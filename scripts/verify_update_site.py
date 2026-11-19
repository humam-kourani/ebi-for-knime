#!/usr/bin/env python3
"""Verify resolved p2 versions, feature/bundle wiring, and node registrations."""
import io
import lzma
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile


PLUGIN = re.compile(r"plugins/org\.ryoo\.knimeEbi_[^/]+\.jar")
FEATURE = re.compile(r"features/org\.ryoo\.knimeEbi\.feature_[^/]+\.jar")
BUNDLE_ID = "org.ryoo.knimeEbi"
FEATURE_GROUP_ID = "org.ryoo.knimeEbi.feature.feature.group"
CONFIG_ID = "configure.org.ryoo.knimeEbi"
P2_IU = "org.eclipse.equinox.p2.iu"


def _content_metadata(site: zipfile.ZipFile) -> ET.Element:
    """Read the generated p2 IUs (Tycho can emit XZ, JAR, or plain XML)."""
    names = set(site.namelist())
    if "content.xml.xz" in names:
        xml = lzma.decompress(site.read("content.xml.xz"))
    elif "content.jar" in names:
        with zipfile.ZipFile(io.BytesIO(site.read("content.jar"))) as jar:
            xml = jar.read("content.xml")
    elif "content.xml" in names:
        xml = site.read("content.xml")
    else:
        raise ValueError("Missing p2 content metadata")
    return ET.fromstring(xml)


def verify_p2_metadata(root: ET.Element, bundle_version: str) -> None:
    """Validate Tycho's standard p2 units; do not inject an extra bundle CU.

    KNIME/Eclipse already provides the default bundle configuration units.
    Feature-level p2.inf advice using $version$ can leave a literal qualifier
    in dependencies, making the feature impossible to install.
    """
    units = root.findall("./units/unit")
    def unit(identity: str) -> ET.Element:
        found = [u for u in units if u.get("id") == identity
                 and u.get("version") == bundle_version]
        if len(found) != 1:
            raise ValueError(f"Missing or ambiguous p2 unit {identity}/{bundle_version}")
        return found[0]

    bundle = unit(BUNDLE_ID)
    group = unit(FEATURE_GROUP_ID)
    if not any(a.get("classifier") == "osgi.bundle"
               and a.get("id") == BUNDLE_ID and a.get("version") == bundle_version
               for a in bundle.findall("./artifacts/artifact")):
        raise ValueError("p2 bundle IU does not point to the release JAR")
    if any(u.get("id") == CONFIG_ID for u in units):
        raise ValueError("Unexpected custom Ebi configuration unit; use standard p2 provisioning")
    if any(r.get("name") == CONFIG_ID
           for r in group.findall("./requires/required")):
        raise ValueError("Feature must not depend on a custom Ebi configuration unit")
    # Detect the exact regression from the earlier advice: p2 metadata contains
    # a literal '.qualifier' whereas the built plug-in has a timestamp qualifier.
    for target in (bundle, group):
        for req in target.findall("./requires/required"):
            if req.get("name", "").startswith("org.ryoo.knimeEbi") and ".qualifier" in req.get("range", ""):
                raise ValueError("Ebi p2 requirement contains an unresolved qualifier")
    exact = f"[{bundle_version},{bundle_version}]"
    if not any(r.get("namespace") == P2_IU and r.get("name") == BUNDLE_ID
               and r.get("range") == exact
               for r in group.findall("./requires/required")):
        raise ValueError("p2 feature group does not require the exact released Ebi bundle")


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
            feature_version = ET.fromstring(feature.read("feature.xml")).get("version", "")
            if not feature_version.startswith(expected_version + "."):
                raise ValueError(f"Stale feature version: {feature_version}")
        with zipfile.ZipFile(io.BytesIO(site.read(plugins[0]))) as bundle:
            manifest = bundle.read("META-INF/MANIFEST.MF").decode("utf-8")
            version = re.search(r"(?m)^Bundle-Version:\s*([^\r\n]+)", manifest)
            if not version or not version.group(1).startswith(expected_version + "."):
                raise ValueError("Stale plug-in bundle version")
            bundle_version = version.group(1).strip()
            if bundle_version != feature_version:
                raise ValueError("Feature and bundle versions must match exactly")
            if plugins[0] != f"plugins/{BUNDLE_ID}_{bundle_version}.jar":
                raise ValueError("p2 plug-in filename does not match its bundle version")
            if features[0] != f"features/org.ryoo.knimeEbi.feature_{bundle_version}.jar":
                raise ValueError("p2 feature filename does not match its feature version")
            verify_p2_metadata(_content_metadata(site), bundle_version)
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
