#!/usr/bin/env python3
"""Verify a release's flat bundle, p2 wiring, nodes, and matching JNI payload."""
import argparse
from contextlib import nullcontext
import hashlib
import io
import json
import lzma
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile


PLUGIN = re.compile(r"plugins/org\.ryoo\.knimeEbi_[^/]+\.jar")
FEATURE = re.compile(r"features/org\.ryoo\.knimeEbi\.feature_[^/]+\.jar")
BUNDLE_ID = "org.ryoo.knimeEbi"
FEATURE_ID = "org.ryoo.knimeEbi.feature"
FEATURE_GROUP_ID = "org.ryoo.knimeEbi.feature.feature.group"
CONFIG_ID = "configure.org.ryoo.knimeEbi"
P2_IU = "org.eclipse.equinox.p2.iu"
CATALOG = "org/ryoo/knimeEbi/scaffolder/commands.xml"
RUNTIME_CLASSES = (
    "org/processmining/ebi/CallEbi.class",
    "org/processmining/ebi/CallEbi$CancellationChecker.class",
    "org/processmining/ebi/EbiNativeBuild.class",
    "org/processmining/ebi/EbiNativeWorker.class",
    "org/processmining/ebi/IsolatedEbiRunner.class",
    "org/processmining/ebi/NativeLibraryLoader.class",
    "org/processmining/ebi/NativePlatform.class",
    "org/ebitools/Ebi.class",
    "org/ryoo/knimeEbi/scaffolder/EbiCatalog.class",
)
NATIVE_RESOURCES = {
    "linux-x86_64": "lib/linux-x86_64/libebi.so",
    "windows-x86_64": "lib/windows-x86_64/ebi.dll",
}


def manifest_headers(text: str) -> dict[str, str]:
    """Read main-section headers, including standard JAR continuation lines."""
    result = {}
    for line in re.sub(r"\r?\n ", "", text.strip()).splitlines():
        if not line:
            break
        if ":" not in line:
            raise ValueError("Malformed bundle manifest header")
        key, value = line.split(":", 1)
        if key in result:
            raise ValueError("Duplicate bundle manifest header: " + key)
        result[key] = value.strip()
    return result


def _resolved_version(version: str, expected: str) -> bool:
    return bool(re.fullmatch(re.escape(expected) + r"\.[A-Za-z0-9_-]+", version)
                and not version.endswith(".qualifier"))


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

    # p2 installs using this manifest, so it must describe the JAR we verified.
    manifests = bundle.findall("./touchpointData/instructions/instruction[@key='manifest']")
    if len(manifests) != 1:
        raise ValueError("Missing or ambiguous p2 bundle touchpoint manifest")
    headers = manifest_headers("".join(manifests[0].itertext()))
    if (headers.get("Bundle-Version") != bundle_version
            or headers.get("Bundle-SymbolicName", "").split(";", 1)[0] != BUNDLE_ID):
        raise ValueError("p2 touchpoint manifest does not match the released bundle")


def verify_native_payload(bundle: zipfile.ZipFile, expected_lock: dict | None = None) -> None:
    """Use the same payload checks during collection and Pages promotion."""
    if "lib/native-artifacts.json" not in bundle.namelist():
        raise ValueError("Missing packaged native provenance")
    lock = json.loads(bundle.read("lib/native-artifacts.json"))
    if expected_lock is not None and lock != expected_lock:
        raise ValueError("Packaged native provenance differs from tracked lock")
    entries = lock.get("artifacts", [])
    if (len(entries) != len(NATIVE_RESOURCES)
            or {entry.get("platform") for entry in entries} != set(NATIVE_RESOURCES)):
        raise ValueError("Expected one native artifact per supported platform")
    if not re.fullmatch(r"[0-9A-Za-z.+-]+", lock.get("version", "")):
        raise ValueError("Missing or invalid native version in packaged provenance")
    for entry in entries:
        resource = NATIVE_RESOURCES[entry["platform"]]
        if entry.get("path") != "extension/" + resource:
            raise ValueError("Native provenance has an unexpected resource path")
        if (not re.fullmatch(r"[0-9a-f]{64}", entry.get("sha256", ""))
                or type(entry.get("size")) is not int or entry["size"] <= 0):
            raise ValueError("Native provenance has an invalid digest or size")
        if entry.get("embedded_version", lock["version"]) != lock["version"]:
            raise ValueError("Native provenance contains inconsistent Ebi versions")
        if resource not in bundle.namelist():
            raise ValueError("Missing native " + resource)
        with bundle.open(resource) as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        if bundle.getinfo(resource).file_size != entry["size"] or digest != entry["sha256"]:
            raise ValueError("Native file failed checksum inside p2: " + resource)


