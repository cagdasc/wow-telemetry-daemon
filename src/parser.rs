use std::borrow::Cow;
use std::str;

use memchr::{memchr, memchr2};
use serde::Serialize;

pub const SPEC_EVENTS: &[&str] = &[
    "SPELL_DAMAGE",
    "SPELL_PERIODIC_DAMAGE",
    "RANGE_DAMAGE",
    "DAMAGE_SHIELD",
    "DAMAGE_SPLIT",
    "SPELL_DAMAGE_SUPPORT",
    "SPELL_PERIODIC_DAMAGE_SUPPORT",
    "RANGE_DAMAGE_SUPPORT",
    "SWING_DAMAGE_LANDED_SUPPORT",
    "SWING_DAMAGE",
    "SWING_DAMAGE_LANDED",
    "ENVIRONMENTAL_DAMAGE",
    "SPELL_HEAL",
    "SPELL_PERIODIC_HEAL",
    "SPELL_HEAL_SUPPORT",
    "SPELL_PERIODIC_HEAL_SUPPORT",
    "SPELL_ABSORBED",
    "SPELL_ABSORBED_SUPPORT",
    "SPELL_HEAL_ABSORBED",
    "SPELL_AURA_APPLIED",
    "SPELL_AURA_REMOVED",
    "SPELL_AURA_REFRESH",
    "SPELL_AURA_BROKEN",
    "SPELL_AURA_BROKEN_SPELL",
    "SPELL_AURA_APPLIED_DOSE",
    "SPELL_AURA_REMOVED_DOSE",
    "SPELL_INTERRUPT",
    "SPELL_DISPEL",
    "SPELL_DISPEL_FAILED",
    "SPELL_STOLEN",
    "SPELL_ENERGIZE",
    "SPELL_DRAIN",
    "SPELL_LEECH",
    "SPELL_PERIODIC_ENERGIZE",
    "SPELL_CAST_START",
    "SPELL_CAST_SUCCESS",
    "SPELL_EMPOWER_START",
    "SPELL_EMPOWER_END",
    "SPELL_EMPOWER_INTERRUPT",
    "SPELL_CAST_FAILED",
    "SWING_MISSED",
    "SPELL_MISSED",
    "RANGE_MISSED",
    "SPELL_PERIODIC_MISSED",
    "DAMAGE_SHIELD_MISSED",
    "SPELL_SUMMON",
    "SPELL_RESURRECT",
    "SPELL_INSTAKILL",
    "UNIT_DIED",
    "UNIT_DESTROYED",
    "UNIT_DISSIPATES",
    "UNIT_HEALTH",
    "ENCOUNTER_START",
    "ENCOUNTER_END",
    "CHALLENGE_MODE_START",
    "CHALLENGE_MODE_END",
    "ZONE_CHANGE",
    "MAP_CHANGE",
    "WORLD_MARKER_PLACED",
    "WORLD_MARKER_REMOVED",
    "COMBATANT_INFO",
    "COMBAT_LOG_VERSION",
];

const ADDITIONAL_EVENTS: &[&str] = &["PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED"];
const NO_SOURCE_TARGET_EVENTS: &[&str] = &[
    "UNIT_HEALTH",
    "ENCOUNTER_START",
    "ENCOUNTER_END",
    "CHALLENGE_MODE_START",
    "CHALLENGE_MODE_END",
    "ZONE_CHANGE",
    "MAP_CHANGE",
    "WORLD_MARKER_PLACED",
    "WORLD_MARKER_REMOVED",
    "COMBATANT_INFO",
    "COMBAT_LOG_VERSION",
];
const NON_STANDARD_HEADER_EVENTS: &[&str] = &[
    "SPELL_ABSORBED",
    "SPELL_ABSORBED_SUPPORT",
    "SPELL_HEAL_ABSORBED",
];

#[derive(Debug, Serialize)]
pub struct CombatEvent<'a> {
    pub timestamp: &'a str,
    pub event: &'a str,
    pub fields: Vec<Option<Cow<'a, str>>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source_guid: Option<Cow<'a, str>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source_name: Option<Cow<'a, str>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dest_guid: Option<Cow<'a, str>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dest_name: Option<Cow<'a, str>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub spell_id: Option<Cow<'a, str>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub spell_name: Option<Cow<'a, str>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub amount: Option<Cow<'a, str>>,
}

#[derive(Debug)]
pub struct ParseError(&'static str);

impl std::fmt::Display for ParseError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.0)
    }
}

impl std::error::Error for ParseError {}

struct Fields<'a> {
    bytes: &'a [u8],
    position: usize,
}

impl<'a> Fields<'a> {
    fn next(&mut self) -> Result<Option<Cow<'a, str>>, ParseError> {
        if self.position > self.bytes.len() {
            return Ok(None);
        }

