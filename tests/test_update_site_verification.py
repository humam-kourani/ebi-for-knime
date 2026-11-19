"""Regressions for Tycho's version-resolved p2 feature/bundle wiring.

A custom feature-level configuration IU was removed because it relied on a
literal '.qualifier' version which Tycho cannot resolve against the bundle.
Normal Eclipse p2 installation uses the host product's standard bundle CUs.
"""
import importlib.util
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("verify_p2", ROOT / "scripts/verify_update_site.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)
VERSION = "1.2.3.202611191500"
EXACT = f"[{VERSION},{VERSION}]"
FACTORY = "org.ryoo.knimeEbi.node.EbiTestNodeFactory"


def manifest(classpath="."):
    return ("Manifest-Version: 1.0\r\n"
            f"Bundle-SymbolicName: {verify.BUNDLE_ID};singleton:=true\r\n"
            f"Bundle-Version: {VERSION}\r\n"
            f"Bundle-ClassPath: {classpath}\r\n\r\n").encode()


def sample_metadata():
    root = ET.Element("repository")
    units = ET.SubElement(root, "units")
    bundle = ET.SubElement(units, "unit", id=verify.BUNDLE_ID, version=VERSION)
    artifacts = ET.SubElement(bundle, "artifacts")
    ET.SubElement(artifacts, "artifact", classifier="osgi.bundle", id=verify.BUNDLE_ID, version=VERSION)
    instructions = ET.SubElement(ET.SubElement(bundle, "touchpointData"), "instructions")
    ET.SubElement(instructions, "instruction", key="manifest").text = manifest().decode()
    group = ET.SubElement(units, "unit", id=verify.FEATURE_GROUP_ID, version=VERSION)
    requirements = ET.SubElement(group, "requires")
    ET.SubElement(requirements, "required", namespace=verify.P2_IU,
                  name=verify.BUNDLE_ID, range=EXACT)
    return root


class P2MetadataTest(unittest.TestCase):
    def test_normal_tycho_metadata_is_accepted_without_custom_config_unit(self):
        verify.verify_p2_metadata(sample_metadata(), VERSION)

    def test_missing_bundle_is_rejected(self):
        root = sample_metadata()
        root.find(f"./units/unit[@id='{verify.BUNDLE_ID}']").set("id", "wrong")
        with self.assertRaisesRegex(ValueError, "Missing or ambiguous p2 unit"):
            verify.verify_p2_metadata(root, VERSION)

    def test_missing_feature_group_is_rejected(self):
        root = sample_metadata()
        root.find(f"./units/unit[@id='{verify.FEATURE_GROUP_ID}']").set("id", "wrong")
        with self.assertRaisesRegex(ValueError, "Missing or ambiguous p2 unit"):
            verify.verify_p2_metadata(root, VERSION)

    def test_mismatched_bundle_requirement_is_rejected(self):
        root = sample_metadata()
        root.find(f"./units/unit[@id='{verify.FEATURE_GROUP_ID}']/requires/required").set("range", "[1.2.3,1.2.4)")
        with self.assertRaisesRegex(ValueError, "does not require the exact released Ebi bundle"):
            verify.verify_p2_metadata(root, VERSION)

    def test_unresolved_qualifier_is_rejected(self):
        root = sample_metadata()
        root.find(f"./units/unit[@id='{verify.FEATURE_GROUP_ID}']/requires/required").set("range", "[1.2.3.qualifier,1.2.3.qualifier]")
        with self.assertRaisesRegex(ValueError, "unresolved qualifier"):
            verify.verify_p2_metadata(root, VERSION)

    def test_custom_configuration_unit_is_rejected(self):
        root = sample_metadata()
        ET.SubElement(root.find("./units"), "unit", id=verify.CONFIG_ID, version=VERSION)
        with self.assertRaisesRegex(ValueError, "custom Ebi configuration unit"):
            verify.verify_p2_metadata(root, VERSION)

    def test_reference_to_custom_configuration_unit_is_rejected(self):
        root = sample_metadata()
        group = root.find(f"./units/unit[@id='{verify.FEATURE_GROUP_ID}']")
        ET.SubElement(group.find("./requires"), "required", namespace=verify.P2_IU,
                      name=verify.CONFIG_ID, range=EXACT)
        with self.assertRaisesRegex(ValueError, "custom Ebi configuration unit"):
            verify.verify_p2_metadata(root, VERSION)

    def test_missing_artifact_is_rejected(self):
        root = sample_metadata()
        root.find(f"./units/unit[@id='{verify.BUNDLE_ID}']/artifacts/artifact").set("version", "1.0.0")
        with self.assertRaisesRegex(ValueError, "does not point to the release JAR"):
            verify.verify_p2_metadata(root, VERSION)

    def test_duplicate_units_are_rejected(self):
        root = sample_metadata()
        ET.SubElement(root.find("./units"), "unit", id=verify.BUNDLE_ID, version=VERSION)
        with self.assertRaisesRegex(ValueError, "Missing or ambiguous p2 unit"):
            verify.verify_p2_metadata(root, VERSION)

    def test_stale_touchpoint_manifest_is_rejected(self):
        root = sample_metadata()
        entry = root.find(".//instruction[@key='manifest']")
        entry.text = entry.text.replace(VERSION, "1.0.0.202601010000")
        with self.assertRaisesRegex(ValueError, "touchpoint manifest does not match"):
            verify.verify_p2_metadata(root, VERSION)

    def test_missing_touchpoint_manifest_is_rejected(self):
        root = sample_metadata()
        entry = root.find(".//instruction[@key='manifest']")
        entry.set("key", "other")
        with self.assertRaisesRegex(ValueError, "Missing or ambiguous p2 bundle touchpoint"):
            verify.verify_p2_metadata(root, VERSION)


def archive(entries):
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w") as output:
        for name, content in entries.items():
            output.writestr(name, content)
    return result.getvalue()


class PackagedSiteTest(unittest.TestCase):
    """Corrupt complete miniature sites at the publication boundary.

    Placeholder class/native bytes deliberately test packaging and integrity;
    Equinox and JNI execution are covered by the separate integration gates.
    """
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.site = Path(self.temp.name) / "site.zip"
        self.bundle = {name: b"fixture bytecode" for name in verify.RUNTIME_CLASSES}
        self.bundle.update({
            "META-INF/MANIFEST.MF": manifest(),
            "plugin.xml": ('<plugin><extension point="org.knime.workbench.repository.nodes">'
                           f'<node factory-class="{FACTORY}"/>'
                           '</extension></plugin>').encode(),
            verify.CATALOG: b'<commands><command name="test"/></commands>',
            FACTORY.replace(".", "/") + ".class": b"fixture factory bytecode",
            "org/ryoo/knimeEbi/node/default.png": b"fixture icon",
            "icons/ebi.png": b"fixture icon",
            "LICENSE": b"fixture license",
            "lib/LICENSE-Ebi-MIT.txt": b"fixture native license",
        })
        self.lock = {"version": "0.3.29", "artifacts": []}
        for platform, resource in verify.NATIVE_RESOURCES.items():
            payload = b"native fixture for " + platform.encode()
            self.bundle[resource] = payload
            self.lock["artifacts"].append({"platform": platform, "path": "extension/" + resource,
                                           "size": len(payload),
                                           "sha256": hashlib.sha256(payload).hexdigest(),
                                           "embedded_version": "0.3.29"})
        self.bundle["lib/native-artifacts.json"] = json.dumps(self.lock).encode()
        self.feature = ET.Element("feature", id=verify.FEATURE_ID, version=VERSION)
        ET.SubElement(self.feature, "plugin", id=verify.BUNDLE_ID, version=VERSION)
        self.metadata = sample_metadata()

    def check(self, **options):
        self.site.write_bytes(archive({
            f"plugins/{verify.BUNDLE_ID}_{VERSION}.jar": archive(self.bundle),
            f"features/{verify.FEATURE_ID}_{VERSION}.jar": archive({
                "feature.xml": ET.tostring(self.feature)}),
            "content.xml": ET.tostring(self.metadata),
            "artifacts.xml": b"<repository/>",
        }))
        return verify.verify(self.site, "1.2.3", **options)

    def test_flat_release_with_matching_native_payload_passes(self):
        self.assertEqual(self.check(expected_native_lock=self.lock), 1)

    def test_folded_symbolic_name_is_supported(self):
        self.bundle["META-INF/MANIFEST.MF"] = manifest().replace(
            b";singleton:=true", b";\r\n singleton:=true")
        self.assertEqual(self.check(), 1)

    def test_new_releases_reject_nested_classes_but_baseline_mode_accepts_them(self):
        nested = {name: self.bundle.pop(name) for name in list(self.bundle)
                  if name.startswith("org/")}
        self.bundle["knimeebi.jar"] = archive(nested)
        self.bundle["META-INF/MANIFEST.MF"] = manifest("knimeebi.jar")
        with self.assertRaisesRegex(ValueError, "flat bundle"):
            self.check()
        self.assertEqual(self.check(allow_legacy_layout=True), 1)

    def test_leftover_inner_jar_is_rejected_even_with_flat_classpath(self):
        self.bundle["knimeebi.jar"] = archive({"stale.class": b"old"})
        with self.assertRaisesRegex(ValueError, "flat bundle"):
            self.check()

    def test_changed_bundle_identity_is_rejected(self):
        self.bundle["META-INF/MANIFEST.MF"] = manifest().replace(
            verify.BUNDLE_ID.encode(), b"org.wrong.bundle")
        with self.assertRaisesRegex(ValueError, "singleton bundle identity"):
            self.check()

    def test_non_singleton_bundle_is_rejected(self):
        self.bundle["META-INF/MANIFEST.MF"] = manifest().replace(b";singleton:=true", b"")
        with self.assertRaisesRegex(ValueError, "singleton bundle identity"):
            self.check()

    def test_feature_must_pin_actual_bundle_version(self):
        self.feature.find("plugin").set("version", "0.0.0")
        with self.assertRaisesRegex(ValueError, "Feature does not include the exact"):
            self.check()

    def test_unresolved_bundle_qualifier_is_rejected(self):
        self.bundle["META-INF/MANIFEST.MF"] = manifest().replace(
            VERSION.encode(), b"1.2.3.qualifier")
        with self.assertRaisesRegex(ValueError, "Stale plug-in bundle version"):
            self.check()

    def test_missing_worker_class_is_rejected(self):
        del self.bundle["org/processmining/ebi/EbiNativeWorker.class"]
        with self.assertRaisesRegex(ValueError, "Missing packaged class or resource"):
            self.check()

    def test_missing_registered_factory_is_rejected(self):
        del self.bundle[FACTORY.replace(".", "/") + ".class"]
        with self.assertRaisesRegex(ValueError, "missing generated node factory"):
            self.check()

    def test_new_catalog_node_requires_a_registration(self):
        self.bundle[verify.CATALOG] = b'<commands><command name="test"/><command name="new"/></commands>'
        with self.assertRaisesRegex(ValueError, "Registered nodes do not match"):
            self.check()

    def test_missing_native_file_is_rejected(self):
        del self.bundle["lib/windows-x86_64/ebi.dll"]
        with self.assertRaisesRegex(ValueError, "Missing native"):
            self.check()

    def test_replaced_native_bytes_are_rejected_by_both_publication_paths(self):
        payload = self.bundle["lib/linux-x86_64/libebi.so"]
        self.bundle["lib/linux-x86_64/libebi.so"] = b"X" + payload[1:]
        for options in ({}, {"expected_native_lock": self.lock}):
            with self.subTest(options=bool(options)), self.assertRaisesRegex(ValueError, "failed checksum"):
                self.check(**options)

    def test_native_payload_must_match_the_tracked_build_lock(self):
        other_lock = dict(self.lock, version="0.3.28")
        with self.assertRaisesRegex(ValueError, "differs from tracked lock"):
            self.check(expected_native_lock=other_lock)

    def test_native_provenance_cannot_omit_a_platform(self):
        self.lock["artifacts"].pop()
        self.bundle["lib/native-artifacts.json"] = json.dumps(self.lock).encode()
        with self.assertRaisesRegex(ValueError, "one native artifact per supported platform"):
            self.check()


if __name__ == "__main__":
    unittest.main()
