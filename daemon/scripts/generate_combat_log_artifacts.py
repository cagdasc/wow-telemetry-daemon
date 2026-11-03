#!/usr/bin/env python3
"""Generate Rust event metadata and JSON artifacts from combat-log-spec.yml."""

import argparse
import datetime
import json
import re
import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "resources" / "combat-log-spec.yml"
RUST_PATH = ROOT / "src" / "spec_events.rs"
JSON_SPEC_PATH = ROOT / "resources" / "combat-log-spec.json"
EVENT_SCHEMA_PATH = ROOT / "resources" / "combat-log-event.schema.json"
ADDITIONAL_EVENTS = ("PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED")
UNIT_BLOCK_FIELDS = (
    ("guid", "string"),
    ("name", "quoted_string"),
    ("flags", "hex_uint32"),
    ("raid_flags", "hex_uint32"),
)
SPELL_BLOCK_FIELDS = (
    ("id", "integer"),
    ("name", "quoted_string"),
    ("school", "hex_bitfield"),
)
FIELD_TYPE_VARIANTS = {
    "string": "String",
    "quoted_string": "QuotedString",
    "integer": "Integer",
    "long": "Long",
    "float": "Float",
    "hex_uint32": "HexUint32",
    "hex_bitfield": "HexBitfield",
    "bool_or_nil": "BoolOrNil",
    "boolean_int": "BooleanInt",
    "integer_array": "IntegerArray",
    "bracket_value": "BracketValue",
}


def inherits(events, event_name, property_name):
    event = events[event_name]
    if property_name in event:
        return event[property_name]
    parent = event.get("inherits")
    if parent is None:
        return None
    if parent not in events:
        raise ValueError(f"{event_name} inherits unknown event {parent}")
    return inherits(events, parent, property_name)


def rust_array(name, values, public=False):
    visibility = "pub " if public else ""
    lines = [f"{visibility}const {name}: &[&str] = &["]
    lines.extend(f'    "{value}",' for value in values)
    lines.extend(["];", ""])
    return lines


def offset_range(offset):
    if isinstance(offset, int):
        return [offset]
    start, end = (int(part) for part in str(offset).split("-", 1))
    return list(range(start, end + 1))


