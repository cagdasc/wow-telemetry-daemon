use std::borrow::Cow;
use std::str;

use crate::spec_events::{FieldType, event_fields as spec_event_fields, is_spec_event};
use crate::typed_events::{
    MalformedEventEventData, TypedCombatLogEvent, UnrecognizedEventEventData, from_decoded,
};
use memchr::{memchr, memchr2};
use serde::Serialize;
use serde_json::{Map, Number, Value};

#[derive(Debug, Serialize)]
pub struct CombatEvent<'a> {
    pub timestamp: Cow<'a, str>,
    pub fields: Vec<Option<Cow<'a, str>>>,
    #[serde(rename = "event")]
    pub typed_event: TypedCombatLogEvent,
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum ParseError {
    #[error("missing timestamp/event separator")]
    MissingSeparator,
    #[error("timestamp is not valid UTF-8")]
    InvalidTimestampUtf8,
    #[error("event name is not valid UTF-8")]
    InvalidEventNameUtf8,
    #[error("missing event name")]
    MissingEventName,
    #[error("combatant field nesting is too deep")]
    NestingTooDeep,
    #[error("unbalanced combatant field brackets")]
    UnbalancedBrackets,
    #[error("unterminated quoted field")]
    UnterminatedQuotedField,
    #[error("unterminated combatant field brackets")]
    UnterminatedBrackets,
    #[error("field is not valid UTF-8")]
    InvalidFieldUtf8,
    #[error("could not decode typed event: {0}")]
    TypedEventDecode(String),
}

impl From<serde_json::Error> for ParseError {
    fn from(error: serde_json::Error) -> Self {
        Self::TypedEventDecode(error.to_string())
    }
}

struct Fields<'a> {
    bytes: &'a [u8],
    position: usize,
    bracket_aware: bool,
}

impl<'a> Iterator for Fields<'a> {
    type Item = Result<Cow<'a, str>, ParseError>;

    fn next(&mut self) -> Option<Self::Item> {
        if self.position > self.bytes.len() {
            return None;
        }

        let start = self.position;
        let mut position = start;
        let mut quoted = false;
        let mut nesting = [0_u8; 16];
        let mut nesting_depth = 0;
        let end = loop {
            let remaining = &self.bytes[position..];
            let next_delimiter = if self.bracket_aware {
                match (
                    memchr2(b',', b'"', remaining),
                    memchr2(b'[', b'(', remaining),
                    memchr2(b']', b')', remaining),
                ) {
                    (Some(a), Some(b), Some(c)) => Some(a.min(b).min(c)),
                    (Some(a), Some(b), None)
                    | (Some(a), None, Some(b))
                    | (None, Some(a), Some(b)) => Some(a.min(b)),
                    (Some(a), None, None) | (None, Some(a), None) | (None, None, Some(a)) => {
                        Some(a)
                    }
                    (None, None, None) => None,
                }
            } else {
                memchr2(b',', b'"', remaining)
            };
            match next_delimiter {
                Some(offset) => {
                    position += offset;
                    match self.bytes[position] {
                        b'"' if quoted && self.bytes.get(position + 1) == Some(&b'"') => {
                            position += 2;
                        }
                        b'"' => {
                            quoted = !quoted;
                            position += 1;
                        }
                        b'[' | b'(' if !quoted => {
                            if self.bracket_aware {
                                let close = if self.bytes[position] == b'[' {
                                    b']'
                                } else {
                                    b')'
                                };
                                let Some(slot) = nesting.get_mut(nesting_depth) else {
                                    self.position = self.bytes.len() + 1;
                                    return Some(Err(ParseError::NestingTooDeep));
                                };
                                *slot = close;
                                nesting_depth += 1;
                            }
                            position += 1;
                        }
                        b']' | b')' if !quoted => {
                            if self.bracket_aware
                                && (nesting_depth == 0
                                    || nesting[nesting_depth - 1] != self.bytes[position])
                            {
                                self.position = self.bytes.len() + 1;
                                return Some(Err(ParseError::UnbalancedBrackets));
                            }
                            if self.bracket_aware {
                                nesting_depth -= 1;
                            }
                            position += 1;
                        }
                        b',' if !quoted && nesting_depth == 0 => break position,
                        b',' => position += 1,
                        _ => unreachable!(),
                    }
                }
                None => break self.bytes.len(),
            }
        };
        if quoted {
            self.position = self.bytes.len() + 1;
            return Some(Err(ParseError::UnterminatedQuotedField));
        }
        if nesting_depth != 0 {
            self.position = self.bytes.len() + 1;
            return Some(Err(ParseError::UnterminatedBrackets));
        }
        self.position = if end == self.bytes.len() {
            self.bytes.len() + 1
        } else {
            end + 1
        };

        let mut value = &self.bytes[start..end];
        if value.len() >= 2 && value[0] == b'"' && value[value.len() - 1] == b'"' {
            value = &value[1..value.len() - 1];
        }
        let value = match str::from_utf8(value) {
            Ok(v) => v,
            Err(_) => {
                self.position = self.bytes.len() + 1;
                return Some(Err(ParseError::InvalidFieldUtf8));
            }
        };
        if value.contains("\"\"") {
            Some(Ok(Cow::Owned(value.replace("\"\"", "\""))))
        } else {
            Some(Ok(Cow::Borrowed(value)))
        }
    }
}

