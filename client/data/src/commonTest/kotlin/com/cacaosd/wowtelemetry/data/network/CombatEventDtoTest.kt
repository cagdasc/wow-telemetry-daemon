package com.cacaosd.wowtelemetry.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CombatEventDtoTest {
    @Test
    fun decodesDaemonPayloadAndMapsRawIds() {
        val dto = CombatEventDto.decode(
            """{"timestamp":"10/28 15:04:05.123","event":"SPELL_DAMAGE","fields":["Player-1","Mage","0x511","0","Creature-2","Target","0x10","0","1234","Fireball"],"source_guid":"Player-1","source_name":"Mage","dest_guid":"Creature-2","dest_name":"Target","spell_id":"1234","spell_name":"Fireball","amount":"250"}""",
        )

        assertEquals("1234", dto.toDomain().spellId)
        assertEquals("250", dto.toDomain().amount)
        assertEquals("Player-1", dto.toDomain().sourceGuid)
        assertEquals("10/28 15:04:05.123", dto.toDomain().timestamp)
    }

    @Test
    fun preservesNullFieldsAndAllowsOmittedOptionalProperties() {
        val dto = CombatEventDto.decode(
            """{"timestamp":"10/28 15:04:05.123","event":"UNIT_DIED","fields":[null,"Creature-2"]}""",
        )

        assertNull(dto.sourceGuid)
        assertNull(dto.spellId)
        assertEquals(listOf(null, "Creature-2"), dto.fields)
    }

    @Test
    fun rejectsMalformedContractValues() {
        assertFailsWith<CombatEventDecodeException> {
            CombatEventDto.decode(
                """{"timestamp":"10/28 15:04:05.123","event":"SPELL_DAMAGE","fields":[123]}""",
            )
        }
    }
}
