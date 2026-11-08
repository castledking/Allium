plugins {
    `java-library`
}

group = rootProject.group
version = providers.gradleProperty("alliumApiVersion").get()

val targetJavaVersion = providers.gradleProperty("targetJavaVersion").get().toInt()
val spigotApiVersion = providers.gradleProperty("spigotApiVersion").get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
    }
}

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
}

// The addon API: interfaces and value types only. Its command-spec and scheduler types come from
// allium-core and are part of the published API jar (see the root project's apiJar task).
dependencies {
    api(project(":allium-core"))
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

tasks.test {
    useJUnitPlatform()
}
