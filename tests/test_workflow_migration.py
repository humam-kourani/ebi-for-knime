"""Regression checks for non-destructive Ebi 1.1.0 workflow migration."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("migration", ROOT / "scripts/migrate-workflow-1.1.1.py")
migration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(migration)

LEGACY = b'''<?xml version="1.0" encoding="UTF-8"?>
<config xmlns="http://www.knime.org/2008/09/XMLConfig" key="settings.xml">
 <entry key="factory" type="xstring" value="org.ryoo.knimeEbi.node.EbiInformationNodeFactory"/>
 <config key="model"><entry key="m_input1" type="xstring" value="untouched"/></config>
 <config key="node_creation_config">
  <config key="ebi-input-0"><entry key="object_class" type="xstring" value="org.pm4knime.portobject.ProcessTreePortObject"/></config>
  <config key="ebi-output"/>
 </config>
</config>'''


class WorkflowMigrationTest(unittest.TestCase):
    def test_legacy_selection_preserved_and_repeat_is_noop(self):
        changed, groups = migration.migrate_settings(LEGACY)
        self.assertEqual(groups, ["ebi-input-0", "ebi-output"])
        root = ET.fromstring(changed)
        creation = migration.direct(root, "config", "node_creation_config")[0]
        group = migration.direct(creation, "config", "Input: Log or process model")[0]
        selected = migration.direct(group, "config", "port_0")[0]
        self.assertEqual(migration.direct(selected, "entry", "object_class")[0].get("value"),
                         "org.pm4knime.portobject.ProcessTreePortObject")
        self.assertEqual(migration.migrate_settings(changed), (changed, []))
        self.assertEqual(migration.direct(root, "config", "model")[0][0].get("value"), "untouched")
        self.assertEqual(len(migration.direct(creation, "config", "Output: Object information")[0]), 0)

    def test_other_factories_and_fixed_nodes_unchanged(self):
        other = LEGACY.replace(b"org.ryoo.knimeEbi.node.EbiInformationNodeFactory", b"org.example.OtherNodeFactory")
        self.assertEqual(migration.migrate_settings(other), (other, []))
        fixed = LEGACY.replace(b"node_creation_config", b"legacy-settings")
        self.assertEqual(migration.migrate_settings(fixed), (fixed, []))

    def test_mixed_legacy_and_new_records_rejected(self):
        mixed = LEGACY.replace(b'<config key="ebi-input-0">', b'<config key="ebi-input-0"><config key="port_0"/>')
        with self.assertRaisesRegex(ValueError, "Mixed"):
            migration.migrate_settings(mixed)

    def test_ambiguous_or_missing_group_identifiers_rejected(self):
        duplicate = LEGACY.replace(b'<config key="ebi-output"/>',
            b'<config key="ebi-output"/><config key="Output: Object information"/>')
        missing = LEGACY.replace(b'<config key="ebi-output"/>', b'')
        for invalid in [duplicate, missing]:
            with self.assertRaisesRegex(ValueError, "Missing or ambiguous"):
                migration.migrate_settings(invalid)

    def test_archive_keeps_original_connections_and_other_data(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as folder:
            original, updated = Path(folder) / "old.knwf", Path(folder) / "new.knwf"
            with zipfile.ZipFile(original, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.comment = b"Original workflow"
                archive.writestr("Demo/workflow.knime", b"<config><connections>same</connections></config>")
                archive.writestr("Demo/Ebi (#2)/settings.xml", LEGACY)
                archive.writestr("Demo/Ebi (#2)/port_0/data.zip", bytes(range(256)))
            before = hashlib.sha256(original.read_bytes()).digest()
            report = migration.migrate_archive(original, updated)
            self.assertEqual(report["nodes_changed"], 1)
            self.assertEqual(report["groups_changed"], 2)
            self.assertEqual(hashlib.sha256(original.read_bytes()).digest(), before)
            with zipfile.ZipFile(original) as source, zipfile.ZipFile(updated) as result:
                self.assertEqual(source.namelist(), result.namelist())
                self.assertEqual(source.comment, result.comment)
                for item in source.namelist():
                    if not item.endswith("settings.xml"):
                        self.assertEqual(source.read(item), result.read(item))

    def test_existing_destination_and_in_place_conversion_rejected(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as folder:
            source, output = Path(folder) / "source.knwf", Path(folder) / "existing.knwf"
            source.write_bytes(b"original")
            output.write_bytes(b"keep")
            with self.assertRaises(ValueError):
                migration.migrate_archive(source, source)
            with self.assertRaises(ValueError):
                migration.migrate_archive(source, output)
            self.assertEqual(source.read_bytes(), b"original")
            self.assertEqual(output.read_bytes(), b"keep")

    def test_bad_node_aborts_without_leaving_partial_output(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as folder:
            source, output = Path(folder) / "source.knwf", Path(folder) / "output.knwf"
            with zipfile.ZipFile(source, "w") as archive:
                archive.writestr("Demo/workflow.knime", b"<config/>")
                archive.writestr("Demo/Ebi/settings.xml", LEGACY.replace(b'type="xstring" value="org.pm4knime', b'type="xint" value="org.pm4knime'))
            with self.assertRaises(ValueError):
                migration.migrate_archive(source, output)
            self.assertFalse(output.exists())
            self.assertTrue(source.exists())

    def test_unsafe_archive_members_rejected_before_output_created(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as folder:
            source, output = Path(folder) / "source.knwf", Path(folder) / "output.knwf"
            with zipfile.ZipFile(source, "w") as archive:
                archive.writestr("workflow.knime", b"<config/>")
                archive.writestr("../settings.xml", LEGACY)
            with self.assertRaisesRegex(ValueError, "Unsafe"):
                migration.migrate_archive(source, output)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
