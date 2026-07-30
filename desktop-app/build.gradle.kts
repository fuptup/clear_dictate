plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-models"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlin.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "com.cleardictate.desktop.ClearDictateDesktopApplicationKt"

        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)
            packageName = "ClearDictate"
            packageVersion = "0.1.0"
            description = "Private, offline dictation for Windows"
            vendor = "ClearDictate"
        }
    }
}
