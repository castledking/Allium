plugins {
    java
    id("net.fabricmc.fabric-loom")
}

val minecraftVersion = providers.gradleProperty("modMinecraftVersion").get()
val supportedVersions = providers.gradleProperty("modMinecraftVersions").get().split(',')
val loaderVersion = providers.gradleProperty("fabricLoaderVersion").get()
val fabricApiVersion = providers.gradleProperty("fabricApiVersion").get()
val javaVersion = providers.gradleProperty("modJavaVersion").get().toInt()

version = rootProject.version
base.archivesName.set("Allium-Fabric")
java.toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
sourceSets.main { java.srcDirs("../minecraft/src/main/java", "../portable/src/main/java", "../brigadier/src/main/java", "../minecraft-homes/src/main/java") }

loom { serverOnlyMinecraftJar() }

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation(fabricApi.module("fabric-command-api-v2", fabricApiVersion))
    implementation(project(":allium-core"))
    include(project(":allium-core"))
}

tasks.processResources {
    val minecraft = supportedVersions.joinToString("\", \"")
    val metadata = mapOf("version" to project.version, "minecraftVersions" to minecraft,
        "loaderVersion" to loaderVersion, "javaVersion" to javaVersion)
    inputs.properties(metadata)
    filesMatching("fabric.mod.json") { expand(metadata) }
}

