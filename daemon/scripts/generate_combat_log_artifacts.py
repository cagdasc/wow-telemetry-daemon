#!/usr/bin/env python3
"""Generate Rust/Kotlin event models and JSON artifacts from combat-log-spec.yml."""

import argparse
import datetime
import json
import re
import subprocess
import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parent
SPEC_PATH = ROOT / "resources" / "combat-log-spec.yml"
RUST_PATH = ROOT / "src" / "spec_events.rs"
TYPED_RUST_PATH = ROOT / "src" / "typed_events.rs"
JSON_SPEC_PATH = ROOT / "resources" / "combat-log-spec.json"
EVENT_SCHEMA_PATH = ROOT / "resources" / "combat-log-event.schema.json"
KOTLIN_DOMAIN_PATH = WORKSPACE / "client/domain/src/commonMain/kotlin/com/cacaosd/wowtelemetry/domain/CombatLogEvent.kt"
KOTLIN_DECODER_PATH = WORKSPACE / "client/data/src/commonMain/kotlin/com/cacaosd/wowtelemetry/data/network/CombatLogEventDecoder.kt"
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
COMMON_HEADER_FIELDS = (
    "source_guid",
    "source_name",
    "source_flags",
    "source_raid_flags",
    "dest_guid",
    "dest_name",
    "dest_flags",
    "dest_raid_flags",
)
SPELL_PREFIX_FIELDS = ("spell_id", "spell_name", "spell_school")
KOTLIN_KEYWORDS = {
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
    "if", "in", "interface", "is", "null", "object", "package", "return",
    "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
    "var", "when", "while",
}


def advanced_field_names(spec):
    return tuple(
        field["name"] for field in spec["advanced_logging_block"]["fields"]
    )


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
    json_types = {
        "integer": ["integer", "null"],
        "long": ["integer", "null"],
        "float": ["number", "null"],
        "hex_uint32": ["integer", "null"],
        "hex_bitfield": ["integer", "null"],
        "bool_or_nil": ["boolean", "null"],
        "boolean_int": ["boolean", "null"],
        "integer_array": ["array", "object", "string", "number", "boolean", "null"],
        "bracket_value": ["array", "object", "string", "number", "boolean", "null"],
        "string": ["string", "null"],
        "quoted_string": ["string", "null"],
    }
    common_types = {
        field["name"]: field["type"]
        for field in spec["common_header"]["fields"][1:]
    }
    spell_types = {field["name"]: field["type"] for field in spec["spell_prefix"]["fields"]}
    advanced_types = {field["name"]: field["type"] for field in spec["advanced_logging_block"]["fields"]}

    def field_schema(name, field_type):
        types = ["string", "null"] if is_identifier_field(name) else json_types[field_type]
        if name in ("affixes", "pvp_talents"):
            return {
                "type": ["array", "null"],
                "items": {"type": ["integer", "null"]},
            }
        if name == "talents":
            return {
                "type": ["array", "null"],
                "items": {
                    "type": "array",
                    "items": {"type": ["integer", "null"]},
                },
            }
        if name == "gear":
            return {
                "type": ["array", "null"],
                "items": {
                    "type": "array",
                    "prefixItems": [
                        {"type": ["integer", "null"]},
                        {"type": ["integer", "null"]},
                        {"type": "array", "items": {"type": ["integer", "null"]}},
                        {"type": "array", "items": {"type": ["integer", "null"]}},
                        {"type": "array", "items": {"type": ["integer", "null"]}},
                    ],
                    "minItems": 5,
                    "maxItems": 5,
                },
            }
        if name == "active_auras":
            return {
                "type": ["array", "null"],
                "items": {"type": "array"},
            }
        return {"type": types}

    def object_schema(fields):
        return {
            "type": "object",
            "properties": {
                name: field_schema(name, field_type)
                for name, field_type in fields
            },
            "additionalProperties": False,
        }

    definitions = {
        "common_header": object_schema([
            (name, common_types[name]) for name in COMMON_HEADER_FIELDS
        ]),
        "spell_prefix": object_schema([
            (name, spell_types[name]) for name in SPELL_PREFIX_FIELDS
        ]),
        "advanced_block": object_schema(
            [("unit", "string")]
            + [
                (name, advanced_types[name])
                for name in advanced_field_names(spec)
            ]
        ),
    }

    event_variants = []
    for event_name in event_names:
        has_header, has_spell, advanced_fields, extra_fields = (
            event_model(spec, event_name)
            if event_name in spec["events"]
            else (False, False, [], [])
        )
        data_properties = {}
        if has_header:
            data_properties["header"] = {"$ref": "#/$defs/common_header"}
        if has_spell:
            data_properties["spell"] = {"$ref": "#/$defs/spell_prefix"}
        if advanced_fields:
            data_properties["advanced_block"] = {
                "oneOf": [
                    {"$ref": "#/$defs/advanced_block"},
                    {"type": "null"},
                ]
            }
        data_properties.update({
            field[1]: field_schema(field[1], field[2])
            for field in extra_fields
        })
        event_variants.append({
            "type": "object",
            "required": ["type", "data"],
            "properties": {
                "type": {"const": event_name},
                "data": {
                    "type": "object",
                    "properties": data_properties,
                    "additionalProperties": False,
                },
            },
            "additionalProperties": False,
        })
    event_variants.extend([
        {
            "type": "object",
            "required": ["type", "data"],
            "properties": {
                "type": {"const": "UNRECOGNIZED_EVENT"},
                "data": object_schema([("event_name", "string")]),
            },
            "additionalProperties": False,
        },
        {
            "type": "object",
            "required": ["type", "data"],
            "properties": {
                "type": {"const": "MALFORMED_EVENT"},
                "data": {
                    "type": "object",
                    "properties": {
                        "reason": {"type": "string"},
                        "raw_bytes": {
                            "type": "array",
                            "items": {"type": "integer", "minimum": 0, "maximum": 255},
                        },
                    },
                    "required": ["reason", "raw_bytes"],
                    "additionalProperties": False,
                },
            },
            "additionalProperties": False,
        },
    ])
    return json.dumps(
        {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "$id": "https://github.com/cagdasc/wow-telemetry-daemon/blob/main/daemon/resources/combat-log-event.schema.json",
            "title": "WoW Telemetry Combat Event",
            "description": (
                "WebSocket event emitted by the daemon. The event is a tagged, "
                "typed payload; fields preserves raw CSV values losslessly."
            ),
            "$defs": definitions,
            "type": "object",
            "additionalProperties": False,
            "required": ["timestamp", "event", "fields"],
            "properties": {
                "timestamp": {"type": "string"},
                "event": {"oneOf": event_variants},
                "fields": {
                    "type": "array",
                    "items": {"type": ["string", "null"]},
                    "description": (
                        "Ordered CSV payload after the event name. Values remain "
                        "strings regardless of their source type in the combat log."
                    ),
                },
            },
        },
        indent=2,
        ensure_ascii=False,
    ) + "\n"


