package com.cacaosd.wowtelemetry.data.database

import com.cacaosd.platform.core.config.PlatformConfiguration

expect fun createAppDatabase(platformConfiguration: PlatformConfiguration): AppDatabase

expect fun createCharacterProfileDatabase(platformConfiguration: PlatformConfiguration): CharacterProfileDatabase