fn present(value: Cow<'_, str>) -> Option<Cow<'_, str>> {
    (value != "nil").then_some(value)
}

fn is_recognized_event(event: &str) -> bool {
    is_spec_event(event) || matches!(event, "PLAYER_REGEN_DISABLED" | "PLAYER_REGEN_ENABLED")
}

fn skip_whitespace(bytes: &[u8], position: &mut usize) {
    while bytes.get(*position).is_some_and(u8::is_ascii_whitespace) {
        *position += 1;
    }
}

fn parse_nested_value_at(bytes: &[u8], position: &mut usize) -> Option<Value> {
    skip_whitespace(bytes, position);
    let first = *bytes.get(*position)?;
    if first == b'[' || first == b'(' {
        let close = if first == b'[' { b']' } else { b')' };
        *position += 1;
        let mut values = Vec::new();
        loop {
            skip_whitespace(bytes, position);
            if bytes.get(*position) == Some(&close) {
                *position += 1;
                return Some(Value::Array(values));
            }
            values.push(parse_nested_value_at(bytes, position)?);
            skip_whitespace(bytes, position);
            match bytes.get(*position) {
                Some(b',') => *position += 1,
                Some(byte) if *byte == close => {
                    *position += 1;
                    return Some(Value::Array(values));
                }
                _ => return None,
            }
        }
    }
    if first == b'"' {
        *position += 1;
        let mut value = Vec::new();
        while let Some(byte) = bytes.get(*position) {
            *position += 1;
            match byte {
                b'"' if bytes.get(*position) == Some(&b'"') => {
                    value.push(b'"');
                    *position += 1;
                }
                b'"' => return Some(Value::String(str::from_utf8(&value).ok()?.to_owned())),
                byte => value.push(*byte),
            }
        }
        return None;
    }
    let start = *position;
    while let Some(byte) = bytes.get(*position) {
        if matches!(byte, b',' | b']' | b')') {
            break;
        }
        *position += 1;
    }
    let value = str::from_utf8(&bytes[start..*position]).ok()?.trim();
    if value.is_empty() || value == "nil" {
        return Some(Value::Null);
    }
    if let Ok(value) = value.parse::<i64>() {
        return Some(Value::Number(Number::from(value)));
    }
    if let Some(value) = value.parse::<f64>().ok().and_then(Number::from_f64) {
        return Some(Value::Number(value));
    }
    Some(Value::String(value.to_owned()))
}

fn parse_nested_value(value: &str) -> Option<Value> {
    let bytes = value.as_bytes();
    let mut position = 0;
    let parsed = parse_nested_value_at(bytes, &mut position)?;
    skip_whitespace(bytes, &mut position);
    (position == bytes.len()).then_some(parsed)
}

fn parse_spec_value(value: Option<&str>, field_type: FieldType) -> Value {
    let Some(value) = value else {
        return Value::Null;
    };
    match field_type {
        FieldType::Integer | FieldType::Long => value
            .parse::<i64>()
            .map(Number::from)
            .map(Value::Number)
            .unwrap_or_else(|_| Value::String(value.to_owned())),
        FieldType::Float => value
            .parse::<f64>()
            .ok()
            .and_then(Number::from_f64)
            .map(Value::Number)
            .unwrap_or_else(|| Value::String(value.to_owned())),
        FieldType::HexUint32 | FieldType::HexBitfield => {
            let digits = value.trim_start_matches("0x").trim_start_matches("0X");
            let parsed = if matches!(field_type, FieldType::HexUint32) {
                u32::from_str_radix(digits, 16).map(u64::from)
            } else {
                u64::from_str_radix(digits, 16)
            };
            parsed
                .map(Number::from)
                .map(Value::Number)
                .unwrap_or_else(|_| Value::String(value.to_owned()))
        }
        FieldType::BoolOrNil | FieldType::BooleanInt => match value {
            "0" | "false" | "False" => Value::Bool(false),
            "1" | "true" | "True" => Value::Bool(true),
            _ => Value::String(value.to_owned()),
        },
        FieldType::IntegerArray => {
            let value = value.trim();
            if value.starts_with('[') {
                parse_nested_value(value).unwrap_or_else(|| Value::String(value.to_owned()))
            } else {
                parse_nested_value(&format!("[{value}]"))
                    .unwrap_or_else(|| Value::String(value.to_owned()))
            }
        }
        FieldType::BracketValue => {
            parse_nested_value(value).unwrap_or_else(|| Value::String(value.to_owned()))
        }
        _ => Value::String(value.to_owned()),
    }
}

fn normalize_active_auras(value: Value) -> Value {
    let Value::Array(values) = value else {
        return value;
    };
    if values.len() % 3 != 0 || values.iter().any(Value::is_array) {
        return Value::Array(values);
    }

    // Flat aura entries contain a source GUID, spell ID, and stack count.
    Value::Array(
        values
            .chunks(3)
            .map(|aura| Value::Array(aura.to_vec()))
            .collect(),
    )
}

fn parsed_fields(event: &str, fields: &[Option<Cow<'_, str>>]) -> Map<String, Value> {
    let total_field_count = fields.len() + 1;
    let layout = spec_event_fields(event);
    let mut advanced_start = None;
    let mut advanced_min_fields = usize::MAX;
    let mut advanced_unit = None;

    for field in layout {
        if field.advanced && advanced_start.is_none() {
            advanced_start = Some(field.offset);
            advanced_min_fields = field.advanced_min_fields;
            if !field.advanced_unit.is_empty() {
                advanced_unit = Some(field.advanced_unit);
            }
        }
    }

    let advanced = advanced_start.is_some_and(|_| total_field_count >= advanced_min_fields);
    let variant = match event {
        "SPELL_ABSORBED" | "SPELL_ABSORBED_SUPPORT" | "SPELL_HEAL_ABSORBED" => {
            if total_field_count >= 22 { 1 } else { 2 }
        }
        _ => 0,
    };

    let mut decoded = Map::with_capacity(layout.len() + usize::from(advanced));
    if advanced && let Some(unit) = advanced_unit {
        decoded.insert(
            "advanced_block_unit".to_owned(),
            Value::String(unit.to_owned()),
        );
    }

    for spec_field in layout {
        if spec_field.variant != variant || (spec_field.advanced && !advanced) {
            continue;
        }
        let mut offset = spec_field.offset;
        if !advanced && advanced_start.is_some_and(|start| offset >= start + 19) {
            offset -= 19;
        }
        let Some(value) = fields.get(offset.saturating_sub(1)) else {
            continue;
        };
        let field_name = spec_field.target_name.to_owned();
        let Some(value) = value.as_deref() else {
            decoded.insert(field_name, Value::Null);
            continue;
        };
        let parsed = if is_identifier_field(spec_field.name) {
            Value::String(value.to_owned())
        } else {
            parse_spec_value(Some(value), spec_field.field_type)
        };
        let parsed = if spec_field.name == "active_auras" {
            normalize_active_auras(parsed)
        } else {
            parsed
        };
        decoded.insert(field_name, parsed);
    }
    decoded
}

fn is_identifier_field(name: &str) -> bool {
    name == "id" || name == "guid" || name.ends_with("_id") || name.ends_with("_guid")
}

pub fn parse_line(line: &[u8]) -> CombatEvent<'_> {
    match parse_line_inner(line) {
        Ok(event) => event,
        Err(error) => malformed_event(line, error),
    }
}

fn find_separator(line: &[u8]) -> Result<(usize, usize), ParseError> {
    let mut offset = 0;
    loop {
        let Some(index) = memchr2(b' ', b'\t', &line[offset..]) else {
            return Err(ParseError::MissingSeparator);
        };
        let index = offset + index;
        if line[index] == b'\t' {
            return Ok((index, index + 1));
        }
        if line.get(index + 1) == Some(&b' ') {
            let mut end = index + 2;
            while line.get(end) == Some(&b' ') {
                end += 1;
            }
            return Ok((index, end));
        }
        offset = index + 1;
    }
}

fn timestamp_from_malformed(line: &[u8]) -> Cow<'_, str> {
    let Ok((separator, _)) = find_separator(line) else {
        return Cow::Borrowed("");
    };
    str::from_utf8(&line[..separator])
        .map(Cow::Borrowed)
        .unwrap_or_else(|_| Cow::Owned(String::from_utf8_lossy(&line[..separator]).into_owned()))
}