def event_model(spec, event_name):
    events = spec["events"]
    fields, _, _ = event_fields(spec, event_name)
    no_source_target = inherits(events, event_name, "noSourceTarget") is True
    has_spell_prefix = inherits(events, event_name, "hasSpellPrefix")
    if has_spell_prefix is None:
        has_spell_prefix = inherits(events, event_name, "prefix") == "spell_prefix"
    non_standard_header = (
        event_name.startswith(("SPELL_", "RANGE_"))
        and not has_spell_prefix
        and not no_source_target
    )
    has_header = not no_source_target and not non_standard_header
    advanced_fields = []
    extra_fields = []
    seen_advanced = set()
    seen_extra = set()
    for field in fields:
        if field[3] and field[1] not in seen_advanced:
            advanced_fields.append(field)
            seen_advanced.add(field[1])
        elif (
            not field[3]
            and field[1] not in COMMON_HEADER_FIELDS
            and field[1] not in SPELL_PREFIX_FIELDS
            and field[1] not in seen_extra
        ):
            extra_fields.append(field)
            seen_extra.add(field[1])
    return has_header, has_spell_prefix, advanced_fields, extra_fields


def is_identifier_field(name):
    return name.endswith(("_id", "_guid")) or name in ("id", "guid")


def rust_field_type(name, field_type):
    if is_identifier_field(name):
        return "String"
    if name in ("affixes", "pvp_talents"):
        return "Vec<Option<i64>>"
    if name == "talents":
        return "Vec<TalentEntry>"
    if name == "gear":
        return "Vec<GearSlot>"
    if name == "active_auras":
        return "Vec<Vec<Value>>"
    return {
        "string": "String",
        "quoted_string": "String",
        "integer": "i64",
        "long": "i64",
        "float": "f64",
        "hex_uint32": "u32",
        "hex_bitfield": "u64",
        "bool_or_nil": "bool",
        "boolean_int": "bool",
        "integer_array": "Value",
        "bracket_value": "Value",
    }[field_type]


