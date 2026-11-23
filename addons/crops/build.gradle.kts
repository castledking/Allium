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

val packTool by sourceSets.creating

dependencies {
    compileOnly(project(":allium-api"))
    compileOnly("org.joml:joml:1.10.5")
    compileOnly("org.spigotmc:spigot-api:$spigotApiVersion")
    compileOnly("org.jetbrains:annotations:26.0.2")
    "packToolImplementation"("org.spigotmc:spigot-api:$spigotApiVersion")
    testImplementation(packTool.output)
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

val packOutput = layout.buildDirectory.file("Crops-resource-pack.zip")
val resourcePack = tasks.register<JavaExec>("resourcePack") {
    group = "build"
    description = "Builds the Crops resource pack"
    dependsOn(tasks.named("packToolClasses"))
    classpath = packTool.runtimeClasspath
    mainClass.set("codes.castled.crops.pack.CropsPackBuilder")
    inputs.dir("pack/assets")
    inputs.file("pack/pack.png")
    inputs.file("src/main/resources/items.yml")
    inputs.file("src/main/resources/crops.yml")
    inputs.file("src/main/resources/kitchen.yml")
    outputs.file(packOutput)
    args(file("pack").absolutePath, file("src/main/resources").absolutePath, packOutput.get().asFile.absolutePath)
}

// A copy beside Allium.jar, in the root build/libs/addons/, so every jar transfers from one place.
// Only this jar is the task's output, so the addons can share that folder.
val collectJar = tasks.register("collectJar") {
    group = "build"
    description = "Copies the addon jar to the root build/libs/addons/Crops.jar"
    val built = tasks.jar.flatMap { it.archiveFile }
    val collected = rootProject.layout.buildDirectory.file("libs/addons/Crops.jar")
    inputs.file(built)
    outputs.file(collected)
    doLast { built.get().asFile.copyTo(collected.get().asFile, overwrite = true) }
}

tasks.jar {
    archiveBaseName.set("Addon-Crops")
    dependsOn(resourcePack)
    from(packOutput) {
        rename { "crops-pack.zip" }
    }
    finalizedBy(collectJar)
}
