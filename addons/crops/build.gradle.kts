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
    compileOnly("org.joml:joml:1.10.5")
    compileOnly("org.spigotmc:spigot-api:$spigotApiVersion")
    compileOnly("org.jetbrains:annotations:26.0.2")
    testImplementation(project(":allium-api"))
    testImplementation("org.spigotmc:spigot-api:$spigotApiVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.1")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}
tasks.test { useJUnitPlatform() }

val packOutput = layout.buildDirectory.file("Crops-resource-pack.zip")
val resourcePack = tasks.register<Exec>("resourcePack") {
    group = "build"
    inputs.file("pack/build.py")
    inputs.dir("pack/assets")
    inputs.file("src/main/resources/items.yml")
    inputs.file("src/main/resources/crops.yml")
    outputs.file(packOutput)
    commandLine("python3", "pack/build.py", packOutput.get().asFile)
}

val collectJar = tasks.register<Copy>("collectJar") {
    from(tasks.jar)
    into(rootProject.layout.buildDirectory.dir("libs/addons/crops"))
    rename { "Crops.jar" }
}

tasks.jar {
    archiveBaseName.set("Addon-Crops")
    dependsOn(resourcePack)
    from(packOutput) {
        rename { "crops-pack.zip" }
    }
    finalizedBy(collectJar)
}
