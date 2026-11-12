package com.cacaosd.wowtelemetry.data.mapper

import com.cacaosd.wowtelemetry.data.proto.ActiveAuras
import com.cacaosd.wowtelemetry.data.proto.Gears
import com.cacaosd.wowtelemetry.data.proto.JsonValue
import com.cacaosd.wowtelemetry.data.proto.NullableIntList
import com.cacaosd.wowtelemetry.data.proto.Talents
import com.cacaosd.wowtelemetry.domain.ActiveAura
import com.cacaosd.wowtelemetry.domain.GearSlot
import com.cacaosd.wowtelemetry.domain.TalentEntry

internal fun NullableIntList.toLongs(): List<Long?> = values.map { it.value_ }

internal fun Talents.toTalentEntries(): List<TalentEntry> =
    entries.map { TalentEntry(nodeId = it.node_id, entryId = it.entry_id, rank = it.rank) }

internal fun Gears.toGearSlots(): List<GearSlot> =
    slots.map { slot ->
        GearSlot(
            itemId = slot.item_id,
            itemLevel = slot.item_level,
            enchants = slot.enchants?.toLongs().orEmpty(),
            bonusIds = slot.bonus_ids?.toLongs().orEmpty(),
            gems = slot.gems?.toLongs().orEmpty(),
        )
    }

internal fun ActiveAuras.toActiveAuras(): List<ActiveAura> =
    entries.map { entry ->
        val items = entry.values.map { it.asText() }
        ActiveAura(
            sourceGuid = items.getOrNull(0),
            spellId = items.getOrNull(1),
            remaining = items.drop(2),
        )
    }

private fun JsonValue.asText(): String? = when {
    null_value != null -> null
    bool_value != null -> bool_value.toString()
    int_value != null -> int_value.toString()
    double_value != null -> double_value.toString()
    string_value != null -> string_value
    else -> null
}
