#!/usr/bin/env python3
"""Copy a KNIME 1.1.0 workflow export with Ebi's port records upgraded to 1.1.1.

The original archive is never modified. Only the Ebi node_creation_config groups
change: group identifiers become readable labels, and a flat object_class record
becomes a single nested port_0 record. Node identities, selected types, settings,
connections and cached data are retained.
Requires Python 3.10+ and the standard library; no KNIME installation is needed.
"""
from __future__ import annotations

import argparse
from functools import lru_cache
import json
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import xml.etree.ElementTree as ET
import zipfile


GROUP = re.compile(r"ebi-(?:input-[0-9]+|output)\Z")
MAPPING_FILE = Path(__file__).resolve().parents[1] / "metadata/port-group-migration-1.1.1.json"


@lru_cache(maxsize=1)
def group_mapping() -> dict[str, dict[str, str]]:
    """Generated from the same Java catalogue/helper as the live node factories."""
    mapping = json.loads(MAPPING_FILE.read_text(encoding="utf-8"))
    if not isinstance(mapping, dict) or not mapping:
        raise ValueError("The generated port migration mapping is missing or invalid")
    return mapping


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1] if isinstance(tag, str) else ""


def direct(parent: ET.Element, tag: str, key: str) -> list[ET.Element]:
    return [item for item in parent if local_name(item.tag) == tag and item.get("key") == key]


def migrate_settings(data: bytes) -> tuple[bytes, list[str]]:
    """Return unchanged bytes unless this is a recognized legacy Ebi node record."""
    if b"org.ryoo.knimeEbi.node." not in data:
        return data, []
    if b"<!DOCTYPE" in data or b"<!ENTITY" in data:
        raise ValueError("DTD/entity declarations are not supported in workflow settings")
    root = ET.fromstring(data, parser=ET.XMLParser(target=ET.TreeBuilder(insert_comments=True)))
    factories = direct(root, "entry", "factory")
    if len(factories) != 1:
        return data, []
    renames = group_mapping().get(factories[0].get("value", ""))
    if renames is None:
        return data, []
    creation = direct(root, "config", "node_creation_config")
    if not creation:
        return data, []  # Thesis-era fixed-port nodes are outside this migration.
    if len(creation) != 1:
        raise ValueError("Ambiguous Ebi node_creation_config records")
    for old, new in renames.items():
        old_records = direct(creation[0], "config", old)
        new_records = direct(creation[0], "config", new)
        if len(old_records) + len(new_records) != 1:
            raise ValueError("Missing or ambiguous port group: " + old)
    changed: list[str] = []
    for group in creation[0]:
        if local_name(group.tag) != "config" or not GROUP.fullmatch(group.get("key", "")):
            continue
        old_key = group.get("key", "")
        if old_key not in renames:
            raise ValueError("Unexpected legacy port group: " + old_key)
        legacy = direct(group, "entry", "object_class")
        if legacy:
            children = [item for item in group if isinstance(item.tag, str)]
            if len(legacy) != 1 or len(children) != 1:
                raise ValueError("Mixed or ambiguous port records in " + old_key)
            entry = legacy[0]
            if entry.get("type") != "xstring" or not entry.get("value"):
                raise ValueError("Invalid object_class in " + old_key)
            selected = ET.Element(group.tag, {"key": "port_0"})
            group.remove(entry)
            selected.append(entry)
            group.append(selected)
        group.set("key", renames[old_key])
        changed.append(old_key)
    if not changed:
        return data, []
    if root.tag.startswith("{"):
        ET.register_namespace("", root.tag[1:].split("}", 1)[0])
    ET.indent(root, space="    ")
    return ET.tostring(root, encoding="UTF-8", xml_declaration=True) + b"\n", changed


def migrate_archive(source: Path, destination: Path) -> dict:
    source, destination = source.resolve(), destination.resolve()
    if source == destination:
        raise ValueError("Choose a different output path; the original must be retained")
    if destination.exists():
        raise ValueError("Output already exists; choose a new filename")
    if not destination.parent.is_dir():
        raise ValueError("Output parent directory does not exist")
    report = {"source": str(source), "output": str(destination), "nodes_changed": 0,
              "groups_changed": 0, "changes": []}
    created = False
    try:
        with zipfile.ZipFile(source) as original:
            seen: set[str] = set()
            has_workflow = False
            for item in original.infolist():
                name = PurePosixPath(item.filename)
                if name.is_absolute() or ".." in name.parts or "\\" in item.filename:
                    raise ValueError("Unsafe archive member: " + item.filename)
                if item.filename in seen or stat.S_ISLNK(item.external_attr >> 16):
                    raise ValueError("Duplicate or symbolic-link archive member: " + item.filename)
                seen.add(item.filename)
                has_workflow |= name.name == "workflow.knime"
            if not has_workflow:
                raise ValueError("Input is not a KNIME workflow export (workflow.knime is missing)")
            # Exclusive mode prevents overwriting an output created after the initial check.
            with zipfile.ZipFile(destination, "x") as updated:
                created = True
                updated.comment = original.comment
                for item in original.infolist():
                    data = original.read(item)
                    if PurePosixPath(item.filename).name == "settings.xml":
                        data, groups = migrate_settings(data)
                        if groups:
                            report["nodes_changed"] += 1
                            report["groups_changed"] += len(groups)
                            report["changes"].append({"file": item.filename, "groups": groups})
                    updated.writestr(item, data)
        with zipfile.ZipFile(destination) as result:
            bad = result.testzip()
            if bad:
                raise ValueError("Output archive verification failed: " + bad)
    except Exception:
        if created:
            destination.unlink(missing_ok=True)
        raise
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("workflow", type=Path, help="Exported .knwf or .zip workflow from Ebi 1.1.0")
    parser.add_argument("--output", required=True, type=Path, help="New .knwf filename; must not exist")
    options = parser.parse_args()
    try:
        report = migrate_archive(options.workflow, options.output)
    except (OSError, ValueError, ET.ParseError, zipfile.BadZipFile) as failure:
        parser.exit(1, f"Migration failed: {failure}\nThe original workflow was not modified.\n")
    print(json.dumps(report, indent=2))
    print("Original retained. Import the new archive in KNIME with Ebi 1.1.1, check the selected ports, then reset and rerun Ebi nodes.")


if __name__ == "__main__":
    main()
