pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ClearDictate"

include(":core-domain")
include(":core-input-connection")
include(":core-models")
include(":inference-contract")
include(":desktop-inference")
include(":desktop-app")
include(":android-app")
include(":inference-service")
