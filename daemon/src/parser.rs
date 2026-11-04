use std::borrow::Cow;
use std::str;

use crate::spec_events::{
    FieldType, NO_SOURCE_TARGET_EVENTS, NON_STANDARD_HEADER_EVENTS, SPEC_EVENTS,
    SPEC_SPELL_PREFIX_EVENTS, event_fields as spec_event_fields,
};
use crate::typed_events::{
    MalformedEventEventData, TypedCombatLogEvent, UnrecognizedEventEventData, from_decoded,
};
use memchr::{memchr, memchr2};
use serde::Serialize;
use serde_json::{Map, Number, Value};

const ADDITIONAL_EVENTS: &[&str] = &["PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED"];

#[derive(Debug, Serialize)]
pub struct CombatEvent<'a> {
    pub timestamp: Cow<'a, str>,
    #[serde(skip_serializing)]
    pub event: &'a str,
    pub fields: Vec<Option<Cow<'a, str>>>,
    #[serde(rename = "event")]
    pub typed_event: TypedCombatLogEvent,
    #[serde(skip_serializing)]
    pub source_guid: Option<Cow<'a, str>>,
    #[serde(skip_serializing)]
    pub source_name: Option<Cow<'a, str>>,
    #[serde(skip_serializing)]
    pub spell_id: Option<Cow<'a, str>>,
    #[serde(skip_serializing)]
    pub spell_name: Option<Cow<'a, str>>,
    #[serde(skip_serializing)]
    pub amount: Option<Cow<'a, str>>,
}

#[derive(Debug)]
pub struct ParseError(String);

impl ParseError {
    fn new(message: impl Into<String>) -> Self {
        Self(message.into())
    }
}

impl std::fmt::Display for ParseError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for ParseError {}

impl From<serde_json::Error> for ParseError {
    fn from(error: serde_json::Error) -> Self {
        Self::new(format!("could not decode typed event: {error}"))
    }
}

struct Fields<'a> {
    bytes: &'a [u8],
    position: usize,
    bracket_aware: bool,
}

impl<'a> Fields<'a> {
    fn next(&mut self) -> Result<Option<Cow<'a, str>>, ParseError> {
        if self.position > self.bytes.len() {
            return Ok(None);
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
                                    return Err(ParseError::new(
                                        "combatant field nesting is too deep",
                                    ));
                                };
                                *slot = close;
                                nesting_depth += 1;
                            }
                            position += 1;
                        }
                        b']' | b')' if !quoted => {
                            if self.bracket_aware {
                                if nesting_depth == 0
                                    || nesting[nesting_depth - 1] != self.bytes[position]
                                {
                                    return Err(ParseError::new(
                                        "unbalanced combatant field brackets",
                                    ));
                                }
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
            return Err(ParseError::new("unterminated quoted field"));
        }
        if nesting_depth != 0 {
            return Err(ParseError::new("unterminated combatant field brackets"));
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
        let value =
            str::from_utf8(value).map_err(|_| ParseError::new("field is not valid UTF-8"))?;
        if value.contains("\"\"") {
            Ok(Some(Cow::Owned(value.replace("\"\"", "\""))))
        } else {
            Ok(Some(Cow::Borrowed(value)))
        }
    }
}