def event_fields(spec, event_name):
    events = spec["events"]
    event = events[event_name]

    def inherited(property_name):
        return inherits(events, event_name, property_name)

    no_source_target = inherited("noSourceTarget") is True
    has_spell_prefix = inherited("hasSpellPrefix")
    if has_spell_prefix is None:
        has_spell_prefix = inherited("prefix") == "spell_prefix"
    non_standard_header = (
        event_name.startswith(("SPELL_", "RANGE_"))
        and not has_spell_prefix
        and not no_source_target
    )
    fields = []

    def add_field(offset, name, field_type, advanced=False, variant=0):
        fields.append((offset, name, field_type, advanced, variant))

    if not no_source_target and not non_standard_header:
        for field in spec["common_header"]["fields"][1:]:
            add_field(field["offset"], field["name"], field["type"])

    if has_spell_prefix:
        for field in spec["spell_prefix"]["fields"]:
            add_field(field["offset"], field["name"], field["type"])

    advanced_start = None
    if inherited("hasAdvancedBlock") is True:
        advanced_start = (
            9
            if event_name.startswith("SWING_") and not has_spell_prefix
            or event_name == "ENVIRONMENTAL_DAMAGE"
            else 12
        )
        for field in spec["advanced_logging_block"]["fields"]:
            add_field(
                advanced_start + field["offset"],
                field["name"],
                field["type"],
                advanced=True,
            )

    if event_name == "COMBATANT_INFO":
        combatant_fields = event["csvPortion"]
        for field in combatant_fields:
            offset = field["offset"]
            name = field["name"]
            if name == "crit_rating":
                for suffix, repeated_offset in (
                    ("melee", 11),
                    ("ranged", 12),
                    ("spell", 13),
                ):
                    add_field(repeated_offset, f"crit_rating_{suffix}", field["type"])
            elif name == "haste_rating":
                for suffix, repeated_offset in (
                    ("melee", 16),
                    ("ranged", 17),
                    ("spell", 18),
                ):
                    add_field(repeated_offset, f"haste_rating_{suffix}", field["type"])
            elif name == "versatility_rating":
                for suffix, repeated_offset in (
                    ("damage_done", 21),
                    ("healing_done", 22),
                    ("damage_taken", 23),
                ):
                    add_field(
                        repeated_offset,
                        f"versatility_{suffix}",
                        field["type"],
                    )
            else:
                add_field(offset, name, field["type"])
        bracket_start = max(field["offset"] for field in combatant_fields) + 1
        for field in event["bracketPortion"]:
            add_field(
                bracket_start + field["position"] - 1,
                field["name"],
                "bracket_value",
            )
        return fields, advanced_start, 0

    if event_name == "COMBAT_LOG_VERSION":
        for index, field in enumerate(spec["version_header"]["fields"]):
            add_field(index * 2 + 1, field["name"], field["type"])
        return fields, advanced_start, 0

    if event_name in ("SPELL_ABSORBED", "SPELL_ABSORBED_SUPPORT", "SPELL_HEAL_ABSORBED"):
        for variant, long_layout in ((1, True), (2, False)):
            if long_layout:
                block_names = (
                    ("attacker", UNIT_BLOCK_FIELDS, 1),
                    ("defender", UNIT_BLOCK_FIELDS, 5),
                    ("damage_spell", SPELL_BLOCK_FIELDS, 9),
                    ("absorber", UNIT_BLOCK_FIELDS, 12),
                    ("shield_spell", SPELL_BLOCK_FIELDS, 16),
                )
                amount_offset, total_offset, critical_offset = 19, 20, 21
                support_offset = 22
            else:
                block_names = (
                    ("attacker", UNIT_BLOCK_FIELDS, 1),
                    ("defender", UNIT_BLOCK_FIELDS, 5),
                    ("absorber", UNIT_BLOCK_FIELDS, 9),
                    ("shield_spell", SPELL_BLOCK_FIELDS, 13),
                )
                amount_offset, total_offset, critical_offset = 16, 17, 18
                support_offset = 19
            for block_name, members, start in block_names:
                for index, (member_name, field_type) in enumerate(members):
                    add_field(
                        start + index,
                        f"{block_name}_{member_name}",
                        field_type,
                        variant=variant,
                    )
            add_field(amount_offset, "amount", "integer", variant=variant)
            add_field(total_offset, "total_amount", "integer", variant=variant)
            add_field(critical_offset, "critical", "bool_or_nil", variant=variant)
            if event_name == "SPELL_ABSORBED_SUPPORT":
                add_field(support_offset, "support_player_guid", "string", variant=variant)
        advanced_min_fields = 0
        if advanced_start is not None:
            total_field_count = inherited("totalFieldCount")
            if total_field_count is not None:
                count = re.search(r"\d+", str(total_field_count))
                if count:
                    advanced_min_fields = int(count.group())
            if advanced_min_fields == 0:
                advanced_min_fields = max(offset for offset, *_ in fields) + 1
        return fields, advanced_start, advanced_min_fields

    suffix = inherited("suffix") or []
    for field in suffix:
        offsets = offset_range(field["offset"])
        field_type = field["type"]
        if field_type in ("unit_block", "spell_block"):
            members = UNIT_BLOCK_FIELDS if field_type == "unit_block" else SPELL_BLOCK_FIELDS
            for offset, (member_name, member_type) in zip(offsets, members):
                add_field(offset, f'{field["name"]}_{member_name}', member_type)
        else:
            add_field(offsets[0], field["name"], field_type)

    advanced_min_fields = 0
    if advanced_start is not None:
        total_field_count = inherited("totalFieldCount")
        if total_field_count is not None:
            count = re.search(r"\d+", str(total_field_count))
            if count:
                advanced_min_fields = int(count.group())
        if advanced_min_fields == 0:
            advanced_min_fields = max(offset for offset, *_ in fields) + 1
    return fields, advanced_start, advanced_min_fields


def render_event_fields(spec):
    lines = [
        "#[derive(Clone, Copy)]",
        "pub enum FieldType {",
    ]
    lines.extend(f"    {variant}," for variant in FIELD_TYPE_VARIANTS.values())
    lines.extend(
        [
            "}",
            "",
            "#[derive(Clone, Copy)]",
            "pub struct EventField {",
            "    pub offset: usize,",
            "    pub name: &'static str,",
            "    pub field_type: FieldType,",
            "    pub advanced: bool,",
            "    pub advanced_min_fields: usize,",
            "    pub advanced_unit: &'static str,",
            "    pub variant: u8,",
            "}",
            "",
            "pub const EVENT_FIELDS: &[EventField] = &[",
        ]
    )
    event_ranges = []
    start = 0
    for event_name in spec["events"]:
        fields, _, advanced_min_fields = event_fields(spec, event_name)
        advanced_unit = inherits(spec["events"], event_name, "advancedBlockDescribes") or ""
        event_ranges.append((event_name, start, start + len(fields)))
        for offset, name, field_type, advanced, variant in fields:
            lines.extend(
                [
                    "    EventField {",
                    f"        offset: {offset},",
                    f'        name: "{name}",',
                    f"        field_type: FieldType::{FIELD_TYPE_VARIANTS[field_type]},",
                    f"        advanced: {str(advanced).lower()},",
                    f"        advanced_min_fields: {advanced_min_fields},",
                    f'        advanced_unit: "{advanced_unit}",',
                    f"        variant: {variant},",
                    "    },",
                ]
            )
        start += len(fields)
    lines.extend(["];", ""])
    lines.extend(
        [
            "pub fn event_fields(event: &str) -> &'static [EventField] {",
            "    match event {",
        ]
    )
    for event_name, start, end in event_ranges:
        lines.append(
            f'        "{event_name}" => &EVENT_FIELDS[{start}..{end}],'
        )
    lines.extend(["        _ => &[],", "    }", "}", ""])
    return "\n".join(lines)


