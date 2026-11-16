plugins {
    alias(libs.plugins.android.kotlin.multiplatform)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.wire)
}

kotlin {
    jvm()
    android {
        namespace = "com.cacaosd.wowtelemetry.data.network"
        compileSdk = 37
        minSdk = 26

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

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
