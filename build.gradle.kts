import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

group = property("group") as String
version = property("version") as String

val spigotApiVersion = providers.gradleProperty("spigotApiVersion").get()
val targetJavaVersion = providers.gradleProperty("targetJavaVersion").get().toInt()
val coreProject = project(":allium-core")

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://croabeast.github.io/repo/")
    maven("https://repo.xenondevs.xyz/releases")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://jitpack.io")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
    }
}

dependencies {
    implementation(coreProject)

    // Shared baseline server API: Spigot 1.20
    compileOnly("org.spigotmc:spigot-api:$spigotApiVersion")

    // Provided optional integrations
    compileOnly("org.jetbrains:annotations:26.0.2")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("me.clip:placeholderapi:2.11.6")

    // Shaded runtime dependencies (audited for Java 17 bytecode compatibility)
    implementation("me.croabeast.takion:shaded:1.4")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("xyz.xenondevs.invui:invui-core:1.49")
    implementation("xyz.xenondevs.invui:inventory-access:1.49") {
        isTransitive = false
    }
    // Include InvUI inventory-access revisions for 1.20 through 1.21.11+
    // Note: r16-r18 are Java 17 bytecode; r19+ (1.20.5+) are Java 21 bytecode loaded reflectively only on 1.20.5+ servers
    for (rev in 16..26) {
        runtimeOnly("xyz.xenondevs.invui:inventory-access-r$rev:1.49") {
            isTransitive = false
        }
    }
    implementation("net.kyori:adventure-text-serializer-legacy:4.17.0")

    // Testing
    testImplementation("org.spigotmc:spigot-api:$spigotApiVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.1")
    testImplementation("org.mockito:mockito-junit-jupiter:5.16.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.jetbrains:annotations:26.0.2")
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(targetJavaVersion)
    }

    processResources {
        filesMatching("plugin.yml") {
            expand("version" to project.version.toString())
        }
    }

    shadowJar {
        archiveBaseName.set("Allium")
        archiveClassifier.set("")
        archiveVersion.set("")

        // Preserve reflectively loaded InvUI version modules and HikariCP internals
        minimize {
            exclude(dependency("xyz.xenondevs.invui:.*:.*"))
            exclude(dependency("com.zaxxer:HikariCP:.*"))
            exclude(dependency("me.croabeast.takion:.*:.*"))
        }

        dependsOn(coreProject.tasks.named("classes"))
        mergeServiceFiles()
        from(coreProject.layout.buildDirectory.dir("classes/java/main"))

        relocate("com.zaxxer.hikari", "codes.castled.allium.lib.hikari")
        relocate("me.croabeast", "codes.castled.allium.lib.croabeast")
        relocate("xyz.xenondevs.invui", "codes.castled.allium.lib.invui")
        relocate("xyz.xenondevs.inventoryaccess", "codes.castled.allium.lib.inventoryaccess")
        relocate("net.kyori.adventure", "codes.castled.allium.lib.adventure")
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
        jvmArgs("-Xshare:off", "-Dnet.bytebuddy.experimental=true")
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}
