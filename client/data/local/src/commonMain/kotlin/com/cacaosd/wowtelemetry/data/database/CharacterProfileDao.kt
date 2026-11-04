package com.cacaosd.wowtelemetry.data.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface CharacterProfileDao {
    @Query("SELECT * FROM CharacterProfile WHERE id = 1 LIMIT 1")
    suspend fun getProfile(): CharacterProfileEntity?

    @Upsert
    suspend fun saveProfile(profile: CharacterProfileEntity)
}
