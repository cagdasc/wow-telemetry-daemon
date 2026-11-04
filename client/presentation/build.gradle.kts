plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":agent"))
            implementation(project(":combat-log"))
            implementation(project(":data"))
            implementation(project(":domain"))
            api(libs.androidx.lifecycle.viewmodel)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
