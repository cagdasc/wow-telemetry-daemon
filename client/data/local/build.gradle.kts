plugins {
    alias(libs.plugins.android.kotlin.multiplatform)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    jvm()
    android {
        namespace = "com.cacaosd.wowtelemetry.data.local"
        compileSdk = 37
        minSdk = 26

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
            implementation(libs.datastore.core)
            implementation(libs.datastore.preferences.core)
            api(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)

            implementation(libs.koin.core)

            implementation(libs.platform.coroutines)
            implementation(libs.platform.coroutines.di)
            implementation(libs.platform.core)
            implementation(libs.platform.core.di)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        androidMain.dependencies {
            implementation(libs.android.core.ktx)
        }
    }
}

dependencies {
    add("kspJvm", libs.androidx.room.compiler)
    add("kspAndroid", libs.androidx.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}
