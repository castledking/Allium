import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    java
    id("net.neoforged.moddev")
}

val supportedVersions = providers.gradleProperty("modMinecraftVersions").get().split(',')
val neoForgeVersion = providers.gradleProperty("neoForgeVersion").get()
val javaVersion = providers.gradleProperty("modJavaVersion").get().toInt()
val coreJar = project(":allium-core").tasks.named<Jar>("jar")

version = rootProject.version
base.archivesName.set("Allium-NeoForge")
java.toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
sourceSets.main {
    java.srcDirs("../minecraft/src/main/java", "../portable/src/main/java", "../brigadier/src/main/java", "../minecraft-homes/src/main/java", "../neoforge-bootstrap/src/main/java")
}
// NeoForge 20.2 scans every class in the combined mod with an older ASM version.
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
configurations.compileClasspath { attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, javaVersion) }

neoForge {
    version = neoForgeVersion
}

dependencies {
    implementation(project(":allium-core"))
}

tasks.processResources {
    val minecraft = supportedVersions.joinToString(",") { "[$it]" }
    val metadata = mapOf("version" to project.version, "minecraftVersions" to minecraft,
        "neoForgeVersion" to neoForgeVersion)
    inputs.properties(metadata)
    filesMatching("META-INF/neoforge.mods.toml") { expand(metadata) }
}

tasks.jar {
    from(coreJar.flatMap { it.archiveFile }.map { zipTree(it.asFile) }) {
        exclude("META-INF/MANIFEST.MF")
    }
}

val legacyProject = layout.projectDirectory.dir("../neoforge-1.20.2")
val legacyWrapper = tasks.register<Copy>("prepareLegacyWrapper") {
    from(rootProject.layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.jar"))
    from(legacyProject.file("gradle-wrapper.properties"))
    into(layout.buildDirectory.dir("legacy/gradle/wrapper"))
}
val legacyJava = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
val legacyJar = legacyProject.file("build/libs/Allium-NeoForge-1.20.2-${project.version}.jar")
val buildLegacyAdapter = tasks.register<Exec>("buildLegacyAdapter") {
    dependsOn(legacyWrapper)
    inputs.files(legacyWrapper)
    inputs.files(legacyProject.file("build.gradle"), legacyProject.file("settings.gradle"),
        rootProject.layout.projectDirectory.file("gradle.properties"))
    inputs.files(legacyProject.dir("src"), layout.projectDirectory.dir("../neoforge-bootstrap/src"),
        layout.projectDirectory.dir("../portable/src"), layout.projectDirectory.dir("../brigadier/src"),
        layout.projectDirectory.dir("../minecraft-homes/src"))
    outputs.file(legacyJar)
    val wrapperJar = legacyWrapper.map { it.destinationDir.resolve("gradle-wrapper.jar") }
    workingDir(legacyProject)
    doFirst {
        val java = legacyJava.get()
        environment("JAVA_HOME", java.metadata.installationPath.asFile.absolutePath)
        commandLine(java.executablePath.asFile, "-cp", wrapperJar.get(), "org.gradle.wrapper.GradleWrapperMain",
            "--no-daemon", "jar")
    }
}

val universalJar = tasks.register<Jar>("universalJar") {
    dependsOn(buildLegacyAdapter)
    archiveClassifier.set("universal")
    from(tasks.jar.flatMap { it.archiveFile }.map { zipTree(it) }) { exclude("META-INF/MANIFEST.MF") }
    from(buildLegacyAdapter.map { zipTree(legacyJar) }) {
        include("META-INF/mods.toml", "codes/castled/allium/neoforge/v1202/**")
    }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

tasks.register<Copy>("stagePlatformJar") {
    from(universalJar.flatMap { it.archiveFile })
    into(rootProject.layout.buildDirectory.dir("platforms"))
    rename { "Allium-NeoForge-${project.version}.jar" }
}
