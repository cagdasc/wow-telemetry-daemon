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

data class CharacterProfile(
    val characterName: String = "",
    val characterClass: String = "",
    val specialization: String = "",
    val realm: String = "",
    val region: String = "",
)

interface SpellMetadataRepository {
    suspend fun findSpellNameById(id: String): SpellNameMetadata?
    suspend fun findSpellById(id: String): SpellMetadata?
}

interface NpcMetadataRepository {
    suspend fun findById(id: String): NpcMetadata?
}

interface CharacterProfileRepository {
    suspend fun getProfile(): CharacterProfile?
    suspend fun saveProfile(profile: CharacterProfile)
}
