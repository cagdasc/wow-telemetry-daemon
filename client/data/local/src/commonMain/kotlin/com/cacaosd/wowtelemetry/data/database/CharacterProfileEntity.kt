package com.cacaosd.wowtelemetry.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "CharacterProfile")
data class CharacterProfileEntity(
    @PrimaryKey
    val id: Int = PROFILE_ID,
    val characterName: String,
    val characterClass: String,
    val specialization: String,
    val realm: String,
    val region: String,
) {
    companion object {
        const val PROFILE_ID = 1
    }
}