def rust_string_slice(values):
    return "&[" + ", ".join(f'"{value}"' for value in values) + "]"


def kotlin_field_type(name, field_type):
    if is_identifier_field(name):
        return "String?"
    if name in ("affixes", "pvp_talents"):
        return "List<Long?>?"
    if name == "talents":
        return "List<TalentEntry>?"
    if name == "gear":
        return "List<GearSlot>?"
    if name == "active_auras":
        return "List<List<JsonElement>>?"
    return {
        "string": "String?",
        "quoted_string": "String?",
        "integer": "Long?",
        "long": "Long?",
        "float": "Double?",
        "hex_uint32": "Long?",
        "hex_bitfield": "Long?",
        "bool_or_nil": "Boolean?",
        "boolean_int": "Boolean?",
        "integer_array": "JsonElement?",
        "bracket_value": "JsonElement?",
    }[field_type]


def camel_case(name):
    parts = name.split("_")
    result = parts[0] + "".join(part[:1].upper() + part[1:] for part in parts[1:])
    return f"`{result}`" if result in KOTLIN_KEYWORDS else result


def pascal_case(name):
    return "".join(part[:1].upper() + part[1:].lower() for part in name.split("_"))


def render_rust_typed_events(spec):
    names = list(spec["events"]) + list(ADDITIONAL_EVENTS)
    models = {
        name: event_model(spec, name) if name in spec["events"] else (
            False, False, [], [],
        )
        for name in names
    }
    lines = [
        "// Generated by scripts/generate_combat_log_artifacts.py; do not edit.",
        "use serde::{Deserialize, Serialize};",
        "use serde_json::{Map, Value, json};",
        "",
        "#[derive(Debug, Deserialize, Serialize)]",
        "pub struct CommonHeader {",
    ]
    common_types = {
        field["name"]: field["type"]
        for field in spec["common_header"]["fields"][1:]
    }
    for name in COMMON_HEADER_FIELDS:
        lines.append(f"    pub {name}: Option<{rust_field_type(name, common_types[name])}>,")
    lines.extend(["}", "", "#[derive(Debug, Deserialize, Serialize)]", "pub struct SpellPrefix {"])
    spell_types = {field["name"]: field["type"] for field in spec["spell_prefix"]["fields"]}
    for name in SPELL_PREFIX_FIELDS:
        lines.append(f"    pub {name}: Option<{rust_field_type(name, spell_types[name])}>,")
    lines.extend(["}", "", "#[derive(Debug, Deserialize, Serialize)]", "pub struct AdvancedLoggingBlock {"])
    advanced_types = {field["name"]: field["type"] for field in spec["advanced_logging_block"]["fields"]}
    lines.append("    pub unit: Option<String>,")
    for name in advanced_field_names(spec):
        lines.append(f"    pub {name}: Option<{rust_field_type(name, advanced_types[name])}>,")
    lines.extend(["}", ""])
    lines.extend([
        "#[derive(Debug, Deserialize, Serialize)]",
        "pub struct TalentEntry(",
        "    pub Option<i64>,",
        "    pub Option<i64>,",
        "    pub Option<i64>,",
        ");",
        "",
        "#[derive(Debug, Deserialize, Serialize)]",
        "pub struct GearSlot(",
        "    pub Option<i64>,",
        "    pub Option<i64>,",
        "    pub Vec<Option<i64>>,",
        "    pub Vec<Option<i64>>,",
        "    pub Vec<Option<i64>>,",
        ");",
        "",
    ])

    for name in names:
        has_header, has_spell, advanced_fields, extra_fields = models[name]
        lines.extend([
            "#[derive(Debug, Deserialize, Serialize)]",
            f"pub struct {pascal_case(name)}EventData {{",
        ])
        if has_header:
            lines.append("    pub header: CommonHeader,")
        if has_spell:
            lines.append("    pub spell: SpellPrefix,")
        if advanced_fields:
            lines.append("    pub advanced_block: Option<AdvancedLoggingBlock>,")
        for _, field_name, field_type, _, _ in extra_fields:
            lines.append(
                f"    pub {field_name}: Option<{rust_field_type(field_name, field_type)}>,"
            )
        lines.extend(["}", ""])
    lines.extend([
        "#[derive(Debug, Deserialize, Serialize)]",
        "pub struct UnrecognizedEventEventData {",
        "    pub event_name: String,",
        "}",
        "",
        "#[derive(Debug, Deserialize, Serialize)]",
        "pub struct MalformedEventEventData {",
        "    pub reason: String,",
        "    pub raw_bytes: Vec<u8>,",
        "}",
        "",
    ])
    lines.extend([
        "#[derive(Debug, Deserialize, Serialize)]",
        "#[serde(tag = \"type\", content = \"data\")]",
        "pub enum TypedCombatLogEvent {",
    ])
    for name in names:
        lines.append(
            f'    #[serde(rename = "{name}")] {pascal_case(name)}({pascal_case(name)}EventData),'
        )
    lines.extend([
        '    #[serde(rename = "UNRECOGNIZED_EVENT")] UnrecognizedEvent(UnrecognizedEventEventData),',
        '    #[serde(rename = "MALFORMED_EVENT")] MalformedEvent(MalformedEventEventData),',
    ])
    lines.extend(["}", "", "impl TypedCombatLogEvent {", "    pub fn event_name(&self) -> &'static str {", "        match self {"])
    for name in names:
        lines.append(
            f'            Self::{pascal_case(name)}(_) => "{name}",'
        )
    lines.extend([
        '            Self::UnrecognizedEvent(_) => "UNRECOGNIZED_EVENT",',
        '            Self::MalformedEvent(_) => "MALFORMED_EVENT",',
    ])
    lines.extend(["        }", "    }", "}", ""])
    lines.extend([
        "fn move_fields(source: &mut Map<String, Value>, target: &mut Map<String, Value>, names: &[&str]) {",
        "    for name in names {",
        "        if let Some(value) = source.remove(*name) {",
        "            target.insert((*name).to_owned(), value);",
        "        }",
        "    }",
        "}",
        "",
        "pub fn from_decoded(",
        "    event_name: &str,",
        "    mut decoded_fields: Map<String, Value>,",
        ") -> Result<TypedCombatLogEvent, serde_json::Error> {",
        "    let mut data = Map::new();",
        "    match event_name {",
    ])
    for name in names:
        has_header, has_spell, advanced_fields, extra_fields = models[name]
        lines.append(f'        "{name}" => {{')
        if has_header:
            lines.append("            let mut header = Map::new();")
            lines.append(
                f"            move_fields(&mut decoded_fields, &mut header, {rust_string_slice(COMMON_HEADER_FIELDS)});"
            )
            lines.append('            data.insert("header".to_owned(), Value::Object(header));')
        if has_spell:
            lines.append("            let mut spell = Map::new();")
            lines.append(
                f"            move_fields(&mut decoded_fields, &mut spell, {rust_string_slice(SPELL_PREFIX_FIELDS)});"
            )
            lines.append('            data.insert("spell".to_owned(), Value::Object(spell));')
        if advanced_fields:
            lines.append("            let mut advanced = Map::new();")
            extra_names = {field[1] for field in extra_fields}
            advanced_aliases = tuple(
                field[1] for field in advanced_fields if field[1] in extra_names
            )
            advanced_names = tuple(
                f"advanced_block_{field[1]}"
                if field[1] in advanced_aliases
                else field[1]
                for field in advanced_fields
            )
            lines.append(
                f"            move_fields(&mut decoded_fields, &mut advanced, {rust_string_slice(advanced_names)});"
            )
            for field_name in advanced_aliases:
                lines.append(
                    f'            if let Some(value) = advanced.remove("advanced_block_{field_name}") {{'
                )
                lines.append(
                    f'                advanced.insert("{field_name}".to_owned(), value);'
                )
                lines.append("            }")
            lines.append('            if let Some(unit) = decoded_fields.remove("advanced_block_unit") {')
            lines.append('                advanced.insert("unit".to_owned(), unit);')
            lines.append("            }")
            lines.append("            if !advanced.is_empty() {")
            lines.append('                data.insert("advanced_block".to_owned(), Value::Object(advanced));')
            lines.append("            }")
        extra_names = tuple(field[1] for field in extra_fields)
        if extra_names:
            lines.append(
                f"            move_fields(&mut decoded_fields, &mut data, {rust_string_slice(extra_names)});"
            )
        lines.extend(["        }"])
    lines.extend([
        '        _ => return Ok(TypedCombatLogEvent::UnrecognizedEvent(UnrecognizedEventEventData { event_name: event_name.to_owned() })),',
        "    }",
        "    serde_json::from_value(json!({",
        '        "type": event_name,',
        '        "data": Value::Object(data),',
        "    }))",
        "}",
        "",
    ])
    return "\n".join(lines)