fn present(value: Cow<'_, str>) -> Option<Cow<'_, str>> {
    (value != "nil").then_some(value)
}

fn is_recognized_event(event: &str) -> bool {
    SPEC_EVENTS.contains(&event) || ADDITIONAL_EVENTS.contains(&event)
}

fn has_spell_prefix(event: &str) -> bool {
    SPEC_SPELL_PREFIX_EVENTS.contains(&event)
}

fn indexed_field<'a>(fields: &[Option<Cow<'a, str>>], index: usize) -> Option<Cow<'a, str>> {
    fields.get(index).cloned().flatten()
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
    if let Ok(value) = value.parse::<f64>() {
        if let Some(value) = Number::from_f64(value) {
            return Some(Value::Number(value));
        }
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

fn parsed_fields<'a>(
    event: &str,
    fields: &[Option<Cow<'a, str>>],
) -> (Map<String, Value>, Option<Cow<'a, str>>) {
    let total_field_count = fields.len() + 1;
    let layout = spec_event_fields(event);
    let advanced_start = layout
        .iter()
        .filter(|field| field.advanced)
        .map(|field| field.offset)
        .min();
    let advanced_min_fields = layout
        .iter()
        .find(|field| field.advanced)
        .map(|field| field.advanced_min_fields)
        .unwrap_or(usize::MAX);
    let advanced = advanced_start
        .map(|_| total_field_count >= advanced_min_fields)
        .unwrap_or(false);
    let variant = match event {
        "SPELL_ABSORBED" | "SPELL_ABSORBED_SUPPORT" | "SPELL_HEAL_ABSORBED" => {
            if total_field_count >= 22 { 1 } else { 2 }
        }
        _ => 0,
    };
    let mut decoded = Map::new();
    let mut amount = None;
    if advanced {
        if let Some(unit) = layout
            .iter()
            .find(|field| field.advanced)
            .map(|field| field.advanced_unit)
            .filter(|unit| !unit.is_empty())
        {
            decoded.insert(
                "advanced_block_unit".to_owned(),
                Value::String(unit.to_owned()),
            );
        }
    }
    for spec_field in layout {
        if spec_field.variant != variant || (spec_field.advanced && !advanced) {
            continue;
        }
        let mut offset = spec_field.offset;
        if !advanced {
            if let Some(start) = advanced_start {
                if offset >= start + 19 {
                    offset -= 19;
                }
            }
        }
        let Some(value) = fields.get(offset.saturating_sub(1)) else {
            continue;
        };
        let Some(value) = value.as_deref() else {
            decoded.insert(spec_field.name.to_owned(), Value::Null);
            continue;
        };
        let parsed = if is_identifier_field(spec_field.name) {
            Value::String(value.to_owned())
        } else {
            parse_spec_value(Some(value), spec_field.field_type)
        };
        if spec_field.name == "base_amount" || spec_field.name == "amount" {
            amount = fields.get(offset.saturating_sub(1)).cloned().flatten();
        }
        decoded.insert(spec_field.name.to_owned(), parsed);
    }
    (decoded, amount)
}

fn is_identifier_field(name: &str) -> bool {
    name == "id" || name == "guid" || name.ends_with("_id") || name.ends_with("_guid")
}

pub fn parse_line(line: &[u8]) -> Result<Option<CombatEvent<'_>>, ParseError> {
    match parse_line_inner(line) {
        Ok(event) => Ok(event),
        Err(error) => Ok(Some(malformed_event(line, error))),
    }
}