fn malformed_event(line: &[u8], error: ParseError) -> CombatEvent<'_> {
    CombatEvent {
        timestamp: timestamp_from_malformed(line),
        fields: Vec::new(),
        typed_event: TypedCombatLogEvent::MalformedEvent(MalformedEventEventData {
            reason: error.to_string(),
            raw_bytes: line.to_vec(),
        }),
    }
}

fn parse_line_inner(line: &[u8]) -> Result<CombatEvent<'_>, ParseError> {
    let (separator, separator_end) = find_separator(line)?;
    let timestamp =
        str::from_utf8(&line[..separator]).map_err(|_| ParseError::InvalidTimestampUtf8)?;
    let header = &line[separator_end..];
    let event_end = memchr(b',', header).unwrap_or(header.len());
    let event_name = &header[..event_end];
    let event = str::from_utf8(event_name).map_err(|_| ParseError::InvalidEventNameUtf8)?;
    if event.is_empty() {
        return Err(ParseError::MissingEventName);
    }
    let payload = &header[event_end + usize::from(event_end < header.len())..];
    let csv = Fields {
        bytes: payload,
        position: 0,
        bracket_aware: event == "COMBATANT_INFO",
    };
    let mut fields = Vec::new();
    if event_end < header.len() {
        for value in csv {
            fields.push(present(value?));
        }
    }

    let recognized = is_recognized_event(event);
    let typed_event = if recognized {
        let decoded_fields = parsed_fields(event, &fields);
        from_decoded(event, decoded_fields)?
    } else {
        TypedCombatLogEvent::UnrecognizedEvent(UnrecognizedEventEventData {
            event_name: event.to_owned(),
        })
    };

    Ok(CombatEvent {
        timestamp: Cow::Borrowed(timestamp),
        fields,
        typed_event,
    })
}