def _verify_classes(classes: zipfile.ZipFile, plugin: ET.Element) -> int:
    paths = set(classes.namelist())
    for required in (*RUNTIME_CLASSES, CATALOG, "org/ryoo/knimeEbi/node/default.png"):
        if required not in paths:
            raise ValueError("Missing packaged class or resource: " + required)
    registrations = [node.get("factory-class") for node in plugin.findall(
        "./extension[@point='org.knime.workbench.repository.nodes']/node")]
    commands = ET.fromstring(classes.read(CATALOG)).findall("command")
    if not registrations or len(registrations) != len(commands):
        raise ValueError("Registered nodes do not match the command catalog")
    if len(registrations) != len(set(registrations)) or any(
        not name or name.replace(".", "/") + ".class" not in paths
        for name in registrations
    ):
        raise ValueError("Stale or missing generated node factory classes")
    return len(registrations)


def verify(site_path: Path, expected_version: str, *, allow_legacy_layout: bool = False,
           expected_native_lock: dict | None = None) -> int:
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", expected_version):
        raise ValueError("Expected a three-part release version")
    with zipfile.ZipFile(site_path) as site:
        files = site.namelist()
        plugins = [name for name in files if PLUGIN.fullmatch(name)]
        features = [name for name in files if FEATURE.fullmatch(name)]
        if len(plugins) != 1 or len(features) != 1:
            raise ValueError("Expected exactly one Ebi plug-in and feature")
        with zipfile.ZipFile(io.BytesIO(site.read(features[0]))) as feature:
            installer = ET.fromstring(feature.read("feature.xml"))
            feature_version = installer.get("version", "")
            if installer.get("id") != FEATURE_ID:
                raise ValueError("Incorrect feature identity")
            if not _resolved_version(feature_version, expected_version):
                raise ValueError(f"Stale feature version: {feature_version}")
        with zipfile.ZipFile(io.BytesIO(site.read(plugins[0]))) as bundle:
            headers = manifest_headers(bundle.read("META-INF/MANIFEST.MF").decode("utf-8"))
            identity = headers.get("Bundle-SymbolicName", "")
            if (identity.split(";", 1)[0] != BUNDLE_ID
                    or not re.search(r';\s*singleton\s*:=\s*"?true"?\s*(?:;|$)', identity)):
                raise ValueError("Ebi must retain its singleton bundle identity")
            bundle_version = headers.get("Bundle-Version", "")
            if not _resolved_version(bundle_version, expected_version):
                raise ValueError("Stale plug-in bundle version")
            if bundle_version != feature_version:
                raise ValueError("Feature and bundle versions must match exactly")
            included = installer.findall(f"./plugin[@id='{BUNDLE_ID}']")
            if len(included) != 1 or included[0].get("version") != bundle_version:
                raise ValueError("Feature does not include the exact released Ebi bundle")
            if plugins[0] != f"plugins/{BUNDLE_ID}_{bundle_version}.jar":
                raise ValueError("p2 plug-in filename does not match its bundle version")
            if features[0] != f"features/org.ryoo.knimeEbi.feature_{bundle_version}.jar":
                raise ValueError("p2 feature filename does not match its feature version")
            verify_p2_metadata(_content_metadata(site), bundle_version)
            paths = set(bundle.namelist())
            for required in ("plugin.xml", "icons/ebi.png", "LICENSE", "lib/LICENSE-Ebi-MIT.txt"):
                if required not in paths:
                    raise ValueError("Missing packaged resource " + required)
            classpath = headers.get("Bundle-ClassPath", ".")
            legacy = allow_legacy_layout and classpath == "knimeebi.jar"
            if not legacy and (classpath != "." or "knimeebi.jar" in paths):
                raise ValueError("Release must use a flat bundle with Bundle-ClassPath: .")
            verify_native_payload(bundle, expected_native_lock)
            plugin = ET.fromstring(bundle.read("plugin.xml"))
            # Historical baselines may be checked explicitly by the upgrade test;
            # neither release workflow enables this compatibility mode.
            contents = (zipfile.ZipFile(io.BytesIO(bundle.read("knimeebi.jar")))
                        if legacy else nullcontext(bundle))
            with contents as classes:
                return _verify_classes(classes, plugin)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("site", type=Path)
    parser.add_argument("version")
    parser.add_argument("--allow-legacy-layout", action="store_true",
                        help="Only for checking a historical upgrade-test baseline")
    args = parser.parse_args()
    count = verify(args.site, args.version, allow_legacy_layout=args.allow_legacy_layout)
    print(f"Verified update site v{args.version}: {count} compiled node factories")