def render_rust(spec):
    events = spec["events"]
    names = list(events)
    spell_prefix_events = [
        name for name in names if inherits(events, name, "prefix") == "spell_prefix"
    ]
    no_source_target_events = [
        name for name in names if inherits(events, name, "noSourceTarget") is True
    ]
    non_standard_header_events = [
        name
        for name in names
        if name.startswith(("SPELL_", "RANGE_"))
        and name not in spell_prefix_events
        and not inherits(events, name, "noSourceTarget")
    ]

    lines = [
        "// Generated by scripts/generate_combat_log_artifacts.py; do not edit.",
        f"// Combat log spec format version {spec['format_version']}.",
        "",
    ]
    lines.extend(rust_array("SPEC_EVENTS", names, public=True))
    lines.extend(rust_array("SPEC_SPELL_PREFIX_EVENTS", spell_prefix_events, public=True))
    lines.extend(rust_array("NO_SOURCE_TARGET_EVENTS", no_source_target_events, public=True))
    lines.extend(
        rust_array("NON_STANDARD_HEADER_EVENTS", non_standard_header_events, public=True)
    )
    lines.append(render_event_fields(spec))
    return "\n".join(lines)


def render_event_schema(spec):
    event_names = list(spec["events"]) + list(ADDITIONAL_EVENTS)
    decoded_properties = {}
    json_types = {
        "integer": ["integer", "string", "null"],
        "long": ["integer", "string", "null"],
        "float": ["number", "string", "null"],
        "hex_uint32": ["integer", "string", "null"],
        "hex_bitfield": ["integer", "string", "null"],
        "bool_or_nil": ["boolean", "string", "null"],
        "boolean_int": ["boolean", "string", "null"],
        "integer_array": ["array", "string", "null"],
        "bracket_value": ["array", "string", "null"],
        "string": ["string", "null"],
        "quoted_string": ["string", "null"],
    }
    for event_name in spec["events"]:
        fields, _, _ = event_fields(spec, event_name)
        for _, name, field_type, _, _ in fields:
            decoded_properties[name] = {
                "type": json_types.get(field_type, ["string", "null"]),
                "description": f"Decoded {field_type} field from the combat-log specification.",
            }
    decoded_properties["advanced_block_unit"] = {
        "type": "string",
        "enum": ["source", "target"],
        "description": "Unit described by the optional advanced logging block.",
    }
    return json.dumps(
        {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "$id": "https://github.com/cagdasc/wow-telemetry-daemon/blob/main/daemon/resources/combat-log-event.schema.json",
            "title": "WoW Telemetry Combat Event",
            "description": (
                "WebSocket event emitted by the daemon. Raw fields preserve the "
                "CSV values as strings; nil values become null."
            ),
            "type": "object",
            "additionalProperties": False,
            "required": ["timestamp", "event", "fields"],
            "properties": {
                "timestamp": {"type": "string"},
                "event": {"type": "string", "enum": event_names},
                "fields": {
                    "type": "array",
                    "items": {"type": ["string", "null"]},
                    "description": (
                        "Ordered CSV payload after the event name. Values remain "
                        "strings regardless of their source type in the combat log."
                    ),
                },
                "decoded_fields": {
                    "type": "object",
                    "description": (
                        "Known event fields decoded by the YAML specification. "
                        "Fields not named by the spec remain losslessly available "
                        "in fields."
                    ),
                    "properties": decoded_properties,
                    "additionalProperties": {
                        "type": [
                            "string",
                            "integer",
                            "number",
                            "boolean",
                            "null",
                            "array",
                            "object",
                        ]
                    },
                },
                "source_guid": {"type": "string"},
                "source_name": {"type": "string"},
                "dest_guid": {"type": "string"},
                "dest_name": {"type": "string"},
                "spell_id": {"type": "string"},
                "spell_name": {"type": "string"},
                "amount": {"type": "string"},
            },
        },
        indent=2,
        ensure_ascii=False,
    ) + "\n"


def json_safe(value):
    if isinstance(value, (datetime.date, datetime.datetime)):
        return value.isoformat()
    if isinstance(value, dict):
        return {key: json_safe(item) for key, item in value.items()}
    if isinstance(value, list):
        return [json_safe(item) for item in value]
    return value


def artifacts(spec):
    return {
        RUST_PATH: render_rust(spec),
        JSON_SPEC_PATH: json.dumps(json_safe(spec), indent=2, ensure_ascii=False) + "\n",
        EVENT_SCHEMA_PATH: render_event_schema(spec),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail if generated files do not match the YAML specification",
    )
    args = parser.parse_args()

    spec = yaml.safe_load(SPEC_PATH.read_text(encoding="utf-8"))
    if not isinstance(spec.get("events"), dict):
        raise ValueError("specification must define an events mapping")

    failed = False
    for path, content in artifacts(spec).items():
        if args.check:
            if not path.exists() or path.read_text(encoding="utf-8") != content:
                print(f"out of date: {path.relative_to(ROOT)}", file=sys.stderr)
                failed = True
        else:
            path.write_text(content, encoding="utf-8")
            print(f"generated {path.relative_to(ROOT)}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
