plugins {
    `java-library`
}

group = rootProject.group
version = rootProject.version

val targetJavaVersion = providers.gradleProperty("targetJavaVersion").get().toInt()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
    }
}

repositories {
    mavenCentral()
}

// Platform-neutral domain and command-spec module:
// Strictly no Bukkit, Spigot, or Paper dependencies allowed here.
dependencies {
    compileOnly("org.jetbrains:annotations:26.0.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.1")
    testImplementation("org.mockito:mockito-junit-jupiter:5.16.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.jetbrains:annotations:26.0.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Xshare:off", "-Dnet.bytebuddy.experimental=true")
}