        let start = self.position;
        let mut position = start;
        let mut quoted = false;
        let end = loop {
            match memchr2(b',', b'"', &self.bytes[position..]) {
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
                        b',' if !quoted => break position,
                        b',' => position += 1,
                        _ => unreachable!(),
                    }
                }
                None => break self.bytes.len(),
            }
        };
        self.position = if end == self.bytes.len() {
            self.bytes.len() + 1
        } else {
            end + 1
        };

        let mut value = &self.bytes[start..end];
        if value.len() >= 2 && value[0] == b'"' && value[value.len() - 1] == b'"' {
            value = &value[1..value.len() - 1];
        }
        let value = str::from_utf8(value).map_err(|_| ParseError("field is not valid UTF-8"))?;
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
    (event.starts_with("SPELL_") || event.starts_with("RANGE_"))
        && !NON_STANDARD_HEADER_EVENTS.contains(&event)
        || event == "SWING_DAMAGE_LANDED_SUPPORT"
}

fn indexed_field<'a>(fields: &[Option<Cow<'a, str>>], index: usize) -> Option<Cow<'a, str>> {
    fields.get(index).cloned().flatten()
}

pub fn parse_line(line: &[u8]) -> Result<Option<CombatEvent<'_>>, ParseError> {
    let mut offset = 0;
    let (separator, separator_end) = loop {
        let Some(index) = memchr2(b' ', b'\t', &line[offset..]) else {
            return Err(ParseError("missing timestamp/event separator"));
        };
        let index = offset + index;
        if line[index] == b'\t' {
            break (index, index + 1);
        }
        if line.get(index + 1) == Some(&b' ') {
            let mut end = index + 2;
            while line.get(end) == Some(&b' ') {
                end += 1;
            }
            break (index, end);
        }
        offset = index + 1;
    };
    let timestamp = str::from_utf8(&line[..separator])
        .map_err(|_| ParseError("timestamp is not valid UTF-8"))?;
    let header = &line[separator_end..];
    let event_end = memchr(b',', header).unwrap_or(header.len());
    let event_name = &header[..event_end];
    let event =
        str::from_utf8(event_name).map_err(|_| ParseError("event name is not valid UTF-8"))?;
    if !is_recognized_event(event) {
        return Ok(None);
    }

    let payload = &header[event_end + usize::from(event_end < header.len())..];
    let mut csv = Fields {
        bytes: payload,
        position: 0,
    };
    let mut fields = Vec::new();
    if !payload.is_empty() {
        while let Some(value) = csv.next()? {
            fields.push(present(value));
        }
    }

    let has_common_header = !NO_SOURCE_TARGET_EVENTS.contains(&event)
        && !NON_STANDARD_HEADER_EVENTS.contains(&event)
        && !ADDITIONAL_EVENTS.contains(&event);
    let (source_guid, source_name, dest_guid, dest_name) = if has_common_header {
        (
            indexed_field(&fields, 0),
            indexed_field(&fields, 1),
            indexed_field(&fields, 4),
            indexed_field(&fields, 5),
        )
    } else {
        (None, None, None, None)
    };

    let (spell_id, spell_name) = if has_spell_prefix(event) {
        (indexed_field(&fields, 8), indexed_field(&fields, 9))
    } else {
        (None, None)
    };

    let total_field_count = fields.len() + 1;
    let amount = match event {
        "SPELL_DAMAGE"
        | "SPELL_PERIODIC_DAMAGE"
        | "RANGE_DAMAGE"
        | "DAMAGE_SHIELD"
        | "DAMAGE_SPLIT"
        | "SPELL_DAMAGE_SUPPORT"
        | "SPELL_PERIODIC_DAMAGE_SUPPORT"
        | "RANGE_DAMAGE_SUPPORT"
        | "SWING_DAMAGE_LANDED_SUPPORT" => {
            indexed_field(&fields, if total_field_count >= 40 { 30 } else { 11 })
        }
        "SWING_DAMAGE" | "SWING_DAMAGE_LANDED" => {
            indexed_field(&fields, if total_field_count >= 30 { 27 } else { 8 })
        }
        "ENVIRONMENTAL_DAMAGE" => indexed_field(&fields, 28),
        _ => None,
    };

    Ok(Some(CombatEvent {
        timestamp,
        event,
        fields,
        source_guid,
        source_name,
        dest_guid,
        dest_name,
        spell_id,
        spell_name,
        amount,
    }))
}

#[cfg(test)]
mod tests {
    use super::{SPEC_EVENTS, parse_line};

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
    }

    #[test]
    fn parses_combat_state_and_ignores_other_events() {
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
        assert!(
            parse_line(b"10/28 15:04:05.123  SPELL_NOT_IN_SPEC")
                .unwrap()
                .is_none()
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
        spell_damage.extend(std::iter::repeat_n("advanced", 19));
        spell_damage.extend([
            "250", "300", "0", "4", "0", "0", "0", "nil", "nil", "nil", "ST",
        ]);
        let line = format!("10/29 20:15:01.125  {}", spell_damage.join(","));
        let event = parse_line(line.as_bytes()).unwrap().unwrap();
        assert_eq!(event.event, "SPELL_DAMAGE");
        assert_eq!(event.amount.as_deref(), Some("250"));

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
        swing_damage.extend(std::iter::repeat_n("advanced", 19));
        swing_damage.extend(["500", "500", "0", "0x1", "0", "0", "0", "nil", "nil", "nil"]);
        let line = format!("10/29 20:15:01.125  {}", swing_damage.join(","));
        let event = parse_line(line.as_bytes()).unwrap().unwrap();
        assert_eq!(event.event, "SWING_DAMAGE");
        assert_eq!(event.amount.as_deref(), Some("500"));
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
