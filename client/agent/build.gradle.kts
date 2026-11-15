plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":combat-log"))
            implementation(project(":domain"))
            implementation(libs.koin.core)
            implementation(libs.koog.agents)
            implementation(libs.koog.ollama.client)
        }
    }
}
