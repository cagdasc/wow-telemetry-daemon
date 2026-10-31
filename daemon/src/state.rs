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
        *self.event_counts.entry(event.event.to_owned()).or_default() += 1;
        self.last_timestamp = Some(event.timestamp.to_owned());
        match event.event {
            "PLAYER_REGEN_DISABLED" => self.combat_active = true,
            "PLAYER_REGEN_ENABLED" => self.combat_active = false,
            _ => {}
        }
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
}
