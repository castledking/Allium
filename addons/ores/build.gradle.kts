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
    mavenLocal()
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.momirealms.net/releases/")
}

dependencies {
    compileOnly(project(":allium-api"))
    compileOnly("org.spigotmc:spigot-api:$spigotApiVersion")
    compileOnly("org.jetbrains:annotations:26.0.2")
    // Optional at runtime: ore blocks come from CraftEngine, Jade's overlay goes through PacketEvents.
    compileOnly("net.momirealms:craft-engine-core:26.9.1") { isTransitive = false }
    compileOnly("net.momirealms:craft-engine-bukkit:26.9.1") { isTransitive = false }
    compileOnly("com.github.retrooper:packetevents-api:2.12.1") { isTransitive = false }
    compileOnly("com.github.retrooper:packetevents-spigot:2.12.1") { isTransitive = false }
    compileOnly("net.kyori:adventure-api:4.17.0")
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
// CraftEngine's API is Java 21 bytecode: compile with JDK 21, still emitting the addon's target
// version. CraftEngineOres is only loaded once CraftEngine is enabled, so Java 17 servers run Ores.
configurations.compileClasspath {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
}
tasks.named<JavaCompile>("compileJava") {
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
tasks.test { useJUnitPlatform() }

// A copy beside Allium.jar, in the root build/libs/addons/, so every jar transfers from one place.
// Only this jar is the task's output, so the addons can share that folder.
val collectJar = tasks.register("collectJar") {
    group = "build"
    description = "Copies the addon jar to the root build/libs/addons/Ores.jar"
    val built = tasks.jar.flatMap { it.archiveFile }
    val collected = rootProject.layout.buildDirectory.file("libs/addons/Ores.jar")
    inputs.file(built)
    outputs.file(collected)
    doLast { built.get().asFile.copyTo(collected.get().asFile, overwrite = true) }
}

tasks.jar {
    archiveBaseName.set("Addon-Ores")
    finalizedBy(collectJar)
}
