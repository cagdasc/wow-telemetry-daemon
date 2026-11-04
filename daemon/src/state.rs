use std::collections::HashMap;

use crate::parser::CombatEvent;

#[derive(Debug, Default)]
pub struct State {
    event_counts: HashMap<String, u64>,
    combat_active: bool,
    last_timestamp: Option<String>,
}

impl State {
    pub fn record(&mut self, event: &CombatEvent<'_>) {
        let event_name = event.typed_event.event_name();
        if event_name == "COMBAT_LOG_VERSION" {
            self.reset();
        }
        *self.event_counts.entry(event_name.to_owned()).or_default() += 1;
        self.last_timestamp = Some(event.timestamp.to_string());
        match event_name {
            "PLAYER_REGEN_DISABLED" => self.combat_active = true,
            "PLAYER_REGEN_ENABLED" => self.combat_active = false,
            _ => {}
        }
    }

    fn reset(&mut self) {
        self.event_counts.clear();
        self.combat_active = false;
        self.last_timestamp = None;
    }
}

#[cfg(test)]
mod tests {
    use super::State;
    use crate::parser::parse_line;

    #[test]
    fn aggregates_event_counts_and_combat_state() {
        let mut state = State::default();
        let started = parse_line(b"10/28 15:04:05.123  PLAYER_REGEN_DISABLED")
            .unwrap()
            .unwrap();
        let damage = parse_line(
            b"10/28 15:04:06.123  SWING_DAMAGE,Player-1,Mage,0x511,0,Creature-2,Target,0x10,0,500,0",
        )
        .unwrap()
        .unwrap();
        let ended = parse_line(b"10/28 15:04:07.123  PLAYER_REGEN_ENABLED")
            .unwrap()
            .unwrap();

        state.record(&started);
        assert!(state.combat_active);
        state.record(&damage);
        state.record(&ended);
        assert!(!state.combat_active);
        assert_eq!(state.event_counts.values().sum::<u64>(), 3);
        assert_eq!(state.last_timestamp.as_deref(), Some("10/28 15:04:07.123"));
    }

    #[test]
    fn combat_log_version_resets_accumulated_state() {
        let mut state = State::default();
        let started = parse_line(b"10/28 15:04:05.123  PLAYER_REGEN_DISABLED")
            .unwrap()
            .unwrap();
        let version = parse_line(
            b"10/28 15:04:06.123  COMBAT_LOG_VERSION,22,ADVANCED_LOG_ENABLED,1,BUILD_VERSION,12.0.0,PROJECT_ID,1",
        )
            .unwrap()
            .unwrap();

        state.record(&started);
        state.record(&version);

        assert_eq!(state.event_counts.len(), 1);
        assert_eq!(state.event_counts.get("COMBAT_LOG_VERSION"), Some(&1));
        assert!(!state.combat_active);
        assert_eq!(state.last_timestamp.as_deref(), Some("10/28 15:04:06.123"));
    }
}