def render_kotlin_domain_models(spec):
    names = list(spec["events"]) + list(ADDITIONAL_EVENTS)
    models = {
        name: event_model(spec, name) if name in spec["events"] else (
            False, False, [], [],
        )
        for name in names
    }
    lines = [
        "// Generated by scripts/generate_combat_log_artifacts.py; do not edit.",
        "package com.cacaosd.wowtelemetry.domain",
        "",
        "import kotlinx.serialization.json.JsonElement",
        "",
        "sealed interface CombatLogEvent {",
        "    val type: String",
        "}",
        "",
        "data class CommonHeader(",
    ]
    common_types = {
        field["name"]: field["type"]
        for field in spec["common_header"]["fields"][1:]
    }
    for name in COMMON_HEADER_FIELDS:
        lines.append(f"    val {camel_case(name)}: {kotlin_field_type(name, common_types[name])},")
    lines.extend([
        ")",
        "",
        "interface HasCommonHeader {",
        "    val header: CommonHeader",
        "}",
        "",
        "interface HasSpellPrefix {",
        "    val spell: SpellPrefix",
        "}",
        "",
        "interface HasBaseAmount {",
        "    val baseAmount: Long?",
        "}",
        "",
        "data class SpellPrefix(",
    ])
    spell_types = {field["name"]: field["type"] for field in spec["spell_prefix"]["fields"]}
    for name in SPELL_PREFIX_FIELDS:
        lines.append(f"    val {camel_case(name)}: {kotlin_field_type(name, spell_types[name])},")
    lines.extend([")", "", "data class AdvancedLoggingBlock("])
    lines.append("    val unit: String?,")
    advanced_types = {field["name"]: field["type"] for field in spec["advanced_logging_block"]["fields"]}
    for name in advanced_field_names(spec):
        lines.append(f"    val {camel_case(name)}: {kotlin_field_type(name, advanced_types[name])},")
    lines.extend([
        ")",
        "",
        "data class TalentEntry(",
        "    val nodeId: Long?,",
        "    val entryId: Long?,",
        "    val rank: Long?,",
        ")",
        "",
        "data class GearSlot(",
        "    val itemId: Long?,",
        "    val itemLevel: Long?,",
        "    val enchants: List<Long?>,",
        "    val bonusIds: List<Long?>,",
        "    val gems: List<Long?>,",
        ")",
        "",
    ])

    for name in names:
        has_header, has_spell, advanced_fields, extra_fields = models[name]
        class_name = f"{pascal_case(name)}Event"
        has_base_amount = any(field[1] == "base_amount" for field in extra_fields)
        interfaces = ["CombatLogEvent"]
        if has_header:
            interfaces.append("HasCommonHeader")
        if has_spell:
            interfaces.append("HasSpellPrefix")
        if has_base_amount:
            interfaces.append("HasBaseAmount")
        if not (has_header or has_spell or advanced_fields or extra_fields):
            lines.extend([
                f"data object {class_name} : {', '.join(interfaces)} {{",
                f'    override val type: String = "{name}"',
                "}",
                "",
            ])
            continue
        lines.append(f"data class {class_name}(")
        if has_header:
            lines.append("    override val header: CommonHeader,")
        if has_spell:
            lines.append("    override val spell: SpellPrefix,")
        if advanced_fields:
            lines.append("    val advancedBlock: AdvancedLoggingBlock?,")
        for _, field_name, field_type, _, _ in extra_fields:
            lines.append(
                f"    {'override ' if field_name == 'base_amount' else ''}val {camel_case(field_name)}: {kotlin_field_type(field_name, field_type)},"
            )
        if has_header or has_spell or advanced_fields or extra_fields:
            lines[-1] = lines[-1].removesuffix(",")
        lines.extend([
            f") : {', '.join(interfaces)} {{",
            f'    override val type: String = "{name}"',
            "}",
            "",
        ])
    lines.extend([
        "data class UnrecognizedEventEvent(",
        "    val eventName: String,",
        ") : CombatLogEvent {",
        '    override val type: String = "UNRECOGNIZED_EVENT"',
        "}",
        "",
        "data class MalformedEventEvent(",
        "    val reason: String,",
        "    val rawBytes: List<Int>,",
        ") : CombatLogEvent {",
        '    override val type: String = "MALFORMED_EVENT"',
        "}",
        "",
    ])
    return "\n".join(lines)


