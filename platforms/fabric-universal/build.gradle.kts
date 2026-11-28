import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins { java }

version = rootProject.version
base.archivesName.set("Allium-Fabric")
java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))
tasks.withType<JavaCompile>().configureEach { options.release.set(8) }

val loaderVersion = providers.gradleProperty("fabricLoaderVersion").get()
repositories { maven("https://maven.fabricmc.net/") }
dependencies { compileOnly("net.fabricmc:fabric-loader:$loaderVersion") }

fun adapterJar(projectPath: String, task: String): Provider<RegularFile> {
    return evaluationDependsOn(projectPath).tasks.named<AbstractArchiveTask>(task).flatMap { it.archiveFile }
}

val legacyJar = adapterJar(":allium-fabric-1.14", "remapJar")
val fabric1201Jar = adapterJar(":allium-fabric-1.20.1", "remapJar")
val fabric12111Jar = adapterJar(":allium-fabric-1.21.11", "remapJar")
val modernJar = adapterJar(":allium-fabric", "jar")
val adapters = listOf(legacyJar, fabric1201Jar, fabric12111Jar, modernJar)
val modVersion = version.toString()
val bootstrap = "codes.castled.allium.fabric.bootstrap.FabricBootstrap"

val prepareAdapters by tasks.registering {
    val output = layout.buildDirectory.dir("generated/adapters")
    inputs.files(adapters)
    inputs.property("loaderVersion", loaderVersion)
    inputs.property("modVersion", modVersion)
    inputs.property("bootstrap", bootstrap)
    outputs.dir(output)

    doLast {
        val directory = output.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        val versions = linkedMapOf<String, List<String>>()
        val nested = mutableListOf<Map<String, String>>()

        for (adapter in adapters) {
            ZipFile(adapter.get().asFile).use { zip ->
                val metadata = zip.getInputStream(zip.getEntry("fabric.mod.json")).reader().use {
                    JsonSlurper().parse(it) as Map<*, *>
                }
                val dependencies = metadata["depends"] as Map<*, *>
                val entrypoint = ((metadata["entrypoints"] as Map<*, *>)["server"] as List<*>).single().toString()
                val commandApi = dependencies.keys.single { it.toString().startsWith("fabric-command") }.toString()
                val supported = dependencies["minecraft"]
                for (game in (supported as? List<*> ?: listOf(supported))) {
                    check(versions.put(game.toString(), listOf(entrypoint, commandApi)) == null) {
                        "Multiple Allium Fabric adapters claim Minecraft $game"
                    }
                }

                for (jar in metadata["jars"] as? List<*> ?: emptyList<Any>()) {
                    val path = (jar as Map<*, *>)["file"] as String
                    val target = directory.resolve(path)
                    target.parentFile.mkdirs()
                    check(!target.exists()) { "Duplicate Allium Fabric dependency $path" }
                    zip.getInputStream(zip.getEntry(path)).use { source ->
                        if (adapter == legacyJar) {
                            // These original 1.14 modules declare no game version and carry old mixins.
                            ZipInputStream(source).use { input ->
                                ZipOutputStream(target.outputStream()).use { outputZip ->
                                    while (true) {
                                        val entry = input.nextEntry ?: break
                                        outputZip.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                                        if (entry.name == "fabric.mod.json") {
                                            @Suppress("UNCHECKED_CAST")
                                            val module = JsonSlurper().parseText(input.readBytes().decodeToString())
                                                    as MutableMap<String, Any?>
                                            val requires = module["depends"] as? Map<*, *> ?: emptyMap<String, String>()
                                            module["depends"] = requires + ("minecraft" to ">=1.14 <1.15-")
                                            outputZip.write(JsonOutput.toJson(module).toByteArray())
                                        } else {
                                            input.copyTo(outputZip)
                                        }
                                        outputZip.closeEntry()
                                    }
                                }
                            }
                        } else {
                            target.outputStream().use { source.copyTo(it) }
                        }
                    }
                    nested.add(mapOf("file" to path))
                }
            }
        }

        val metadata = mapOf(
            "schemaVersion" to 1,
            "id" to "allium",
            "version" to modVersion,
            "name" to "Allium",
            "description" to "Allium server utilities for Fabric.",
            "environment" to "server",
            "entrypoints" to mapOf("server" to listOf(bootstrap)),
            "depends" to mapOf("fabricloader" to ">=$loaderVersion", "minecraft" to ">=1.14 <26.4-", "java" to ">=8"),
            "jars" to nested,
            "custom" to mapOf("allium:adapters" to versions)
        )
        directory.resolve("fabric.mod.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(metadata)) + "\n")
    }
}

tasks.jar {
    dependsOn(prepareAdapters)
    from(prepareAdapters)
    from(rootProject.layout.projectDirectory.dir("src/main/resources")) {
        include(
            "config.yml",
            "lang/*.yml",
            "info.txt",
            "rules.txt",
            "motd.txt",
            "custom.txt",
            "broadcasts.txt",
            "worth.yml"
        )
        into("allium-defaults")
    }
    for (adapter in adapters) {
        from(adapter.map { zipTree(it) }) {
            include("codes/**")
            if (adapter != legacyJar) exclude("codes/castled/allium/portable/**", "codes/castled/allium/brigadier/**")
        }
    }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

tasks.register<Copy>("stagePlatformJar") {
    from(tasks.jar.flatMap { it.archiveFile })
    into(rootProject.layout.buildDirectory.dir("platforms"))
}
