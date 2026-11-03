package com.cacaosd.wowtelemetry.data.database

import com.cacaosd.wowtelemetry.domain.*

class RoomSpellMetadataRepository(
    private val dao: SpellNameDao,
    private val spellDao: SpellDao,
) : SpellMetadataRepository {

    override suspend fun findSpellNameById(id: String): SpellNameMetadata? =
        id.toLongOrNull()?.let { numericId ->
            dao.findById(numericId)?.let { SpellNameMetadata(id = it.id.toString(), name = it.name) }
        }

    override suspend fun findSpellById(id: String): SpellMetadata? {
        return id.toLongOrNull()?.let { numericId ->
            spellDao.findById(numericId)?.let {
                SpellMetadata(
                    id = it.id.toString(),
                    nameSubtextLang = it.nameSubtextLang,
                    descriptionLang = it.descriptionLang,
                    auraDescriptionLang = it.auraDescriptionLang
                )
            }
        }
    }
}

class RoomNpcMetadataRepository(
    private val dao: CreatureDao,
) : NpcMetadataRepository {
    override suspend fun findById(id: String): NpcMetadata? =
        id.toLongOrNull()?.let { numericId ->
            dao.findById(numericId)?.let { NpcMetadata(id = it.id.toString(), name = it.nameLang) }
        }
}
