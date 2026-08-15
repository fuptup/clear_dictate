plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":inference-contract"))
    implementation(libs.jna)
    implementation(libs.kotlin.coroutines.core)

    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()

    listOf(
        "clearDictate.workerExecutable",
        "clearDictate.textModel",
        "clearDictate.audioCaptureWorkerExecutable",
        "clearDictate.wslExecutable",
        "clearDictate.wslDistribution",
        "clearDictate.asrWorkerScript",
        "clearDictate.asrModelLock",
        "clearDictate.asrWaveFixture"
    ).forEach { propertyName ->
        providers.systemProperty(propertyName).orNull?.let { propertyValue ->
            systemProperty(propertyName, propertyValue)
        }
    }
}

val realWorkerIntegrationTest by tasks.registering(Test::class) {
    val workerExecutableProperty = System.getProperty("clearDictate.workerExecutable")
    val textModelProperty = System.getProperty("clearDictate.textModel")

    description = "Runs the required Kotlin-to-native integration against the pinned local Qwen model."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("com.cleardictate.desktop.inference.WindowsTextWorkerClientIntegrationTest")
    }
    reports.html.outputLocation = layout.buildDirectory.dir("reports/tests/real-worker-integration")
    reports.junitXml.outputLocation = layout.buildDirectory.dir("test-results/real-worker-integration")

    if (workerExecutableProperty != null)
    {
        systemProperty("clearDictate.workerExecutable", workerExecutableProperty)
    }
    if (textModelProperty != null)
    {
        systemProperty("clearDictate.textModel", textModelProperty)
    }

    doFirst {
        check(workerExecutableProperty != null)
        {
            "clearDictate.workerExecutable must be provided for the real worker integration test."
        }
        check(textModelProperty != null)
        {
            "clearDictate.textModel must be provided for the real worker integration test."
        }
    }
}
