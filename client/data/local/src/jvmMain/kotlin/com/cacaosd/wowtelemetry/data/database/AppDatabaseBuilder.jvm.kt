package com.cacaosd.wowtelemetry.data.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

actual fun createAppDatabase(databasePath: String): AppDatabase =
    Room.databaseBuilder<AppDatabase>(name = databasePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

actual fun createCharacterProfileDatabase(databasePath: String): CharacterProfileDatabase =
    Room.databaseBuilder<CharacterProfileDatabase>(name = databasePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
