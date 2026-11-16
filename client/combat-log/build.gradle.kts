plugins {
    alias(libs.plugins.android.kotlin.multiplatform)
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    android {
        namespace = "com.cacaosd.wowtelemetry.combatlog"
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
        }
    }
}