fn find_separator(line: &[u8]) -> Result<(usize, usize), ParseError> {
    let mut offset = 0;
    loop {
        let Some(index) = memchr2(b' ', b'\t', &line[offset..]) else {
            return Err(ParseError::new("missing timestamp/event separator"));
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
        event: "MALFORMED_EVENT",
        fields: Vec::new(),
        typed_event: TypedCombatLogEvent::MalformedEvent(MalformedEventEventData {
            reason: error.to_string(),
            raw_bytes: line.to_vec(),
        }),
        source_guid: None,
        source_name: None,
        spell_id: None,
        spell_name: None,
        amount: None,
    }
}

fn parse_line_inner(line: &[u8]) -> Result<Option<CombatEvent<'_>>, ParseError> {
    let (separator, separator_end) = find_separator(line)?;
    let timestamp = str::from_utf8(&line[..separator])
        .map_err(|_| ParseError::new("timestamp is not valid UTF-8"))?;
    let header = &line[separator_end..];
    let event_end = memchr(b',', header).unwrap_or(header.len());
    let event_name = &header[..event_end];
    let event =
        str::from_utf8(event_name).map_err(|_| ParseError::new("event name is not valid UTF-8"))?;
    if event.is_empty() {
        return Err(ParseError::new("missing event name"));
    }
    let payload = &header[event_end + usize::from(event_end < header.len())..];
    let mut csv = Fields {
        bytes: payload,
        position: 0,
        bracket_aware: event == "COMBATANT_INFO",
    };
    let mut fields = Vec::new();
    if event_end < header.len() {
        while let Some(value) = csv.next()? {
            fields.push(present(value));
        }
    }

    let recognized = is_recognized_event(event);
    let has_common_header = recognized
        && !NO_SOURCE_TARGET_EVENTS.contains(&event)
        && !NON_STANDARD_HEADER_EVENTS.contains(&event)
        && !ADDITIONAL_EVENTS.contains(&event);
    let (source_guid, source_name) = if has_common_header {
        (indexed_field(&fields, 0), indexed_field(&fields, 1))
    } else {
        (None, None)
    };

    let (spell_id, spell_name) = if recognized && has_spell_prefix(event) {
        (indexed_field(&fields, 8), indexed_field(&fields, 9))
    } else {
        (None, None)
    };

    let (decoded_fields, amount) = parsed_fields(event, &fields);
    let typed_event = if recognized {
        from_decoded(event, decoded_fields)?
    } else {
        TypedCombatLogEvent::UnrecognizedEvent(UnrecognizedEventEventData {
            event_name: event.to_owned(),
        })
    };

    Ok(Some(CombatEvent {
        timestamp: Cow::Borrowed(timestamp),
        event,
        fields,
        typed_event,
        source_guid,
        source_name,
        spell_id,
        spell_name,
        amount,
    }))
}

#[cfg(test)]
mod tests {
    use super::{CombatEvent, SPEC_EVENTS, parse_line};
    use serde_json::Value;

    fn event_data(event: &CombatEvent<'_>) -> Value {
        serde_json::to_value(&event.typed_event).unwrap()["data"].clone()
    }

    #[test]
    fn parses_spell_damage_with_quoted_commas_and_escaped_quotes() {
        let line = br#"10/28 15:04:05.123  SPELL_DAMAGE,Player-1,"Mage, ""The Great""",0x511,0,Creature-2,"Target",0x10,0,1234,"Fireball",4,250,0"#;
        let event = parse_line(line).unwrap().unwrap();

        assert_eq!(event.event, "SPELL_DAMAGE");
        assert_eq!(event.timestamp, "10/28 15:04:05.123");
        assert_eq!(event.source_name.as_deref(), Some("Mage, \"The Great\""));
        assert_eq!(event.spell_id.as_deref(), Some("1234"));
        assert_eq!(event.amount.as_deref(), Some("250"));
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
        let event = parse_line(b"10/28 15:04:05.123  PLAYER_REGEN_DISABLED")
            .unwrap()
            .unwrap();
        assert_eq!(event.event, "PLAYER_REGEN_DISABLED");
        assert_eq!(
            parse_line(b"10/28 15:04:05.123  SPELL_HEAL")
                .unwrap()
                .unwrap()
                .event,
            "SPELL_HEAL"
        );
        let unknown = parse_line(b"10/28 15:04:05.123  SPELL_NOT_IN_SPEC,Player-1,Mage,nil")
            .unwrap()
            .unwrap();
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
        let event = parse_line(line).unwrap().unwrap();
        let payload = serde_json::to_value(&event).unwrap();

        assert_eq!(payload["event"]["type"], "MALFORMED_EVENT");
        assert_eq!(
            payload["event"]["data"]["reason"],
            "unterminated quoted field"
        );
        assert_eq!(
            payload["event"]["data"]["raw_bytes"],
            serde_json::json!(line.to_vec())
        );
    }

