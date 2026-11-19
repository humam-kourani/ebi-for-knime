#!/usr/bin/env python3
"""Verify a p2 site's versions, provisioning actions, and node registrations."""
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
P2_BUNDLE_TYPE = "org.eclipse.equinox.p2.eclipse.type"


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


def verify_p2_provisioning(root: ET.Element, bundle_version: str) -> None:
    """Require an explicit, version-pinned install/uninstall CU for this bundle.

    The generated bundle IU normally contains *only* a manifest instruction.
    Eclipse products may supply a default CU, but relying on an unspecified
    host product leaves installation/update behavior fragile. An Ebi-specific
    CU must be required by the installable feature and bind exactly this bundle.
    """
    def unit(identity: str) -> ET.Element:
        found = [u for u in root.findall("./units/unit")
                 if u.get("id") == identity and u.get("version") == bundle_version]
        if len(found) != 1:
            raise ValueError(f"Missing or ambiguous p2 unit {identity}/{bundle_version}")
        return found[0]

    bundle = unit(BUNDLE_ID)
    if not any(a.get("classifier") == "osgi.bundle"
               and a.get("id") == BUNDLE_ID and a.get("version") == bundle_version
               for a in bundle.findall("./artifacts/artifact")):
        raise ValueError("p2 bundle IU does not point to the release JAR")
    group = unit(FEATURE_GROUP_ID)
    cu = unit(CONFIG_ID)
    exact = f"[{bundle_version},{bundle_version}]"

    def has_requirement(parent: ET.Element, namespace: str, identity: str, version_range: str,
                        section: str = "requires") -> bool:
        return any(req.get("namespace") == namespace and req.get("name") == identity
                   and req.get("range") == version_range
                   for req in parent.findall(f"./{section}/required"))

    if not has_requirement(group, P2_IU, BUNDLE_ID, exact):
        raise ValueError("p2 feature group does not pin the released Ebi bundle")
    if not has_requirement(group, P2_IU, CONFIG_ID, exact):
        raise ValueError("p2 feature group does not install the Ebi configuration unit")
    if not has_requirement(cu, "osgi.bundle", BUNDLE_ID, exact):
        raise ValueError("p2 configuration unit does not require the released Ebi bundle")
    if not has_requirement(cu, "osgi.bundle", BUNDLE_ID, exact, "hostRequirements"):
        raise ValueError("p2 configuration unit is not pinned to the Ebi bundle host")
    if not has_requirement(cu, P2_BUNDLE_TYPE, "bundle", "[1.0.0,2.0.0)", "hostRequirements"):
        raise ValueError("p2 configuration unit cannot override the default bundle CU")
    if not any(prop.get("name") == "org.eclipse.equinox.p2.type.fragment"
               and prop.get("value") == "true"
               for prop in cu.findall("./properties/property")):
        raise ValueError("Ebi p2 configuration unit is not an IU fragment")
    instructions = {i.get("key"): "".join(i.itertext()).strip()
                    for i in cu.findall("./touchpointData/instructions/instruction")}
    for phase, action in (("install", "installBundle"), ("uninstall", "uninstallBundle")):
        statement = f"org.eclipse.equinox.p2.touchpoint.eclipse.{action}(bundle:${{artifact}})"
        if statement not in instructions.get(phase, ""):
            raise ValueError(f"Ebi p2 configuration unit missing {phase} action {action}")
    if "setStartLevel(startLevel:4)" not in instructions.get("configure", ""):
        raise ValueError("Ebi p2 configuration unit must use a normal lazy bundle start level")
    if "setStartLevel(startLevel:-1)" not in instructions.get("unconfigure", ""):
        raise ValueError("Ebi p2 configuration unit must reset the start level on uninstall")


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
            verify_p2_provisioning(_content_metadata(site), bundle_version)
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
