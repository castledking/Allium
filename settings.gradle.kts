pluginManagement {
    plugins {
        id("net.fabricmc.fabric-loom") version "1.17.21"
        id("net.fabricmc.fabric-loom-remap") version "1.17.21"
        id("net.neoforged.moddev") version "2.0.148"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/") {
            content { includeGroupAndSubgroups("net.fabricmc") }
        }
        maven("https://maven.neoforged.net/releases/") {
            content { includeGroupAndSubgroups("net.neoforged") }
        }
    }
}

rootProject.name = "Allium"

include("allium-core")
include("allium-api")
include("allium-legacy")
project(":allium-legacy").projectDir = file("platforms/bukkit-legacy")

include("addons:curios")
include("addons:spawnercraft")
include("addons:crops")
include("addons:partylocator")
include("addons:ores")
include("addons:menus")

if (providers.gradleProperty("modPlatforms").orNull == "true") {
    for (platform in listOf("fabric", "fabric-1.14", "fabric-1.20.1", "fabric-1.21.11", "fabric-universal", "neoforge")) {
        include("allium-$platform")
        project(":allium-$platform").projectDir = file("platforms/$platform")
    }
}
