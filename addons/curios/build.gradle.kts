plugins { java }

group = rootProject.group
version = rootProject.version

val targetJavaVersion = providers.gradleProperty("targetJavaVersion").get().toInt()
val spigotApiVersion = providers.gradleProperty("spigotApiVersion").get()

java.toolchain.languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

val packTool by sourceSets.creating

dependencies {
    compileOnly(project(":allium-api"))
    compileOnly("org.spigotmc:spigot-api:$spigotApiVersion")
    compileOnly("org.jetbrains:annotations:26.0.2")
    compileOnly("com.github.retrooper:packetevents-api:2.12.1") { isTransitive = false }
    compileOnly("net.kyori:adventure-api:4.17.0")
    "packToolImplementation"("org.spigotmc:spigot-api:$spigotApiVersion")
    testImplementation(project(":allium-api"))
    testImplementation("org.spigotmc:spigot-api:$spigotApiVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testImplementation("org.mockito:mockito-junit-jupiter:5.16.0")
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

val resourcePack = tasks.register<JavaExec>("resourcePack") {
    group = "build"
    description = "Builds the Curios resource pack"
    dependsOn(tasks.named("packToolClasses"))
    classpath = packTool.runtimeClasspath
    mainClass.set("codes.castled.curios.pack.CuriosPackBuilder")
    inputs.dir("pack/assets")
    inputs.file("pack/pack.png")
    inputs.file("src/main/resources/config.yml")
    inputs.file("src/main/resources/frame-glyphs.json")
    outputs.file(packOutput)
    outputs.dir(generatedResources)
    args(
        file("pack").absolutePath,
        file("src/main/resources").absolutePath,
        packOutput.get().asFile.absolutePath,
        "--panel-layout", generatedResources.get().file("card-panels.json").asFile.absolutePath
    )
}

sourceSets.main { resources.srcDir(generatedResources) }
tasks.processResources { dependsOn(resourcePack) }

// A copy beside Allium.jar, in the root build/libs/addons/, so every jar transfers from one place.
// Only this jar is the task's output, so the addons can share that folder.
val collectJar = tasks.register("collectJar") {
    group = "build"
    description = "Copies the addon jar to the root build/libs/addons/Curios.jar"
    val built = tasks.jar.flatMap { it.archiveFile }
    val collected = rootProject.layout.buildDirectory.file("libs/addons/Curios.jar")
    inputs.file(built)
    outputs.file(collected)
    doLast { built.get().asFile.copyTo(collected.get().asFile, overwrite = true) }
}

tasks.jar {
    archiveBaseName.set("Addon-Curios")
    dependsOn(resourcePack)
    from(packOutput) {
        rename { "curios-pack.zip" }
    }
    finalizedBy(collectJar)
}