    #[test]
    fn publishes_invalid_utf8_lines_with_original_bytes() {
        let line = b"10/28 15:04:05.123  SPELL_DAMAGE,\xff";
        let event = parse_line(line).unwrap().unwrap();
        let payload = serde_json::to_value(&event).unwrap();

        assert_eq!(payload["event"]["type"], "MALFORMED_EVENT");
        assert_eq!(
            payload["event"]["data"]["reason"],
            "field is not valid UTF-8"
        );
        assert_eq!(
            payload["event"]["data"]["raw_bytes"],
            serde_json::json!(line.to_vec())
        );
    }

    #[test]
    fn publishes_known_events_with_invalid_typed_fields_as_malformed() {
        let line = b"10/28 15:04:05.123  COMBAT_LOG_VERSION,not-a-number,ADVANCED_LOG_ENABLED,1,BUILD_VERSION,12.0.0,PROJECT_ID,1";
        let event = parse_line(line).unwrap().unwrap();
        let payload = serde_json::to_value(&event).unwrap();

        assert_eq!(payload["event"]["type"], "MALFORMED_EVENT");
        assert!(
            payload["event"]["data"]["reason"]
                .as_str()
                .unwrap()
                .contains("could not decode typed event")
        );
        assert_eq!(
            payload["event"]["data"]["raw_bytes"],
            serde_json::json!(line.to_vec())
        );
    }

    #[test]
    fn parses_swing_damage_and_spell_cast_success() {
        let swing =
            parse_line(b"10/28 15:04:05.123  SWING_DAMAGE,Player-1,Mage,0x511,0,Creature-2,Target,0x10,0,500,0")
                .unwrap()
                .unwrap();
        assert_eq!(swing.event, "SWING_DAMAGE");
        assert_eq!(swing.amount.as_deref(), Some("500"));
        assert!(swing.spell_id.is_none());

        let cast =
            parse_line(b"10/28 15:04:05.123  SPELL_CAST_SUCCESS,Player-1,Mage,0x511,0,Creature-2,Target,0x10,0,1234,Fireball,4")
                .unwrap()
                .unwrap();
        assert_eq!(cast.event, "SPELL_CAST_SUCCESS");
        assert_eq!(cast.spell_id.as_deref(), Some("1234"));
        assert_eq!(cast.spell_name.as_deref(), Some("Fireball"));
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
        let event = parse_line(line.as_bytes()).unwrap().unwrap();
        assert_eq!(event.event, "SPELL_DAMAGE");
        assert_eq!(event.amount.as_deref(), Some("250"));
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
        let event = parse_line(line.as_bytes()).unwrap().unwrap();
        assert_eq!(event.event, "SWING_DAMAGE");
        assert_eq!(event.amount.as_deref(), Some("500"));
        assert_eq!(event_data(&event)["advanced_block"]["unit"], "source");
    }

    #[test]
    fn extracts_heal_energize_and_absorb_amounts() {
        let mut heal = vec!["SPELL_HEAL".to_owned()];
        heal.extend((0..16).map(|_| "0".to_owned()));
        heal[13] = "250".to_owned();
        let line = format!("10/29 20:15:01.125  {}", heal.join(","));
        assert_eq!(
            parse_line(line.as_bytes())
                .unwrap()
                .unwrap()
                .amount
                .as_deref(),
            Some("250")
        );

        let mut advanced_heal = vec!["SPELL_HEAL".to_owned()];
        advanced_heal.extend((0..35).map(|_| "0".to_owned()));
        advanced_heal[32] = "500".to_owned();
        let line = format!("10/29 20:15:01.125  {}", advanced_heal.join(","));
        assert_eq!(
            parse_line(line.as_bytes())
                .unwrap()
                .unwrap()
                .amount
                .as_deref(),
            Some("500")
        );

        let mut energize = vec!["SPELL_ENERGIZE".to_owned()];
        energize.extend((0..15).map(|_| "0".to_owned()));
        energize[12] = "75".to_owned();
        let line = format!("10/29 20:15:01.125  {}", energize.join(","));
        assert_eq!(
            parse_line(line.as_bytes())
                .unwrap()
                .unwrap()
                .amount
                .as_deref(),
            Some("75")
        );

        let mut absorbed = vec!["SPELL_ABSORBED".to_owned()];
        absorbed.extend((0..18).map(|_| "0".to_owned()));
        absorbed[16] = "900".to_owned();
        let line = format!("10/29 20:15:01.125  {}", absorbed.join(","));
        assert_eq!(
            parse_line(line.as_bytes())
                .unwrap()
                .unwrap()
                .amount
                .as_deref(),
            Some("900")
        );

        let mut environmental = vec!["ENVIRONMENTAL_DAMAGE".to_owned()];
        environmental.extend((0..19).map(|_| "0".to_owned()));
        environmental[10] = "1200".to_owned();
        let line = format!("10/29 20:15:01.125  {}", environmental.join(","));
        assert_eq!(
            parse_line(line.as_bytes())
                .unwrap()
                .unwrap()
                .amount
                .as_deref(),
            Some("1200")
        );

        let mut advanced_environmental = vec!["ENVIRONMENTAL_DAMAGE".to_owned()];
        advanced_environmental.extend((0..38).map(|_| "0".to_owned()));
        advanced_environmental[29] = "1300".to_owned();
        let line = format!("10/29 20:15:01.125  {}", advanced_environmental.join(","));
        assert_eq!(
            parse_line(line.as_bytes())
                .unwrap()
                .unwrap()
                .amount
                .as_deref(),
            Some("1300")
        );
    }