#[cfg(test)]
mod tests {
    use super::{CombatEvent, parse_line};
    use crate::spec_events::SPEC_EVENTS;
    use serde_json::Value;

    fn to_hex(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02x}")).collect()
    }

    fn event_data(event: &CombatEvent<'_>) -> Value {
        serde_json::to_value(&event.typed_event).unwrap()["data"].clone()
    }

    fn event_name(event: &CombatEvent<'_>) -> &'static str {
        event.typed_event.event_name()
    }

    fn amount(line: &str) -> Option<f64> {
        let data = event_data(&parse_line(line.as_bytes()));
        data["base_amount"]
            .as_f64()
            .or_else(|| data["amount"].as_f64())
    }

    #[test]
    fn parses_spell_damage_with_quoted_commas_and_escaped_quotes() {
        let line = br#"10/28 15:04:05.123  SPELL_DAMAGE,Player-1,"Mage, ""The Great""",0x511,0,Creature-2,"Target",0x10,0,1234,"Fireball",4,250,0"#;
        let event = parse_line(line);

        assert_eq!(event_name(&event), "SPELL_DAMAGE");
        assert_eq!(event.timestamp, "10/28 15:04:05.123");
        assert_eq!(event.fields[1].as_deref(), Some("Mage, \"The Great\""));
        let payload = serde_json::to_value(&event).unwrap();
        assert_eq!(payload["event"]["type"], "SPELL_DAMAGE");
        assert_eq!(payload["event"]["data"]["spell"]["spell_id"], "1234");
        assert_eq!(payload["event"]["data"]["base_amount"], 250);
        assert_eq!(payload["fields"][1], "Mage, \"The Great\"");
        assert!(payload.get("decoded_fields").is_none());
    }

    #[test]
    fn parses_combat_state_and_unrecognized_events() {
        let event = parse_line(b"10/28 15:04:05.123  PLAYER_REGEN_DISABLED");
        assert_eq!(event_name(&event), "PLAYER_REGEN_DISABLED");
        assert_eq!(
            event_name(&parse_line(b"10/28 15:04:05.123  SPELL_HEAL")),
            "SPELL_HEAL"
        );
        let unknown = parse_line(b"10/28 15:04:05.123  SPELL_NOT_IN_SPEC,Player-1,Mage,nil");
        let payload = serde_json::to_value(&unknown).unwrap();
        assert_eq!(payload["event"]["type"], "UNRECOGNIZED_EVENT");
        assert_eq!(payload["event"]["data"]["event_name"], "SPELL_NOT_IN_SPEC");
        assert_eq!(
            payload["fields"],
            serde_json::json!(["Player-1", "Mage", null])
        );
    }

    #[test]
    fn publishes_malformed_lines_with_reason_and_original_bytes() {
        let line = b"10/28 15:04:05.123  SPELL_DAMAGE,\"unterminated";
        let event = parse_line(line);
        let payload = serde_json::to_value(&event).unwrap();

        assert_eq!(payload["event"]["type"], "MALFORMED_EVENT");
        assert_eq!(
            payload["event"]["data"]["reason"],
            "unterminated quoted field"
        );
        assert_eq!(payload["event"]["data"]["raw_bytes"], to_hex(line));
    }

    #[test]
    fn publishes_invalid_utf8_lines_with_original_bytes() {
        let line = b"10/28 15:04:05.123  SPELL_DAMAGE,\xff";
        let event = parse_line(line);
        let payload = serde_json::to_value(&event).unwrap();

        assert_eq!(payload["event"]["type"], "MALFORMED_EVENT");
        assert_eq!(
            payload["event"]["data"]["reason"],
            "field is not valid UTF-8"
        );
        assert_eq!(payload["event"]["data"]["raw_bytes"], to_hex(line));
    }

    #[test]
    fn publishes_known_events_with_invalid_typed_fields_as_malformed() {
        let line = b"10/28 15:04:05.123  COMBAT_LOG_VERSION,not-a-number,ADVANCED_LOG_ENABLED,1,BUILD_VERSION,12.0.0,PROJECT_ID,1";
        let event = parse_line(line);
        let payload = serde_json::to_value(&event).unwrap();

        assert_eq!(payload["event"]["type"], "MALFORMED_EVENT");
        assert!(
            payload["event"]["data"]["reason"]
                .as_str()
                .unwrap()
                .contains("could not decode typed event")
        );
        assert_eq!(payload["event"]["data"]["raw_bytes"], to_hex(line));
    }

    #[test]
    fn parses_swing_damage_and_spell_cast_success() {
        let swing = parse_line(
            b"10/28 15:04:05.123  SWING_DAMAGE,Player-1,Mage,0x511,0,Creature-2,Target,0x10,0,500,0",
        );
        assert_eq!(event_name(&swing), "SWING_DAMAGE");
        assert_eq!(event_data(&swing)["base_amount"], 500);
        assert!(event_data(&swing).get("spell").is_none());

        let cast = parse_line(
            b"10/28 15:04:05.123  SPELL_CAST_SUCCESS,Player-1,Mage,0x511,0,Creature-2,Target,0x10,0,1234,Fireball,4",
        );
        assert_eq!(event_name(&cast), "SPELL_CAST_SUCCESS");
        assert_eq!(event_data(&cast)["spell"]["spell_id"], "1234");
        assert_eq!(event_data(&cast)["spell"]["spell_name"], "Fireball");
    }

    #[test]
    fn parses_advanced_logging_damage_offsets() {
        let mut spell_damage = vec![
            "SPELL_DAMAGE",
            "Player-1",
            "Mage",
            "0x511",
            "0",
            "Creature-2",
            "Target",
            "0x10",
            "0",
            "1234",
            "Fireball",
            "4",
        ];
        spell_damage.extend(std::iter::repeat_n("0", 19));
        spell_damage.extend([
            "250", "300", "0", "4", "0", "0", "0", "nil", "nil", "nil", "ST",
        ]);
        let line = format!("10/29 20:15:01.125  {}", spell_damage.join(","));
        let event = parse_line(line.as_bytes());
        assert_eq!(event_name(&event), "SPELL_DAMAGE");
        assert_eq!(event_data(&event)["base_amount"], 250);
        assert_eq!(event_data(&event)["advanced_block"]["unit"], "target");

        let mut swing_damage = vec![
            "SWING_DAMAGE",
            "Player-1",
            "Mage",
            "0x511",
            "0",
            "Creature-2",
            "Target",
            "0x10",
            "0",
        ];
        swing_damage.extend(std::iter::repeat_n("0", 19));
        swing_damage.extend(["500", "500", "0", "0x1", "0", "0", "0", "nil", "nil", "nil"]);
        let line = format!("10/29 20:15:01.125  {}", swing_damage.join(","));
        let event = parse_line(line.as_bytes());
        assert_eq!(event_name(&event), "SWING_DAMAGE");
        assert_eq!(event_data(&event)["base_amount"], 500);
        assert_eq!(event_data(&event)["advanced_block"]["unit"], "source");
    }

    #[test]
    fn extracts_heal_energize_and_absorb_amounts() {
        let mut heal = vec!["SPELL_HEAL".to_owned()];
        heal.extend((0..16).map(|_| "0".to_owned()));
        heal[13] = "250".to_owned();
        let line = format!("10/29 20:15:01.125  {}", heal.join(","));
        assert_eq!(amount(&line), Some(250.0));

        let mut advanced_heal = vec!["SPELL_HEAL".to_owned()];
        advanced_heal.extend((0..35).map(|_| "0".to_owned()));
        advanced_heal[32] = "500".to_owned();
        let line = format!("10/29 20:15:01.125  {}", advanced_heal.join(","));
        assert_eq!(amount(&line), Some(500.0));

        let mut energize = vec!["SPELL_ENERGIZE".to_owned()];
        energize.extend((0..15).map(|_| "0".to_owned()));
        energize[12] = "75".to_owned();
        let line = format!("10/29 20:15:01.125  {}", energize.join(","));
        assert_eq!(amount(&line), Some(75.0));

        let mut absorbed = vec!["SPELL_ABSORBED".to_owned()];
        absorbed.extend((0..18).map(|_| "0".to_owned()));
        absorbed[16] = "900".to_owned();
        let line = format!("10/29 20:15:01.125  {}", absorbed.join(","));
        assert_eq!(amount(&line), Some(900.0));

        let mut environmental = vec!["ENVIRONMENTAL_DAMAGE".to_owned()];
        environmental.extend((0..19).map(|_| "0".to_owned()));
        environmental[10] = "1200".to_owned();
        let line = format!("10/29 20:15:01.125  {}", environmental.join(","));
        assert_eq!(amount(&line), Some(1200.0));

        let mut advanced_environmental = vec!["ENVIRONMENTAL_DAMAGE".to_owned()];
        advanced_environmental.extend((0..38).map(|_| "0".to_owned()));
        advanced_environmental[29] = "1300".to_owned();
        let line = format!("10/29 20:15:01.125  {}", advanced_environmental.join(","));
        assert_eq!(amount(&line), Some(1300.0));
    }

    #[test]
    fn parses_fractional_energize_suffix_values_with_advanced_logging() {
        let line = br#"11/10/2026 00:33:56.7940  SPELL_ENERGIZE,Player-3681-092E3A6D,"Truedmg-Magtheridon-EU",0x518,0x80000000,Player-3681-092E3A6D,"Truedmg-Magtheridon-EU",0x518,0x80000000,203961,"Blood Frenzy",0x8,Player-3681-092E3A6D,0000000000000000,1193961,1193961,3842,620,9313,2340,119,358188,1,1000,1000,0,8176.88,-4353.63,2393,2.5952,308,0.0000,3.0000,1,1000"#;
        let event = parse_line(line);
        let data = event_data(&event);

        assert_eq!(event_name(&event), "SPELL_ENERGIZE");
        assert_eq!(data["amount"].as_f64(), Some(0.0));
        assert_eq!(data["over_energize"].as_f64(), Some(3.0));
        assert_eq!(data["power_type"].as_i64(), Some(1));
        assert_eq!(data["max_power"].as_i64(), Some(1000));
        assert_eq!(data["advanced_block"]["power_type"].as_i64(), Some(1));
        assert_eq!(data["advanced_block"]["max_power"].as_i64(), Some(1000));
        assert_eq!(data["advanced_block"]["position_x"].as_f64(), Some(8176.88));
    }

    #[test]
    fn decodes_documented_suffixes_and_typed_values() {
        let aura = parse_line(
            b"10/29 20:15:01.125  SPELL_AURA_APPLIED,Player-1,Mage,0x511,0,Creature-2,Target,0x10,0,1234,Fireball,4,BUFF,3",
        );
        assert_eq!(event_data(&aura)["aura_type"], "BUFF");
        assert_eq!(event_data(&aura)["amount"], 3);
        assert_eq!(event_data(&aura)["header"]["source_flags"], 0x511);
        assert_eq!(aura.fields.len(), 13);
        let payload = serde_json::to_value(&aura).unwrap();
        assert_eq!(payload["event"]["data"]["amount"], 3);
        assert_eq!(payload["fields"][12], "3");

        let challenge = parse_line(
            br#"10/29 20:15:01.125  CHALLENGE_MODE_START,"The Dungeon",249,8,20,"147,148""#,
        );
        assert_eq!(
            event_data(&challenge)["affixes"],
            serde_json::json!([147, 148])
        );

        let extended = parse_line(
            b"10/29 20:15:01.125  SPELL_CAST_FAILED,Player-1,Mage,0x511,0,Creature-2,Target,0x10,0,1234,Fireball,4,Out of range,future,",
        );
        assert_eq!(event_data(&extended)["fail_reason"], "Out of range");
        assert_eq!(extended.fields.len(), 14);
        assert_eq!(extended.fields[12].as_deref(), Some("future"));
        assert_eq!(extended.fields[13].as_deref(), Some(""));
    }

    #[test]
    fn decodes_variable_absorb_and_combatant_info_layouts() {
        let long_absorb = parse_line(
            br#"10/29 20:15:01.125  SPELL_ABSORBED,Creature-1,"Attacker",0xa48,0,Player-2,"Defender",0x511,0,17,"Fireball",4,Player-3,"Absorber",0x511,0,18,"Shield",2,900,1200,nil"#,
        );
        assert_eq!(event_data(&long_absorb)["attacker_guid"], "Creature-1");
        assert_eq!(event_data(&long_absorb)["damage_spell_id"], "17");
        assert_eq!(event_data(&long_absorb)["absorber_guid"], "Player-3");
        assert_eq!(event_data(&long_absorb)["amount"], 900);
        assert_eq!(long_absorb.fields.len(), 21);

        let short_absorb = parse_line(
            br#"10/29 20:15:01.125  SPELL_ABSORBED,Creature-1,"Attacker",0xa48,0,Player-2,"Defender",0x511,0,Player-2,"Defender",0x511,0,18,"Shield",2,900,1200,nil"#,
        );
        assert_eq!(event_data(&short_absorb)["absorber_guid"], "Player-2");
        assert_eq!(event_data(&short_absorb)["amount"], 900);
        assert_eq!(short_absorb.fields.len(), 18);

        let combatant = parse_line(
            b"10/29 20:15:01.125  COMBATANT_INFO,Player-1,1,1,2,3,4,0,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,64,[(1,2,3)],(4,5),[(6,7,(8),(9,10),(11))],[(Player-1,1234)]",
        );
        assert_eq!(event_data(&combatant)["player_guid"], "Player-1");
        assert_eq!(event_data(&combatant)["crit_rating_melee"], 11);
        assert_eq!(event_data(&combatant)["crit_rating_spell"], 13);
        assert_eq!(
            event_data(&combatant)["talents"],
            serde_json::json!([[1, 2, 3]])
        );
        assert_eq!(
            event_data(&combatant)["pvp_talents"],
            serde_json::json!([4, 5])
        );
        assert_eq!(
            event_data(&combatant)["active_auras"],
            serde_json::json!([["Player-1", 1234]])
        );
        assert_eq!(
            event_data(&combatant)["gear"],
            serde_json::json!([[6, 7, [8], [9, 10], [11]]])
        );
        assert_eq!(combatant.fields.len(), 29);

        let version = parse_line(
            b"10/29 20:15:01.125  COMBAT_LOG_VERSION,22,ADVANCED_LOG_ENABLED,1,BUILD_VERSION,12.0.0,PROJECT_ID,1",
        );
        assert_eq!(event_data(&version)["format_version"], 22);
        assert_eq!(event_data(&version)["advanced_log_enabled"], true);
        assert_eq!(event_data(&version)["build_version"], "12.0.0");
        assert_eq!(event_data(&version)["project_id"], "1");
    }

    #[test]
    fn decodes_flat_combatant_active_auras() {
        let combatant = parse_line(
            b"10/29 20:15:01.125  COMBATANT_INFO,Player-1,1,1,2,3,4,0,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,64,[],(),[],[Vehicle-1,465,1,Vehicle-2,1126,1]",
        );

        assert_eq!(
            event_data(&combatant)["active_auras"],
            serde_json::json!([["Vehicle-1", 465, 1], ["Vehicle-2", 1126, 1]])
        );
        assert_eq!(
            combatant.fields[28].as_deref(),
            Some("[Vehicle-1,465,1,Vehicle-2,1126,1]")
        );
    }

    #[test]
    fn preserves_offset_timestamps_and_publishes_malformed_structure() {
        let event = parse_line(
            b"10/31/2026 19:41:14.7042  SPELL_CAST_START,Player-1,Mage,0x511,0,Creature-2,Target,0x10,0,1234,Fireball,4",
        );
        assert_eq!(event.timestamp, "10/31/2026 19:41:14.7042");

        for line in [
            &br#"10/31/2026 19:41:14.704-4  SPELL_CAST_START,Player-1,"Mage,0x511,0"#[..],
            &b"10/31/2026 19:41:14.704-4  COMBATANT_INFO,Player-1,[1,(2,3]"[..],
        ] {
            let event = parse_line(line);
            let payload = serde_json::to_value(event).unwrap();
            assert_eq!(payload["event"]["type"], "MALFORMED_EVENT");
            assert_eq!(payload["event"]["data"]["raw_bytes"], to_hex(line));
        }
    }

    #[test]
    fn accepts_tab_separator_and_nil_unit_fields() {
        let event = parse_line(
            b"10/29 20:15:01.125\tSPELL_CAST_SUCCESS,nil,nil,0,0,Creature-2,Target,0x10,0,1234,Fireball,4",
        );
        let header = &event_data(&event)["header"];
        assert!(header["source_guid"].is_null());
        assert!(header["source_name"].is_null());
        assert_eq!(event_data(&event)["spell"]["spell_name"], "Fireball");
    }

    #[test]
    fn recognizes_every_event_listed_in_the_specification() {
        for expected in SPEC_EVENTS {
            let line = format!("10/29 20:15:01.125  {expected}");
            let event = parse_line(line.as_bytes());
            assert_eq!(event_name(&event), *expected);
        }
    }

    #[test]
    fn preserves_no_source_target_and_special_event_payloads() {
        let version = parse_line(
            b"10/29 20:15:01.125  COMBAT_LOG_VERSION,22,ADVANCED_LOG_ENABLED,1,BUILD_VERSION,12.0.0,PROJECT_ID,1",
        );
        assert_eq!(version.fields[0].as_deref(), Some("22"));
        assert_eq!(version.fields[6].as_deref(), Some("1"));

        let absorbed = parse_line(
            br#"10/29 20:15:01.125  SPELL_ABSORBED,Player-1,"Mage",0x511,0,Player-2,"Tank",0x511,0,1234,"Shield",2,500"#,
        );
        assert_eq!(absorbed.fields.len(), 12);
    }
}
