package com.cacaosd.wowtelemetry.domain

data class SpellMetadata(
    val id: String,
    val nameSubtextLang: String?,
    val descriptionLang: String?,
    val auraDescriptionLang: String?,
)

data class SpellNameMetadata(
    val id: String,
    val name: String?,
)

data class NpcMetadata(
    val id: String,
    val name: String?,
)

interface SpellMetadataRepository {
    suspend fun findSpellNameById(id: String): SpellNameMetadata?
    suspend fun findSpellById(id: String): SpellMetadata?
}

interface NpcMetadataRepository {
    suspend fun findById(id: String): NpcMetadata?
}
