"""Regressions for Tycho's version-resolved p2 feature/bundle wiring.

A custom feature-level configuration IU was removed because it relied on a
literal '.qualifier' version which Tycho cannot resolve against the bundle.
Normal Eclipse p2 installation uses the host product's standard bundle CUs.
"""
import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("verify_p2", ROOT / "scripts/verify_update_site.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)
VERSION = "1.2.3.202611191500"
EXACT = f"[{VERSION},{VERSION}]"


def sample_metadata():
    root = ET.Element("repository")
    units = ET.SubElement(root, "units")
    bundle = ET.SubElement(units, "unit", id=verify.BUNDLE_ID, version=VERSION)
    artifacts = ET.SubElement(bundle, "artifacts")
    ET.SubElement(artifacts, "artifact", classifier="osgi.bundle", id=verify.BUNDLE_ID, version=VERSION)
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


if __name__ == "__main__":
    unittest.main()
