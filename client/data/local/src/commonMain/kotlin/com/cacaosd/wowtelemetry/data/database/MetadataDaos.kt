package com.cacaosd.wowtelemetry.data.database

import androidx.room.Dao
import androidx.room.Query

@Dao
interface SpellDao {
    @Query("SELECT * FROM Spell WHERE ID = :id LIMIT 1")
    suspend fun findById(id: Long): Spell?
}

@Dao
interface SpellNameDao {
    @Query("SELECT * FROM SpellName WHERE ID = :id LIMIT 1")
    suspend fun findById(id: Long): SpellName?
}

@Dao
interface CreatureDao {
    @Query("SELECT * FROM Creature WHERE ID = :id LIMIT 1")
    suspend fun findById(id: Long): Creature?
}
