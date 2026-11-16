package com.cacaosd.wowtelemetry.data.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(
    entities = [CharacterProfileEntity::class],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(CharacterProfileDatabaseConstructor::class)
abstract class CharacterProfileDatabase : RoomDatabase() {
    abstract fun characterProfileDao(): CharacterProfileDao

    companion object {
        const val DB_NAME = "wow-telemetry-profile"
    }
}

expect object CharacterProfileDatabaseConstructor :
    RoomDatabaseConstructor<CharacterProfileDatabase> {
    override fun initialize(): CharacterProfileDatabase
}
