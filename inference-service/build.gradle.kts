plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.cleardictate.inference.service"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-models"))
    implementation(project(":inference-contract"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlin.coroutines.core)
    debugImplementation(files(rootProject.file("third_party/moonshine/artifacts/moonshine-voice-0.1.0-cleardictate-debug.aar")))
    debugImplementation(files(rootProject.file("third_party/llama.cpp/artifacts/llama-android-b10189-cleardictate-debug.aar")))
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime:2.11.2")

    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
