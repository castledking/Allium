pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "Allium"

include("allium-core")
include("allium-api")

include("addons:curios")
include("addons:spawnercraft")
