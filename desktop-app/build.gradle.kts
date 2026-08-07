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
    implementation(project(":desktop-inference"))
    implementation(project(":inference-contract"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlin.coroutines.swing)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlin.coroutines.test)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.cleardictate.desktop.ClearDictateDesktopApplicationKt"

        nativeDistributions {
            packageName = "ClearDictate"
            packageVersion = "0.1.0"
            modules("jdk.httpserver")
        }
    }
}
