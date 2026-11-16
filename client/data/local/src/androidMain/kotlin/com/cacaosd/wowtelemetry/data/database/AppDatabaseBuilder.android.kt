package com.cacaosd.wowtelemetry.data.database

import androidx.room.Room
import com.cacaosd.platform.core.config.PlatformConfiguration

actual fun createAppDatabase(platformConfiguration: PlatformConfiguration): AppDatabase {
    return Room.databaseBuilder(platformConfiguration.context, AppDatabase::class.java, AppDatabase.DB_NAME)
        .fallbackToDestructiveMigration(false)
        .build()
}

actual fun createCharacterProfileDatabase(platformConfiguration: PlatformConfiguration): CharacterProfileDatabase {
    return Room.databaseBuilder(
        platformConfiguration.context,
        CharacterProfileDatabase::class.java,
        CharacterProfileDatabase.DB_NAME
    )
        .fallbackToDestructiveMigration(false)
        .build()
}