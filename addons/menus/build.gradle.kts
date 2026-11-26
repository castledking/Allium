import org.gradle.api.attributes.java.TargetJvmVersion

plugins { java }

group = rootProject.group
version = rootProject.version
java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
}

val paper = sourceSets.create("paper") {
    compileClasspath += sourceSets.main.get().output
}
configurations[paper.compileClasspathConfigurationName].attributes
    .attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)

dependencies {
    compileOnly(project(":allium-api"))
    compileOnly("org.spigotmc:spigot-api:" + providers.gradleProperty("spigotApiVersion").get())
    compileOnly("com.google.code.gson:gson:2.11.0")
    compileOnly("org.jetbrains:annotations:26.0.2")
    add(paper.compileOnlyConfigurationName, "io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(if (name == paper.compileJavaTaskName) 21 else 17)
}

val collectJar = tasks.register("collectJar") {
    group = "build"
    val jar = tasks.jar.flatMap { it.archiveFile }
    val collected = rootProject.layout.buildDirectory.file("libs/addons/Menus.jar")
    inputs.file(jar)
    outputs.file(collected)
    doLast { jar.get().asFile.copyTo(collected.get().asFile, overwrite = true) }
}

tasks.jar {
    archiveBaseName.set("Addon-Menus")
    from(paper.output)
    finalizedBy(collectJar)
}
