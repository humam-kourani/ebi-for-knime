"""Regressions for version-pinned p2 install/uninstall configuration units."""
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
    ET.SubElement(requirements, "required", namespace=verify.P2_IU,
                  name=verify.CONFIG_ID, range=EXACT)

    cu = ET.SubElement(units, "unit", id=verify.CONFIG_ID, version=VERSION)
    properties = ET.SubElement(cu, "properties")
    ET.SubElement(properties, "property", name="org.eclipse.equinox.p2.type.fragment", value="true")
    requirements = ET.SubElement(cu, "requires")
    ET.SubElement(requirements, "required", namespace="osgi.bundle",
                  name=verify.BUNDLE_ID, range=EXACT)
    hosts = ET.SubElement(cu, "hostRequirements")
    ET.SubElement(hosts, "required", namespace="osgi.bundle", name=verify.BUNDLE_ID, range=EXACT)
    ET.SubElement(hosts, "required", namespace=verify.P2_BUNDLE_TYPE,
                  name="bundle", range="[1.0.0,2.0.0)")
    instructions = ET.SubElement(ET.SubElement(cu, "touchpointData"), "instructions")
    prefix = "org.eclipse.equinox.p2.touchpoint.eclipse."
    ET.SubElement(instructions, "instruction", key="install").text = prefix + "installBundle(bundle:${artifact});"
    ET.SubElement(instructions, "instruction", key="uninstall").text = prefix + "uninstallBundle(bundle:${artifact});"
    ET.SubElement(instructions, "instruction", key="configure").text = prefix + "setStartLevel(startLevel:4);"
    ET.SubElement(instructions, "instruction", key="unconfigure").text = prefix + "setStartLevel(startLevel:-1);"
    return root


class P2ProvisioningTest(unittest.TestCase):
    def test_version_pinned_provisioning_is_accepted(self):
        verify.verify_p2_provisioning(sample_metadata(), VERSION)

    def test_missing_cu_is_rejected(self):
        root = sample_metadata()
        root.find(f"./units/unit[@id='{verify.CONFIG_ID}']").clear()
        with self.assertRaisesRegex(ValueError, "Missing or ambiguous p2 unit"):
            verify.verify_p2_provisioning(root, VERSION)

    def test_cu_not_required_by_feature_is_rejected(self):
        root = sample_metadata()
        root.find(f"./units/unit[@id='{verify.FEATURE_GROUP_ID}']/requires/required[@name='{verify.CONFIG_ID}']").set("range", "0.0.0")
        with self.assertRaisesRegex(ValueError, "does not install the Ebi configuration unit"):
            verify.verify_p2_provisioning(root, VERSION)

    def test_wrong_bundle_host_version_is_rejected(self):
        root = sample_metadata()
        root.find(f"./units/unit[@id='{verify.CONFIG_ID}']/hostRequirements/required[@name='{verify.BUNDLE_ID}']").set("range", "0.0.0")
        with self.assertRaisesRegex(ValueError, "not pinned to the Ebi bundle host"):
            verify.verify_p2_provisioning(root, VERSION)

    def test_install_and_uninstall_must_be_present(self):
        for action in ("install", "uninstall"):
            with self.subTest(action=action):
                root = sample_metadata()
                root.find(f"./units/unit[@id='{verify.CONFIG_ID}']/touchpointData/instructions/instruction[@key='{action}']").text = ""
                with self.assertRaisesRegex(ValueError, f"missing {action} action"):
                    verify.verify_p2_provisioning(root, VERSION)

    def test_unconfigure_must_reset_the_start_level(self):
        root = sample_metadata()
        root.find(f"./units/unit[@id='{verify.CONFIG_ID}']/touchpointData/instructions/instruction[@key='unconfigure']").text = ""
        with self.assertRaisesRegex(ValueError, "must reset the start level"):
            verify.verify_p2_provisioning(root, VERSION)

    def test_default_cu_would_override_wrong_host(self):
        root = sample_metadata()
        root.find(f"./units/unit[@id='{verify.CONFIG_ID}']/hostRequirements/required[@name='bundle']").set("name", "wrong")
        with self.assertRaisesRegex(ValueError, "cannot override the default bundle CU"):
            verify.verify_p2_provisioning(root, VERSION)

    def test_wrong_or_missing_bundle_artifact_is_rejected(self):
        root = sample_metadata()
        root.find(f"./units/unit[@id='{verify.BUNDLE_ID}']/artifacts/artifact").set("version", "1.0.0")
        with self.assertRaisesRegex(ValueError, "does not point to the release JAR"):
            verify.verify_p2_provisioning(root, VERSION)


if __name__ == "__main__":
    unittest.main()
