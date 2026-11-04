plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":data:network"))
            api(project(":data:local"))
            implementation(libs.koin.core)
        }
    }
}
