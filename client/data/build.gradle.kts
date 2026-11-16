plugins {
    alias(libs.plugins.android.kotlin.multiplatform)
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    android {
        namespace = "com.cacaosd.wowtelemetry.data"
        compileSdk = 37
        minSdk = 26

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":data:network"))
            api(project(":data:local"))
            implementation(libs.koin.core)
        }
    }
}
