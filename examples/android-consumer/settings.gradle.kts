pluginManagement { repositories { mavenCentral(); google(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal { content { includeGroup("de.fahrertagebuch") } }
        mavenCentral()
        google()
    }
}
rootProject.name = "independent-driver-card-consumer"
include(":app")
