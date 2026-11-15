plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.wire)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.koin.core)
            api(libs.wire.runtime)
        }
    }
}

wire {
    sourcePath {
        srcDir("src/commonMain/proto")
    }
    kotlin {}
}
