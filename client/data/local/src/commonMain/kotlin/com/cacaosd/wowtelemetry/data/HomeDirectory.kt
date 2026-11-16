package com.cacaosd.wowtelemetry.data

fun getHomeDirectory(): String {
    val homeDir = System.getProperty("user.home")
    val configDirectory = when {
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) ->
            System.getenv("APPDATA") ?: "$homeDir/AppData/Roaming"

        else -> System.getenv("XDG_CONFIG_HOME") ?: "$homeDir/.config"
    }
    return "$configDirectory/WowTelemetry"
}