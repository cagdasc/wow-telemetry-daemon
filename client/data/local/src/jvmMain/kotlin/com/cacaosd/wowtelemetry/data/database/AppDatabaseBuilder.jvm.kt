package com.cacaosd.wowtelemetry.data.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.cacaosd.platform.core.config.PlatformConfiguration
import com.cacaosd.wowtelemetry.data.getHomeDirectory
import kotlinx.coroutines.Dispatchers
import java.io.File

actual fun createAppDatabase(platformConfiguration: PlatformConfiguration): AppDatabase {
    val dbFile = File(getHomeDirectory(), AppDatabase.DB_NAME)
    return Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}

actual fun createCharacterProfileDatabase(platformConfiguration: PlatformConfiguration): CharacterProfileDatabase {
    val dbFile = File(getHomeDirectory(), CharacterProfileDatabase.DB_NAME)

    return Room.databaseBuilder<CharacterProfileDatabase>(name = dbFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
