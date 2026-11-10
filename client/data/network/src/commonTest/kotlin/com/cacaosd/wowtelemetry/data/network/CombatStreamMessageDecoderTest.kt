package com.cacaosd.wowtelemetry.data.network

import com.cacaosd.wowtelemetry.data.mapper.CombatEventMapper
import com.cacaosd.wowtelemetry.data.mapper.CombatStreamMessageDecoder
import com.cacaosd.wowtelemetry.data.model.CombatEventDecodeException
import com.cacaosd.wowtelemetry.domain.CombatStreamMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CombatStreamMessageDecoderTest {
    private val decoder = CombatStreamMessageDecoder(CombatEventMapper())

    @Test
    fun decodesCaughtUpControlMessage() {
        assertEquals(
            CombatStreamMessage.CaughtUp,
            decoder.decode("""{"control":"LOG_CAUGHT_UP"}"""),
        )
    }

    @Test
    fun preservesExistingCombatEventFrames() {
        val message = decoder.decode(
            """{"timestamp":"10/28 15:04:05.123","event":{"type":"UNIT_DIED","data":{"header":{"dest_guid":"Creature-2","dest_name":null,"dest_flags":16}}},"fields":[null,"Creature-2"]}""",
        )

        assertEquals(
            "10/28 15:04:05.123",
            (message as CombatStreamMessage.Event).combatEvent.timestamp,
        )
    }

    @Test
    fun rejectsUnsupportedControlMessages() {
        assertFailsWith<CombatEventDecodeException> {
            decoder.decode("""{"control":"UNKNOWN"}""")
        }
    }
}