def kotlin_decoder_expression(name, field_type, receiver="data"):
    field = camel_case(name)
    if name in ("affixes", "pvp_talents"):
        return f'{receiver}.optionalLongList("{name}")'
    if name == "talents":
        return f'{receiver}.optionalTalentEntries("{name}")'
    if name == "gear":
        return f'{receiver}.optionalGearSlots("{name}")'
    if name == "active_auras":
        return f'{receiver}.optionalNestedJsonList("{name}")'
    if is_identifier_field(name) or field_type in ("string", "quoted_string"):
        return f'{receiver}.optionalString("{name}")'
    if field_type in ("integer", "long", "hex_uint32", "hex_bitfield"):
        return f'{receiver}.optionalLong("{name}")'
    if field_type == "float":
        return f'{receiver}.optionalDouble("{name}")'
    if field_type in ("bool_or_nil", "boolean_int"):
        return f'{receiver}.optionalBoolean("{name}")'
    return f'{receiver}.optionalJsonElement("{name}")'


def render_kotlin_decoder(spec):
    names = list(spec["events"]) + list(ADDITIONAL_EVENTS)
    models = {
        name: event_model(spec, name) if name in spec["events"] else (
            False, False, [], [],
        )
        for name in names
    }
    lines = [
        "// Generated by scripts/generate_combat_log_artifacts.py; do not edit.",
        "package com.cacaosd.wowtelemetry.data.network",
        "",
        "import com.cacaosd.wowtelemetry.domain.*",
        "import kotlinx.serialization.json.JsonElement",
        "import kotlinx.serialization.json.JsonArray",
        "import kotlinx.serialization.json.JsonNull",
        "import kotlinx.serialization.json.JsonObject",
        "import kotlinx.serialization.json.JsonPrimitive",
        "import kotlinx.serialization.json.booleanOrNull",
        "import kotlinx.serialization.json.doubleOrNull",
        "import kotlinx.serialization.json.intOrNull",
        "import kotlinx.serialization.json.longOrNull",
        "",
        "internal fun decodeCombatLogEvent(type: String, data: JsonObject): CombatLogEvent = when (type) {",
    ]
    for name in names:
        has_header, has_spell, advanced_fields, extra_fields = models[name]
        args = []
        if has_header:
            args.append('header = data.requiredObject("header").decodeHeader()')
        if has_spell:
            args.append('spell = data.requiredObject("spell").decodeSpellPrefix()')
        if advanced_fields:
            args.append('advancedBlock = data.optionalObject("advanced_block")?.decodeAdvancedBlock()')
        for _, field_name, field_type, _, _ in extra_fields:
            args.append(f"{camel_case(field_name)} = {kotlin_decoder_expression(field_name, field_type)}")
        if args:
            lines.append(f'    "{name}" -> {pascal_case(name)}Event(')
            lines.extend(f"        {argument}," for argument in args)
            lines.append("    )")
        else:
            lines.append(f'    "{name}" -> {pascal_case(name)}Event')
    lines.extend([
        '    "UNRECOGNIZED_EVENT" -> UnrecognizedEventEvent(data.requiredString("event_name"))',
        '    "MALFORMED_EVENT" -> MalformedEventEvent(',
        '        reason = data.requiredString("reason"),',
        '        rawBytes = data.requiredIntList("raw_bytes"),',
        "    )",
    ])
    lines.extend([
        '    else -> throw CombatEventDecodeException("Unknown event type: $type")',
        "}",
        "",
        "private fun JsonObject.decodeHeader() = CommonHeader(",
    ])
    common_types = {
        field["name"]: field["type"]
        for field in spec["common_header"]["fields"][1:]
    }
    for name in COMMON_HEADER_FIELDS:
        lines.append(
            f'    {camel_case(name)} = {kotlin_decoder_expression(name, common_types[name], "this")},'
        )
    lines.extend([")", "", "private fun JsonObject.decodeSpellPrefix() = SpellPrefix("])
    spell_types = {field["name"]: field["type"] for field in spec["spell_prefix"]["fields"]}
    for name in SPELL_PREFIX_FIELDS:
        lines.append(
            f'    {camel_case(name)} = {kotlin_decoder_expression(name, spell_types[name], "this")},'
        )
    lines.extend([")", "", "private fun JsonObject.decodeAdvancedBlock() = AdvancedLoggingBlock("])
    lines.append('    unit = optionalString("unit"),')
    advanced_types = {field["name"]: field["type"] for field in spec["advanced_logging_block"]["fields"]}
    for name in advanced_field_names(spec):
        lines.append(
            f'    {camel_case(name)} = {kotlin_decoder_expression(name, advanced_types[name], "this")},'
        )
    lines.extend([
        ")",
        "",
        'private fun JsonObject.requiredString(name: String): String =',
        "    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content",
        '        ?: throw CombatEventDecodeException("\'$name\' must be a string")',
        "",
        'private fun JsonObject.requiredObject(name: String): JsonObject =',
        "    this[name] as? JsonObject",
        "        ?: throw CombatEventDecodeException(\"Event data must include object '$name'\")",
        "",
        'private fun JsonObject.optionalObject(name: String): JsonObject? = when (val value = this[name]) {',
        "    null, JsonNull -> null",
        "    is JsonObject -> value",
        "    else -> throw CombatEventDecodeException(\"'$name' must be an object or null\")",
        "}",
        "",
        'private fun JsonObject.optionalString(name: String): String? = when (val value = this[name]) {',
        "    null, JsonNull -> null",
        "    is JsonPrimitive -> if (value.isString) value.content else throw CombatEventDecodeException(\"'$name' must be a string or null\")",
        "    else -> throw CombatEventDecodeException(\"'$name' must be a string or null\")",
        "}",
        "",
        'private fun JsonObject.optionalLong(name: String): Long? = when (val value = this[name]) {',
        "    null, JsonNull -> null",
        "    is JsonPrimitive -> if (!value.isString) value.longOrNull ?: throw CombatEventDecodeException(\"'$name' must be an integer or null\") else throw CombatEventDecodeException(\"'$name' must be an integer or null\")",
        "    else -> throw CombatEventDecodeException(\"'$name' must be an integer or null\")",
        "}",
        "",
        'private fun JsonObject.optionalDouble(name: String): Double? = when (val value = this[name]) {',
        "    null, JsonNull -> null",
        "    is JsonPrimitive -> if (!value.isString) value.doubleOrNull ?: throw CombatEventDecodeException(\"'$name' must be a number or null\") else throw CombatEventDecodeException(\"'$name' must be a number or null\")",
        "    else -> throw CombatEventDecodeException(\"'$name' must be a number or null\")",
        "}",
        "",
        'private fun JsonObject.optionalBoolean(name: String): Boolean? = when (val value = this[name]) {',
        "    null, JsonNull -> null",
        "    is JsonPrimitive -> if (!value.isString) value.booleanOrNull ?: throw CombatEventDecodeException(\"'$name' must be a boolean or null\") else throw CombatEventDecodeException(\"'$name' must be a boolean or null\")",
        "    else -> throw CombatEventDecodeException(\"'$name' must be a boolean or null\")",
        "}",
        "",
        'private fun JsonObject.optionalJsonElement(name: String): JsonElement? =',
        "    this[name]?.takeUnless { it == JsonNull }",
        "",
        'private fun JsonObject.requiredIntList(name: String): List<Int> =',
        "    (this[name] as? JsonArray)?.mapIndexed { index, value ->",
        '        val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull',
        '            ?: throw CombatEventDecodeException("\'$name[$index]\' must be an integer")',
        '        if (number !in 0..255) throw CombatEventDecodeException("\'$name[$index]\' must be a byte value")',
        "        number",
        '    } ?: throw CombatEventDecodeException("\'$name\' must be an array")',
        "",
        'private fun JsonObject.optionalLongList(name: String): List<Long?>? = when (val value = this[name]) {',
        "    null, JsonNull -> null",
        '    is JsonArray -> value.longItems(name)',
        "    else -> throw CombatEventDecodeException(\"'$name' must be an array or null\")",
        "}",
        "",
        'private fun JsonObject.optionalTalentEntries(name: String): List<TalentEntry>? = when (val value = this[name]) {',
        "    null, JsonNull -> null",
        "    is JsonArray -> value.mapIndexed { index, entry ->",
        "        val fields = entry as? JsonArray ?: throw CombatEventDecodeException(\"'$name[$index]' must be an array\")",
        "        if (fields.size != 3) throw CombatEventDecodeException(\"'$name[$index]' must contain 3 values\")",
        '        TalentEntry(fields[0].longItemOrNull("$name[$index][0]"), fields[1].longItemOrNull("$name[$index][1]"), fields[2].longItemOrNull("$name[$index][2]"))',
        "    }",
        "    else -> throw CombatEventDecodeException(\"'$name' must be an array or null\")",
        "}",
        "",
        'private fun JsonObject.optionalGearSlots(name: String): List<GearSlot>? = when (val value = this[name]) {',
        "    null, JsonNull -> null",
        "    is JsonArray -> value.mapIndexed { index, slot ->",
        "        val fields = slot as? JsonArray ?: throw CombatEventDecodeException(\"'$name[$index]' must be an array\")",
        "        if (fields.size != 5) throw CombatEventDecodeException(\"'$name[$index]' must contain 5 values\")",
        '        GearSlot(fields[0].longItemOrNull("$name[$index][0]"), fields[1].longItemOrNull("$name[$index][1]"), fields[2].longArray("$name[$index][2]"), fields[3].longArray("$name[$index][3]"), fields[4].longArray("$name[$index][4]"))',
        "    }",
        "    else -> throw CombatEventDecodeException(\"'$name' must be an array or null\")",
        "}",
        "",
        'private fun JsonObject.optionalNestedJsonList(name: String): List<List<JsonElement>>? = when (val value = this[name]) {',
        "    null, JsonNull -> null",
        "    is JsonArray -> value.mapIndexed { index, item ->",
        "        item as? JsonArray ?: throw CombatEventDecodeException(\"'$name[$index]' must be an array\")",
        "    }",
        "    else -> throw CombatEventDecodeException(\"'$name' must be an array or null\")",
        "}",
        "",
        'private fun JsonArray.longItems(field: String): List<Long?> =',
        '    mapIndexed { index, value -> value.longItemOrNull("$field[$index]") }',
        "",
        'private fun JsonElement.longArray(field: String): List<Long?> =',
        "    (this as? JsonArray)?.longItems(field)",
        "        ?: throw CombatEventDecodeException(\"'$field' must be an array\")",
        "",
        'private fun JsonElement.longItemOrNull(field: String): Long? = when (this) {',
        "    JsonNull -> null",
        "    is JsonPrimitive -> if (!isString) longOrNull ?: throw CombatEventDecodeException(\"'$field' must be an integer or null\") else throw CombatEventDecodeException(\"'$field' must be an integer or null\")",
        "    else -> throw CombatEventDecodeException(\"'$field' must be an integer or null\")",
        "}",
        "",
    ])
    return "\n".join(lines)


