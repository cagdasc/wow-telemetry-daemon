package com.cacaosd.wowtelemetry.data.session

import com.cacaosd.platform.core.config.PlatformConfiguration
import com.cacaosd.wowtelemetry.data.getHomeDirectory
import java.io.File

actual fun provideSessionDirectory(platformConfiguration: PlatformConfiguration): File =
    File(getHomeDirectory(), "sessions")
