package com.cacaosd.wowtelemetry.data.network

import com.cacaosd.wowtelemetry.domain.*
import kotlin.test.*

class CombatEventDtoTest {
    @Test
    fun decodesTaggedSpellDamageAndPreservesRawIds() {
        val dto = CombatEventDto.decode(
            """{"timestamp":"10/28 15:04:05.123","event":{"type":"SPELL_DAMAGE","data":{"header":{"source_guid":"Player-1","source_name":"Mage","source_flags":1297,"source_raid_flags":0,"dest_guid":"Creature-2","dest_name":"Target","dest_flags":16,"dest_raid_flags":0},"spell":{"spell_id":"001234","spell_name":"Fireball","spell_school":4},"base_amount":250,"raw_amount":250,"overkill":0,"school":4,"resisted":0,"blocked":0,"absorbed":0,"critical":false,"glancing":null,"crushing":null,"ability_hint":"ST"}},"fields":["Player-1","Mage","0x511","0","Creature-2","Target","0x10","0","001234","Fireball"]}""",
        )

        val event = assertIs<SpellDamageEvent>(dto.toDomain().event)
        assertEquals("001234", event.spell.spellId)
        assertEquals(250L, event.baseAmount)
        assertEquals("Player-1", event.header.sourceGuid)
        assertEquals(
            listOf(
                "Player-1",
                "Mage",
                "0x511",
                "0",
                "Creature-2",
                "Target",
                "0x10",
                "0",
                "001234",
                "Fireball"
            ), dto.fields
        )
    }

    @Test
    fun decodesVersionEventWithTypedNumericAndBooleanFields() {
        val event = assertIs<CombatLogVersionEvent>(
            CombatEventDto.decode(
                """{"timestamp":"10/29 20:15:01.125","event":{"type":"COMBAT_LOG_VERSION","data":{"format_version":22,"advanced_log_enabled":true,"build_version":"12.0.0","project_id":"001"}},"fields":["22","1","12.0.0","001"]}""",
            ).toDomain().event,
        )

        assertEquals(22L, event.formatVersion)
        assertEquals(true, event.advancedLogEnabled)
        assertEquals("001", event.projectId)
    }

    @Test
    fun decodesCombatantCollectionsIntoTypedModels() {
        val event = assertIs<CombatantInfoEvent>(
            CombatEventDto.decode(
                """{"timestamp":"10/29 20:15:01.125","event":{"type":"COMBATANT_INFO","data":{"player_guid":"Player-1","talents":[[101,201,2]],"pvp_talents":[4,5],"gear":[[6,700,[8],[9,10],[11]]],"active_auras":[["Player-1",1234]]}},"fields":[]}""",
            ).toDomain().event,
        )

        assertEquals(TalentEntry(101, 201, 2), event.talents?.single())
        assertEquals(listOf(4L, 5L), event.pvpTalents)
        assertEquals(GearSlot(6, 700, listOf(8), listOf(9, 10), listOf(11)), event.gear?.single())
        assertEquals(
            ActiveAura(sourceGuid = "Player-1", spellId = "1234", remaining = emptyList()),
            event.activeAuras?.single()
        )
    }

    @Test
    fun preservesNullFieldsAndAllowsOmittedOptionalData() {
        val dto = CombatEventDto.decode(
            """{"timestamp":"10/28 15:04:05.123","event":{"type":"UNIT_DIED","data":{"header":{"dest_guid":"Creature-2","dest_name":null,"dest_flags":16}}},"fields":[null,"Creature-2"]}""",
        )

        val event = assertIs<UnitDiedEvent>(dto.toDomain().event)
        assertEquals("Creature-2", event.header.destGuid)
        assertNull(event.header.destName)
        assertEquals(listOf(null, "Creature-2"), dto.fields)
    }

    @Test
    fun decodesUnrecognizedEventsAndPreservesFields() {
        val dto = CombatEventDto.decode(
            """{"timestamp":"10/28 15:04:05.123","event":{"type":"UNRECOGNIZED_EVENT","data":{"event_name":"SPELL_NOT_IN_SPEC"}},"fields":["Player-1","Mage",null]}""",
        )

        assertEquals(UnrecognizedEventEvent("SPELL_NOT_IN_SPEC"), dto.event)
        assertEquals(listOf("Player-1", "Mage", null), dto.fields)
    }

    @Test
    fun decodesMalformedEventsWithExactRawBytes() {
        val dto = CombatEventDto.decode(
            """{"timestamp":"10/28 15:04:05.123","event":{"type":"MALFORMED_EVENT","data":{"reason":"unterminated quoted field","raw_bytes":[49,48,44,34,120]}},"fields":[]}""",
        )

        assertEquals(
            MalformedEventEvent("unterminated quoted field", listOf(49, 48, 44, 34, 120)),
            dto.event,
        )

        val hexDto = CombatEventDto.decode(
            """{"timestamp":"10/28 15:04:05.123","event":{"type":"MALFORMED_EVENT","data":{"reason":"unterminated quoted field","raw_bytes":"31302c2278"}},"fields":[]}""",
        )

        assertEquals(
            MalformedEventEvent("unterminated quoted field", listOf(49, 48, 44, 34, 120)),
            hexDto.event,
        )
    }

    @Test
    fun rejectsMalformedTaggedEventValues() {
        assertFailsWith<CombatEventDecodeException> {
            CombatEventDto.decode(
                """{"timestamp":"10/28 15:04:05.123","event":"SPELL_DAMAGE","fields":[]}""",
            )
        }
        assertFailsWith<CombatEventDecodeException> {
            CombatEventDto.decode(
                """{"timestamp":"10/28 15:04:05.123","event":{"type":"NOT_IN_SPEC","data":{}},"fields":[]}""",
            )
        }
        assertFailsWith<CombatEventDecodeException> {
            CombatEventDto.decode(
                """{"timestamp":"10/28 15:04:05.123","event":{"type":"SPELL_DAMAGE","data":{"header":{},"spell":{"spell_id":1234}}},"fields":[]}""",
            )
        }
    }
}