    #[test]
    fn decodes_documented_suffixes_and_typed_values() {
        let aura = parse_line(
            b"10/29 20:15:01.125  SPELL_AURA_APPLIED,Player-1,Mage,0x511,0,Creature-2,Target,0x10,0,1234,Fireball,4,BUFF,3",
        )
            .unwrap()
            .unwrap();
        assert_eq!(event_data(&aura)["aura_type"], "BUFF");
        assert_eq!(event_data(&aura)["amount"], 3);
        assert_eq!(event_data(&aura)["header"]["source_flags"], 0x511);
        assert_eq!(aura.fields.len(), 13);
        let payload = serde_json::to_value(&aura).unwrap();
        assert_eq!(payload["event"]["data"]["amount"], 3);
        assert_eq!(payload["fields"][12], "3");

        let challenge = parse_line(
            br#"10/29 20:15:01.125  CHALLENGE_MODE_START,"The Dungeon",249,8,20,"147,148""#,
        )
        .unwrap()
        .unwrap();
        assert_eq!(
            event_data(&challenge)["affixes"],
            serde_json::json!([147, 148])
        );

        let extended = parse_line(
            b"10/29 20:15:01.125  SPELL_CAST_FAILED,Player-1,Mage,0x511,0,Creature-2,Target,0x10,0,1234,Fireball,4,Out of range,future,",
        )
            .unwrap()
            .unwrap();
        assert_eq!(event_data(&extended)["fail_reason"], "Out of range");
        assert_eq!(extended.fields.len(), 14);
        assert_eq!(extended.fields[12].as_deref(), Some("future"));
        assert_eq!(extended.fields[13].as_deref(), Some(""));
    }

