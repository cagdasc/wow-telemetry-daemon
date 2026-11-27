plugins {
    alias(libs.plugins.android.kotlin.multiplatform)
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    android {
        namespace = "com.cacaosd.wowtelemetry.agent"
        compileSdk = 37
        minSdk = 26

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":combat-log"))
            implementation(project(":domain"))
            implementation(libs.koin.core)
            implementation(libs.ktor.client.core)
            implementation(libs.koog.agents)
            implementation(libs.koog.ollama.client)
            implementation(libs.koog.http.client.ktor)
        }
    }
}
