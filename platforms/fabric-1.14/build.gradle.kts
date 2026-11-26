plugins {
    java
    id("net.fabricmc.fabric-loom-remap")
}

version = rootProject.version
base.archivesName.set("Allium-Fabric-1.14")
java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))
sourceSets.main { java.srcDirs("../portable/src/main/java", "../brigadier/src/main/java") }
loom { serverOnlyMinecraftJar() }

val commandsApi = "net.fabricmc.fabric-api:fabric-commands-v0:0.1.1+591e97ae42"
val apiBase = "net.fabricmc.fabric-api:fabric-api-base:0.1.0+2983bc0442"

dependencies {
    minecraft("com.mojang:minecraft:1.14")
    // This early Yarn release's v1 artifact has the original, correctly ordered namespaces.
    mappings("net.fabricmc:yarn:1.14+build.21")
    modImplementation("net.fabricmc:fabric-loader:0.16.14")
    modImplementation(commandsApi)
    // Minecraft 1.14's Fabric API modules were distributed separately. Keep this install one JAR.
    include(commandsApi)
    include(apiBase)
}

tasks.withType<JavaCompile>().configureEach { options.release.set(8) }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}

tasks.register<Copy>("stagePlatformJar") {
    from(tasks.remapJar.flatMap { it.archiveFile })
    into(rootProject.layout.buildDirectory.dir("platforms"))
}
