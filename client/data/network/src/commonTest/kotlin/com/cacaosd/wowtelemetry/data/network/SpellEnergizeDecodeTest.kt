package com.cacaosd.wowtelemetry.data.network

import com.cacaosd.wowtelemetry.data.model.CombatEventDto
import com.cacaosd.wowtelemetry.domain.SpellEnergizeEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SpellEnergizeDecodeTest {
    @Test
    fun decodesDecimalEnergizeAmounts() {
        val dto = CombatEventDto.decode(
            """{"timestamp":"11/10/2026 00:33:56.7940","event":{"type":"SPELL_ENERGIZE","data":{"header":{},"spell":{},"amount":0.0,"over_energize":3.0,"power_type":1,"max_power":1000}},"fields":[]}""",
        )

        val event = assertIs<SpellEnergizeEvent>(dto.event)
        assertEquals(0.0, event.amount)
        assertEquals(3.0, event.overEnergize)
        assertEquals(1L, event.powerType)
    }
}
