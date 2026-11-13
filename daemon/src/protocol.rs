//! Wire formats for the WebSocket stream: protobuf (default) and JSON.

use prost::Message as _;
use serde_json::{Value, json};
use tungstenite::Message;

use crate::parser::CombatEvent;
use crate::pb;
use crate::typed_events::{GearSlot, TalentEntry};

pub const PROTOCOL_VERSION: u32 = 1;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WireFormat {
    Protobuf,
    Json,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ReplayGapReason {
    EpochChanged,
    InvalidResume,
    BufferEvicted {
        missed_from: u64,
        available_from: u64,
    },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Control {
    Hello {
        epoch: u64,
        last_seq: u64,
    },
    LogCaughtUp,
    LogCleared,
    ReplayGap(ReplayGapReason),
    ClearLogResult {
        success: bool,
        message: Option<String>,
    },
}

impl Control {
    pub fn to_json(&self) -> String {
        match self {
            Self::Hello { epoch, last_seq } => {
                json!({"control": "HELLO", "epoch": epoch, "last_seq": last_seq, "protocol_version": PROTOCOL_VERSION})
            }
            Self::LogCaughtUp => json!({"control": "LOG_CAUGHT_UP"}),
            Self::LogCleared => json!({"control": "LOG_CLEARED"}),
            Self::ReplayGap(ReplayGapReason::EpochChanged) => {
                json!({"control": "REPLAY_GAP", "reason": "EPOCH_CHANGED"})
            }
            Self::ReplayGap(ReplayGapReason::InvalidResume) => {
                json!({"control": "REPLAY_GAP", "reason": "INVALID_RESUME"})
            }
            Self::ReplayGap(ReplayGapReason::BufferEvicted {
                missed_from,
                available_from,
            }) => json!({
                "control": "REPLAY_GAP",
                "reason": "BUFFER_EVICTED",
                "missed_from": missed_from,
                "available_from": available_from
            }),
            Self::ClearLogResult { success, message } => {
                let mut value = json!({"control": "CLEAR_LOG_RESULT", "success": success});
                if let Some(message) = message {
                    value["message"] = Value::String(message.clone());
                }
                value
            }
        }
        .to_string()
    }

    fn to_pb(&self) -> pb::Control {
        use pb::control::Kind;
        let kind = match self {
            Self::Hello { epoch, last_seq } => Kind::Hello(pb::Hello {
                epoch: *epoch,
                last_seq: *last_seq,
                protocol_version: PROTOCOL_VERSION,
            }),
            Self::LogCaughtUp => Kind::LogCaughtUp(pb::Empty {}),
            Self::LogCleared => Kind::LogCleared(pb::Empty {}),
            Self::ReplayGap(reason) => Kind::ReplayGap(match reason {
                ReplayGapReason::EpochChanged => pb::ReplayGap {
                    reason: pb::ReplayGapReason::EpochChanged.into(),
                    ..Default::default()
                },
                ReplayGapReason::InvalidResume => pb::ReplayGap {
                    reason: pb::ReplayGapReason::InvalidResume.into(),
                    ..Default::default()
                },
                ReplayGapReason::BufferEvicted {
                    missed_from,
                    available_from,
                } => pb::ReplayGap {
                    reason: pb::ReplayGapReason::BufferEvicted.into(),
                    missed_from: Some(*missed_from),
                    available_from: Some(*available_from),
                },
            }),
            Self::ClearLogResult { success, message } => Kind::ClearLogResult(pb::ClearLogResult {
                success: *success,
                message: message.clone(),
            }),
        };
        pb::Control { kind: Some(kind) }
    }
}

/// Prepends `seq` to a serialized JSON object.
pub fn json_with_seq(seq: u64, payload: &str) -> String {
    let body = payload.strip_prefix('{').unwrap_or(payload);
    let separator = if body.starts_with('}') { "" } else { "," };
    format!(r#"{{"seq":{seq}{separator}{body}"#)
}

/// Builds a control frame in `format`; unsequenced frames carry seq 0 in protobuf and no seq in JSON.
pub fn control_message(format: WireFormat, seq: Option<u64>, control: &Control) -> Message {
    match format {
        WireFormat::Protobuf => Message::Binary(encode_control(seq.unwrap_or(0), control).into()),
        WireFormat::Json => {
            let json = control.to_json();
            Message::Text(
                match seq {
                    Some(seq) => json_with_seq(seq, &json),
                    None => json,
                }
                .into(),
            )
        }
    }
}

/// Builds a sequenced event frame in `format`; `json` is the already serialized event, if any.
pub fn event_message(
    format: WireFormat,
    seq: u64,
    event: CombatEvent<'_>,
    json: Option<&str>,
) -> Result<Message, serde_json::Error> {
    Ok(match format {
        WireFormat::Protobuf => Message::Binary(encode_event(seq, log_event(event)).into()),
        WireFormat::Json => {
            let json_with_seq = match json {
                Some(json) => json_with_seq(seq, json),
                None => json_with_seq(seq, &serde_json::to_string(&event)?),
            };
            Message::Text(json_with_seq.into())
        }
    })
}

/// Encodes a control frame as a protobuf binary message body.
pub fn encode_control(seq: u64, control: &Control) -> Vec<u8> {
    pb::Envelope {
        seq,
        body: Some(pb::envelope::Body::Control(control.to_pb())),
    }
    .encode_to_vec()
}

/// Encodes an event frame as a protobuf binary message body.
pub fn encode_event(seq: u64, event: pb::LogEvent) -> Vec<u8> {
    pb::Envelope {
        seq,
        body: Some(pb::envelope::Body::Event(event)),
    }
    .encode_to_vec()
}

/// Converts a parsed event into its protobuf form, preserving the raw fields.
pub fn log_event(event: CombatEvent<'_>) -> pb::LogEvent {
    let mut fields = Vec::with_capacity(event.fields.len());
    let mut null_fields = Vec::new();
    for (index, field) in event.fields.into_iter().enumerate() {
        match field {
            Some(value) => fields.push(value.into_owned()),
            None => {
                fields.push(String::new());
                null_fields.push(index as u32);
            }
        }
    }
    pb::LogEvent {
        timestamp: event.timestamp.into_owned(),
        fields,
        null_fields,
        data: Some(event.typed_event.into()),
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Command {
    Resume {
        epoch: Option<u64>,
        from_seq: Option<u64>,
    },
    ClearLog,
}

impl Command {
    pub fn from_json(text: &str) -> Result<Self, String> {
        let request: Value =
            serde_json::from_str(text).map_err(|error| format!("Invalid command JSON: {error}"))?;
        match request.get("command").and_then(Value::as_str) {
            Some("RESUME") => Ok(Self::Resume {
                epoch: request.get("epoch").and_then(Value::as_u64),
                from_seq: request.get("from_seq").and_then(Value::as_u64),
            }),
            Some("CLEAR_LOG") => Ok(Self::ClearLog),
            _ => Err("Unsupported WebSocket command.".to_owned()),
        }
    }

    pub fn from_protobuf(bytes: &[u8]) -> Result<Self, String> {
        let command =
            pb::Command::decode(bytes).map_err(|error| format!("Invalid command: {error}"))?;
        match command.kind {
            Some(pb::command::Kind::Resume(resume)) => Ok(Self::Resume {
                epoch: Some(resume.epoch),
                from_seq: Some(resume.from_seq),
            }),
            Some(pb::command::Kind::ClearLog(_)) => Ok(Self::ClearLog),
            None => Err("Unsupported WebSocket command.".to_owned()),
        }
    }
}

impl From<Value> for pb::JsonValue {
    fn from(value: Value) -> Self {
        use pb::json_value::Kind;
        let kind = match value {
            Value::Null => Kind::NullValue(true),
            Value::Bool(value) => Kind::BoolValue(value),
            Value::Number(number) => match number.as_i64() {
                Some(value) => Kind::IntValue(value),
                None => Kind::DoubleValue(number.as_f64().unwrap_or(f64::NAN)),
            },
            Value::String(value) => Kind::StringValue(value),
            Value::Array(values) => Kind::ListValue(values.into()),
            // The parser never produces objects; keep their JSON text rather than drop them.
            Value::Object(object) => Kind::StringValue(Value::Object(object).to_string()),
        };
        Self { kind: Some(kind) }
    }
}

impl From<Vec<Value>> for pb::JsonList {
    fn from(values: Vec<Value>) -> Self {
        Self {
            values: values.into_iter().map(Into::into).collect(),
        }
    }
}

impl From<Vec<Option<i64>>> for pb::NullableIntList {
    fn from(values: Vec<Option<i64>>) -> Self {
        Self {
            values: values
                .into_iter()
                .map(|value| pb::NullableInt { value })
                .collect(),
        }
    }
}

impl From<Vec<TalentEntry>> for pb::Talents {
    fn from(entries: Vec<TalentEntry>) -> Self {
        Self {
            entries: entries
                .into_iter()
                .map(|TalentEntry(node_id, entry_id, rank)| pb::Talent {
                    node_id,
                    entry_id,
                    rank,
                })
                .collect(),
        }
    }
}

impl From<Vec<GearSlot>> for pb::Gears {
    fn from(slots: Vec<GearSlot>) -> Self {
        Self {
            slots: slots
                .into_iter()
                .map(
                    |GearSlot(item_id, item_level, enchants, bonus_ids, gems)| pb::Gear {
                        item_id,
                        item_level,
                        enchants: Some(enchants.into()),
                        bonus_ids: Some(bonus_ids.into()),
                        gems: Some(gems.into()),
                    },
                )
                .collect(),
        }
    }
}

impl From<Vec<Vec<Value>>> for pb::ActiveAuras {
    fn from(entries: Vec<Vec<Value>>) -> Self {
        Self {
            entries: entries.into_iter().map(Into::into).collect(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parser::parse_line;

    #[test]
    fn protobuf_event_preserves_typed_data_and_raw_fields() {
        let event = parse_line(
            b"10/28 15:04:05.123  SPELL_DAMAGE,Player-1,Mage,0x511,0,Creature-2,nil,0x10,0,1234,Fireball,4,250,0",
        );
        let bytes = encode_event(7, log_event(event));
        let envelope = pb::Envelope::decode(bytes.as_slice()).unwrap();
        assert_eq!(envelope.seq, 7);
        let Some(pb::envelope::Body::Event(event)) = envelope.body else {
            panic!("expected event body");
        };
        assert_eq!(event.timestamp, "10/28 15:04:05.123");
        assert_eq!(event.fields[1], "Mage");
        assert_eq!(event.null_fields, vec![5]);
        let Some(pb::log_event::Data::SpellDamage(damage)) = event.data else {
            panic!("expected SPELL_DAMAGE");
        };
        assert_eq!(damage.base_amount, Some(250));
        assert_eq!(damage.header.unwrap().source_flags, Some(0x511));
        assert_eq!(damage.spell.unwrap().spell_id.as_deref(), Some("1234"));
    }

    #[test]
    fn commands_decode_from_json_and_protobuf() {
        assert_eq!(
            Command::from_json(r#"{"command":"RESUME","epoch":3,"from_seq":9}"#),
            Ok(Command::Resume {
                epoch: Some(3),
                from_seq: Some(9)
            })
        );
        assert_eq!(
            Command::from_json(r#"{"command":"CLEAR_LOG"}"#),
            Ok(Command::ClearLog)
        );
        assert!(Command::from_json(r#"{"command":"NOPE"}"#).is_err());

        let resume = pb::Command {
            kind: Some(pb::command::Kind::Resume(pb::Resume {
                epoch: 3,
                from_seq: 9,
            })),
        };
        assert_eq!(
            Command::from_protobuf(&resume.encode_to_vec()),
            Ok(Command::Resume {
                epoch: Some(3),
                from_seq: Some(9)
            })
        );
        let clear = pb::Command {
            kind: Some(pb::command::Kind::ClearLog(pb::Empty {})),
        };
        assert_eq!(
            Command::from_protobuf(&clear.encode_to_vec()),
            Ok(Command::ClearLog)
        );
    }

    #[test]
    fn control_json_matches_the_documented_shape() {
        assert_eq!(
            Control::LogCaughtUp.to_json(),
            r#"{"control":"LOG_CAUGHT_UP"}"#
        );
        let gap = Control::ReplayGap(ReplayGapReason::BufferEvicted {
            missed_from: 2,
            available_from: 5,
        });
        let value: Value = serde_json::from_str(&gap.to_json()).unwrap();
        assert_eq!(value["reason"], "BUFFER_EVICTED");
        assert_eq!(value["available_from"], 5);
    }
}
