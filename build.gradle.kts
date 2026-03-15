plugins {
    java
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

group = property("group") as String
version = property("version") as String

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://croabeast.github.io/repo/")
    maven("https://repo.xenondevs.xyz/releases")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.20.4-R0.1-SNAPSHOT")

    implementation("me.croabeast.takion:shaded:1.4")

    implementation("com.zaxxer:HikariCP:5.1.0")

    implementation("xyz.xenondevs.invui:invui:1.49")

    compileOnly("org.jetbrains:annotations:26.0.1")
}

tasks.shadowJar {
    archiveClassifier.set("")
    minimize()

    relocate("com.zaxxer.hikari", "net.survivalfun.allium.lib.hikari")
    relocate("me.croabeast", "net.survivalfun.allium.lib.croabeast")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
