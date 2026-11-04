plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
            implementation(libs.kotlinx.coroutines.core)
            api(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

dependencies {
    add("kspJvm", libs.androidx.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}
