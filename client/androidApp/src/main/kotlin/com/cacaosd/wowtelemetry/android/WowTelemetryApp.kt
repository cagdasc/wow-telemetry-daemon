package com.cacaosd.wowtelemetry.android

import android.app.Application
import com.cacaosd.platform.core.config.PlatformConfiguration
import com.cacaosd.platform.core.di.platformConfiguration
import com.cacaosd.platform.coroutines.di.platformCoroutines
import com.cacaosd.wowtelemetry.client.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.component.KoinComponent
import org.koin.core.context.startKoin

class WowTelemetryApp : Application(), KoinComponent {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            allowOverride(false)
            androidContext(this@WowTelemetryApp)
            androidLogger()
            platformCoroutines()
            platformConfiguration(PlatformConfiguration(this@WowTelemetryApp))
            modules(appModule())
        }
    }
}
