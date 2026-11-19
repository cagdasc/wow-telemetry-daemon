package com.cacaosd.wowtelemetry.data.session

import com.cacaosd.platform.core.config.PlatformConfiguration
import java.io.File

class SessionDirectoryProvider {
    fun provide(platformConfiguration: PlatformConfiguration): File =
        platformConfiguration.context.filesDir.resolve("sessions")
}
