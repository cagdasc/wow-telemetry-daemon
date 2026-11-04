plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
