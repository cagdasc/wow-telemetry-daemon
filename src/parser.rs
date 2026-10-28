use std::borrow::Cow;
use std::str;

use memchr::{memchr, memchr2};
use serde::Serialize;

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq, Serialize)]
pub enum EventKind {
    #[serde(rename = "SPELL_DAMAGE")]
    SpellDamage,
    #[serde(rename = "SWING_DAMAGE")]
    SwingDamage,
    #[serde(rename = "SPELL_CAST_SUCCESS")]
    SpellCastSuccess,
    #[serde(rename = "PLAYER_REGEN_DISABLED")]
    PlayerRegenDisabled,
    #[serde(rename = "PLAYER_REGEN_ENABLED")]
    PlayerRegenEnabled,
}

#[derive(Debug, Serialize)]
pub struct CombatEvent<'a> {
    pub timestamp: &'a str,
    pub event: EventKind,
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

fn field<'a>(fields: &mut Fields<'a>) -> Result<Cow<'a, str>, ParseError> {
    fields.next()?.ok_or(ParseError("event has too few fields"))
}

pub fn parse_line(line: &[u8]) -> Result<Option<CombatEvent<'_>>, ParseError> {
    let mut offset = 0;
    let separator = loop {
        let Some(index) = memchr(b' ', &line[offset..]) else {
            return Err(ParseError("missing timestamp/event separator"));
        };
        let index = offset + index;
        if line.get(index + 1) == Some(&b' ') {
            break index;
        }
        offset = index + 1;
    };
    let timestamp = str::from_utf8(&line[..separator])
        .map_err(|_| ParseError("timestamp is not valid UTF-8"))?;
    let header = &line[separator + 2..];
    let event_end = memchr(b',', header).unwrap_or(header.len());
    let event_name = &header[..event_end];
    let event = match event_name {
        b"SPELL_DAMAGE" => EventKind::SpellDamage,
        b"SWING_DAMAGE" => EventKind::SwingDamage,
        b"SPELL_CAST_SUCCESS" => EventKind::SpellCastSuccess,
        b"PLAYER_REGEN_DISABLED" => EventKind::PlayerRegenDisabled,
        b"PLAYER_REGEN_ENABLED" => EventKind::PlayerRegenEnabled,
        _ => return Ok(None),
    };

    if matches!(
        event,
        EventKind::PlayerRegenDisabled | EventKind::PlayerRegenEnabled
    ) {
        return Ok(Some(CombatEvent {
            timestamp,
            event,
            source_guid: None,
            source_name: None,
            dest_guid: None,
            dest_name: None,
            spell_id: None,
            spell_name: None,
            amount: None,
        }));
    }

    let fields = &header[event_end + usize::from(event_end < header.len())..];
    let mut fields = Fields {
        bytes: fields,
        position: 0,
    };
    let source_guid = field(&mut fields)?;
    let source_name = field(&mut fields)?;
    let _source_flags = field(&mut fields)?;
    let _source_raid_flags = field(&mut fields)?;
    let dest_guid = field(&mut fields)?;
    let dest_name = field(&mut fields)?;
    let _dest_flags = field(&mut fields)?;
    let _dest_raid_flags = field(&mut fields)?;

    let (spell_id, spell_name, amount) = match event {
        EventKind::SpellDamage => {
            let spell_id = field(&mut fields)?;
            let spell_name = field(&mut fields)?;
            let _spell_school = field(&mut fields)?;
            let amount = field(&mut fields)?;
            (Some(spell_id), Some(spell_name), Some(amount))
        }
        EventKind::SpellCastSuccess => {
            let spell_id = field(&mut fields)?;
            let spell_name = field(&mut fields)?;
            (Some(spell_id), Some(spell_name), None)
        }
        EventKind::SwingDamage => {
            let amount = field(&mut fields)?;
            (None, None, Some(amount))
        }
        _ => unreachable!(),
    };

    Ok(Some(CombatEvent {
        timestamp,
        event,
        source_guid: Some(source_guid),
        source_name: Some(source_name),
        dest_guid: Some(dest_guid),
        dest_name: Some(dest_name),
        spell_id,
        spell_name,
        amount,
    }))
}

#[cfg(test)]
mod tests {
    use super::{EventKind, parse_line};

    #[test]
    fn parses_spell_damage_with_quoted_commas_and_escaped_quotes() {
        let line = br#"10/28 15:04:05.123  SPELL_DAMAGE,Player-1,"Mage, ""The Great""",0x511,0,Creature-2,"Target",0x10,0,1234,"Fireball",4,250,0"#;
        let event = parse_line(line).unwrap().unwrap();

        assert_eq!(event.event, EventKind::SpellDamage);
        assert_eq!(event.timestamp, "10/28 15:04:05.123");
        assert_eq!(event.source_name.as_deref(), Some("Mage, \"The Great\""));
        assert_eq!(event.spell_id.as_deref(), Some("1234"));
        assert_eq!(event.amount.as_deref(), Some("250"));
    }

    #[test]
    fn parses_combat_state_and_ignores_other_events() {
        let event = parse_line(b"10/28 15:04:05.123  PLAYER_REGEN_DISABLED")
            .unwrap()
            .unwrap();
        assert_eq!(event.event, EventKind::PlayerRegenDisabled);
        assert!(
            parse_line(b"10/28 15:04:05.123  SPELL_HEAL")
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
        assert_eq!(swing.event, EventKind::SwingDamage);
        assert_eq!(swing.amount.as_deref(), Some("500"));
        assert!(swing.spell_id.is_none());

        let cast =
            parse_line(b"10/28 15:04:05.123  SPELL_CAST_SUCCESS,Player-1,Mage,0x511,0,Creature-2,Target,0x10,0,1234,Fireball,4")
                .unwrap()
                .unwrap();
        assert_eq!(cast.event, EventKind::SpellCastSuccess);
        assert_eq!(cast.spell_id.as_deref(), Some("1234"));
        assert_eq!(cast.spell_name.as_deref(), Some("Fireball"));
    }
}
