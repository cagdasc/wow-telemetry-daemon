package com.cacaosd.wowtelemetry.data.session

import com.cacaosd.platform.core.config.PlatformConfiguration
import java.io.File

actual fun provideSessionDirectory(platformConfiguration: PlatformConfiguration): File =
    SessionDirectoryProvider().provide(platformConfiguration)
