plugins { java }

group = rootProject.group
version = rootProject.version

val targetJavaVersion = providers.gradleProperty("targetJavaVersion").get().toInt()
val spigotApiVersion = providers.gradleProperty("spigotApiVersion").get()

java.toolchain.languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
}

dependencies {
    compileOnly(project(":allium-api"))
    compileOnly(project(":addons:curios"))
    compileOnly("org.spigotmc:spigot-api:$spigotApiVersion")
    compileOnly("org.jetbrains:annotations:26.0.2")
    testImplementation("org.spigotmc:spigot-api:$spigotApiVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.1")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}
tasks.test { useJUnitPlatform() }

val sfPlugins = providers.environmentVariable("SF").filter { it.isNotBlank() }
val packOutput = layout.buildDirectory.file("SpawnerCraft-resource-pack.zip")
val resourcePack = tasks.register<Exec>("resourcePack") {
    group = "build"
    inputs.file("pack/build.py")
    inputs.file("pack/pack.png")
    inputs.property("sfPlugins", sfPlugins.orElse(""))
    sfPlugins.orNull?.let { source ->
        val assets = file(source).resolve("Nexo/pack/assets/sf")
        inputs.files(assets.resolve("models/items/spawner_changer.json"),
            assets.resolve("textures/items/spawner_changer.png"))
    }
    inputs.file("src/main/resources/spawner_changer.yml")
    outputs.file(packOutput)
    commandLine("python3", "pack/build.py", packOutput.get().asFile)
    sfPlugins.orNull?.let { args("--sf", file(it).absolutePath) }
}

val collectJar = tasks.register<Copy>("collectJar") {
    from(tasks.jar)
    into(rootProject.layout.buildDirectory.dir("libs/addons/spawnercraft"))
    rename { "SpawnerCraft.jar" }
}

tasks.jar {
    archiveBaseName.set("Addon-SpawnerCraft")
    dependsOn(resourcePack)
    from(packOutput) {
        rename { "spawnercraft-pack.zip" }
    }
    finalizedBy(collectJar)
}
