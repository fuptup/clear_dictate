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
    implementation(project(":inference-contract"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlin.coroutines.core)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
