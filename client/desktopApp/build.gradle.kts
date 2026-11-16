import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":presentation"))
    implementation(project(":data"))

    implementation(libs.ktor.client.core)
    implementation(libs.koin.core)
    implementation(libs.koin.compose)
    implementation(libs.koin.core.jvm)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.coroutines.test)
    implementation(libs.kotlinx.coroutines.jvm)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.uiToolingPreview)
    implementation(compose.desktop.currentOs)

    implementation(libs.platform.coroutines)
    implementation(libs.platform.coroutines.di)
    implementation(libs.platform.core)
    implementation(libs.platform.core.di)
}

compose.desktop {
    application {
        mainClass = "com.cacaosd.wowtelemetry.client.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.cacaosd.wowtelemetry.client"
            packageVersion = "1.0.0"
        }
    }
}