def json_safe(value):
    if isinstance(value, (datetime.date, datetime.datetime)):
        return value.isoformat()
    if isinstance(value, dict):
        return {key: json_safe(item) for key, item in value.items()}
    if isinstance(value, list):
        return [json_safe(item) for item in value]
    return value


def format_rust(source):
    result = subprocess.run(
        ["rustfmt", "--edition", "2024", "--emit", "stdout"],
        input=source,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode:
        raise RuntimeError(f"rustfmt failed: {result.stderr.strip()}")
    return result.stdout


def artifacts(spec):
    return {
        RUST_PATH: render_rust(spec),
        TYPED_RUST_PATH: format_rust(render_rust_typed_events(spec)),
        JSON_SPEC_PATH: json.dumps(json_safe(spec), indent=2, ensure_ascii=False) + "\n",
        EVENT_SCHEMA_PATH: render_event_schema(spec),
        KOTLIN_DOMAIN_PATH: render_kotlin_domain_models(spec),
        KOTLIN_DECODER_PATH: render_kotlin_decoder(spec),
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
                print(f"out of date: {path.relative_to(WORKSPACE)}", file=sys.stderr)
                failed = True
        else:
            path.write_text(content, encoding="utf-8")
            print(f"generated {path.relative_to(WORKSPACE)}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