    #[test]
    fn decodes_variable_absorb_and_combatant_info_layouts() {
        let long_absorb = parse_line(
            br#"10/29 20:15:01.125  SPELL_ABSORBED,Creature-1,"Attacker",0xa48,0,Player-2,"Defender",0x511,0,17,"Fireball",4,Player-3,"Absorber",0x511,0,18,"Shield",2,900,1200,nil"#,
        )
            .unwrap()
            .unwrap();
        assert_eq!(event_data(&long_absorb)["attacker_guid"], "Creature-1");
        assert_eq!(event_data(&long_absorb)["damage_spell_id"], "17");
        assert_eq!(event_data(&long_absorb)["absorber_guid"], "Player-3");
        assert_eq!(event_data(&long_absorb)["amount"], 900);
        assert_eq!(long_absorb.fields.len(), 21);

        let short_absorb = parse_line(
            br#"10/29 20:15:01.125  SPELL_ABSORBED,Creature-1,"Attacker",0xa48,0,Player-2,"Defender",0x511,0,Player-2,"Defender",0x511,0,18,"Shield",2,900,1200,nil"#,
        )
            .unwrap()
            .unwrap();
        assert_eq!(event_data(&short_absorb)["absorber_guid"], "Player-2");
        assert_eq!(event_data(&short_absorb)["amount"], 900);
        assert_eq!(short_absorb.fields.len(), 18);

        let combatant = parse_line(
            b"10/29 20:15:01.125  COMBATANT_INFO,Player-1,1,1,2,3,4,0,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,64,[(1,2,3)],(4,5),[(6,7,(8),(9,10),(11))],[(Player-1,1234)]",
        )
            .unwrap()
            .unwrap();
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
        )
            .unwrap()
            .unwrap();
        assert_eq!(event_data(&version)["format_version"], 22);
        assert_eq!(event_data(&version)["advanced_log_enabled"], true);
        assert_eq!(event_data(&version)["build_version"], "12.0.0");
        assert_eq!(event_data(&version)["project_id"], "1");
    }

    #[test]
    fn preserves_offset_timestamps_and_publishes_malformed_structure() {
        let event = parse_line(
            b"10/31/2026 19:41:14.7042  SPELL_CAST_START,Player-1,Mage,0x511,0,Creature-2,Target,0x10,0,1234,Fireball,4",
        )
            .unwrap()
            .unwrap();
        assert_eq!(event.timestamp, "10/31/2026 19:41:14.7042");

        for line in [
            &br#"10/31/2026 19:41:14.704-4  SPELL_CAST_START,Player-1,"Mage,0x511,0"#[..],
            &b"10/31/2026 19:41:14.704-4  COMBATANT_INFO,Player-1,[1,(2,3]"[..],
        ] {
            let event = parse_line(line).unwrap().unwrap();
            let payload = serde_json::to_value(event).unwrap();
            assert_eq!(payload["event"]["type"], "MALFORMED_EVENT");
            assert_eq!(
                payload["event"]["data"]["raw_bytes"],
                serde_json::json!(line.to_vec())
            );
        }
    }

    #[test]
    fn accepts_tab_separator_and_nil_unit_fields() {
        let event = parse_line(
            b"10/29 20:15:01.125\tSPELL_CAST_SUCCESS,nil,nil,0,0,Creature-2,Target,0x10,0,1234,Fireball,4",
        )
        .unwrap()
        .unwrap();
        assert!(event.source_guid.is_none());
        assert!(event.source_name.is_none());
        assert_eq!(event.spell_name.as_deref(), Some("Fireball"));
    }

    #[test]
    fn recognizes_every_event_listed_in_the_specification() {
        for event_name in SPEC_EVENTS {
            let line = format!("10/29 20:15:01.125  {event_name}");
            let event = parse_line(line.as_bytes())
                .unwrap()
                .unwrap_or_else(|| panic!("did not recognize {event_name}"));
            assert_eq!(event.event, *event_name);
        }
    }

    #[test]
    fn preserves_no_source_target_and_special_event_payloads() {
        let version = parse_line(
            b"10/29 20:15:01.125  COMBAT_LOG_VERSION,22,ADVANCED_LOG_ENABLED,1,BUILD_VERSION,12.0.0,PROJECT_ID,1",
        )
        .unwrap()
        .unwrap();
        assert!(version.source_guid.is_none());
        assert_eq!(version.fields[0].as_deref(), Some("22"));
        assert_eq!(version.fields[6].as_deref(), Some("1"));

        let absorbed = parse_line(
            br#"10/29 20:15:01.125  SPELL_ABSORBED,Player-1,"Mage",0x511,0,Player-2,"Tank",0x511,0,1234,"Shield",2,500"#,
        )
        .unwrap()
        .unwrap();
        assert!(absorbed.source_guid.is_none());
        assert_eq!(absorbed.fields.len(), 12);
    }
}
