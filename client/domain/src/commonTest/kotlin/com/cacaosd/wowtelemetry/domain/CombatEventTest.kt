package com.cacaosd.wowtelemetry.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CombatEventTest {
    @Test
    fun projectsSpellCastAndDamageEventsDeterministically() {
        val cast = event("SPELL_CAST_SUCCESS").asSpellCast()
        assertEquals("1234", cast?.spellId)
        assertNull(event("UNIT_DIED").asSpellCast())

        val damage = event("SPELL_DAMAGE_SUPPORT").asDamageEvent()
        assertEquals("250", damage?.amount)
        assertEquals("Creature-2", damage?.destGuid)
    }

    private fun event(name: String) = CombatEvent(
        timestamp = "10/28 15:04:05.123",
        event = name,
        fields = emptyList(),
        sourceGuid = "Player-1",
        sourceName = "Mage",
        destGuid = "Creature-2",
        destName = "Target",
        spellId = "1234",
        spellName = "Fireball",
        amount = "250",
    )
}
