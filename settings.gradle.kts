pluginManagement { repositories { mavenCentral(); google(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral(); google() }
}
rootProject.name = "driver-card-library"
include(":card-core")
if (!providers.gradleProperty("coreOnly").isPresent) include(":card-usb-android")
