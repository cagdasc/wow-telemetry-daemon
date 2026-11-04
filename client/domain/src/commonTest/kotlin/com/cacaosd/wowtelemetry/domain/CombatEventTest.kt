package com.cacaosd.wowtelemetry.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CombatEventTest {
    @Test
    fun projectsTypedSpellCastAndDamageEventsDeterministically() {
        val cast = combatEvent(
            SpellCastSuccessEvent(header(), spell(), advancedBlock = null),
        ).asSpellCast()
        assertEquals("1234", cast?.spellId)
        assertNull(combatEvent(UnitDiedEvent(header())).asSpellCast())

        val damage = combatEvent(
            SpellDamageSupportEvent(
                header = header(),
                spell = spell(),
                advancedBlock = null,
                baseAmount = 250,
                rawAmount = 250,
                overkill = 0,
                school = 4,
                resisted = 0,
                blocked = 0,
                absorbed = 0,
                critical = false,
                glancing = null,
                crushing = null,
                supportPlayerGuid = "Player-3",
            ),
        ).asDamageEvent()
        assertEquals(250L, damage?.amount)
        assertEquals("Creature-2", damage?.destGuid)
        assertNull(
            combatEvent(
                SpellHealEvent(
                    header = header(),
                    spell = spell(),
                    advancedBlock = null,
                    healedToHp = 250,
                    amount = 250,
                    overheal = 0,
                    absorbedToShield = 0,
                    critical = false,
                ),
            ).asDamageEvent(),
        )
    }

    private fun header() = CommonHeader(
        sourceGuid = "Player-1",
        sourceName = "Mage",
        sourceFlags = 0x511,
        sourceRaidFlags = 0,
        destGuid = "Creature-2",
        destName = "Target",
        destFlags = 0x10,
        destRaidFlags = 0,
    )

    private fun spell() = SpellPrefix(
        spellId = "1234",
        spellName = "Fireball",
        spellSchool = 4,
    )

    private fun combatEvent(event: CombatLogEvent) = CombatEvent(
        timestamp = "10/28 15:04:05.123",
        event = event,
        fields = emptyList(),
    )
}
