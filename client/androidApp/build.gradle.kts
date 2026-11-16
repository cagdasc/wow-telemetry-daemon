plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.cacaosd.wowtelemetry.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cacaosd.wowtelemetry.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":presentation"))
    implementation(project(":data"))

    implementation(libs.androidx.activityCompose)
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.uiToolingPreview)

    implementation(libs.platform.coroutines)
    implementation(libs.platform.coroutines.di)
    implementation(libs.platform.core)
    implementation(libs.platform.core.di)
}
