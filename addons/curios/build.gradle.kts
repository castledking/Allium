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
val packOutput = layout.buildDirectory.file("Curios-resource-pack.zip")
// card-panels.json, the resizable panel layout the pack builder writes for the plugin to read.
val generatedResources = layout.buildDirectory.dir("generated/curios-resources")

val resourcePack = tasks.register<Exec>("resourcePack") {
    group = "build"
    description = "Builds the Curios resource pack (requires Python and PyYAML)"
    inputs.file("pack/build.py")
    inputs.file("pack/pack.png")
    inputs.dir("pack/assets")
    inputs.file("src/main/resources/config.yml")
    inputs.file("src/main/resources/frame-glyphs.json")
    outputs.file(packOutput)
    outputs.dir(generatedResources)
    commandLine("python3", "pack/build.py", packOutput.get().asFile,
        "--panel-layout", generatedResources.get().file("card-panels.json").asFile)
}

sourceSets.main { resources.srcDir(generatedResources) }
tasks.processResources { dependsOn(resourcePack) }

// A copy beside Allium.jar in the root build/libs, so both transfer from one place.
val collectJar = tasks.register<Copy>("collectJar") {
    group = "build"
    description = "Copies the addon jar to the root build/libs/addons/curios/Curios.jar"
    from(tasks.jar)
    into(rootProject.layout.buildDirectory.dir("libs/addons/curios"))
    rename { "Curios.jar" }
}

tasks.jar {
    archiveBaseName.set("Addon-Curios")
    dependsOn(resourcePack)
    from(packOutput) {
        rename { "curios-pack.zip" }
    }
    finalizedBy(collectJar)
}
