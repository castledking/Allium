import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

group = property("group") as String
version = property("version") as String

val minecraftVersion = providers.gradleProperty("minecraftVersion").get()
val paperMinecraftVersion = providers.gradleProperty("paperMinecraftVersion").get()
val legacyBukkitVersion = providers.gradleProperty("legacyBukkitVersion").get()
val targetJavaVersion = providers.gradleProperty("targetJavaVersion").get().toInt()
val legacyTargetJavaVersion = providers.gradleProperty("legacyTargetJavaVersion").get().toInt()
val coreProject = project(":allium-core")

repositories {
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
    val javaVersion = JavaVersion.toVersion(targetJavaVersion)
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}

sourceSets {
    val main by getting

    create("compatLegacy") {
        java.srcDir("src/compat/legacy/java")
        compileClasspath += main.output
        runtimeClasspath += output + compileClasspath
    }

    create("compatModern") {
        java.srcDir("src/compat/modern/java")
        compileClasspath += main.output + main.compileClasspath
        runtimeClasspath += output + compileClasspath
    }
}

val paperApi by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    implementation(coreProject)

    // Core server API
    compileOnly("io.papermc.paper:paper-api:$paperMinecraftVersion-R0.1-SNAPSHOT")
    paperApi("io.papermc.paper:paper-api:$paperMinecraftVersion-R0.1-SNAPSHOT")

    // Provided dependencies
    compileOnly("org.jetbrains:annotations:26.0.2")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("me.clip:placeholderapi:2.11.6")

    // Shaded dependencies
    implementation("me.croabeast.takion:shaded:1.4")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("xyz.xenondevs.invui:invui:1.49")
    implementation("net.kyori:adventure-text-serializer-legacy:4.17.0")

    // Legacy compat - compile only
    add("compatLegacyCompileOnly", "org.bukkit:bukkit:$legacyBukkitVersion-R0.1-SNAPSHOT")
    add("compatLegacyCompileOnly", "org.jetbrains:annotations:26.0.2")

    // Testing
    testImplementation("io.papermc.paper:paper-api:$paperMinecraftVersion-R0.1-SNAPSHOT")
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

    named<JavaCompile>("compileCompatLegacyJava") {
        options.release.set(legacyTargetJavaVersion)
        options.compilerArgs.add("-Xlint:-options")
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
        minimize()

        dependsOn(coreProject.tasks.named("classes"))
        mergeServiceFiles()
        from(coreProject.layout.buildDirectory.dir("classes/java/main"))
        from(sourceSets["compatLegacy"].output)
        from(sourceSets["compatModern"].output)

        relocate("com.zaxxer.hikari", "codes.castled.allium.lib.hikari")
        relocate("me.croabeast", "codes.castled.allium.lib.croabeast")
        relocate("xyz.xenondevs.invui", "codes.castled.allium.lib.invui")
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

    val checkLegacyClassLoading by registering(JavaExec::class) {
        group = "verification"
        description = "Initializes key plugin classes with only legacy Bukkit on the runtime classpath."
        dependsOn(named("classes"), named("compatLegacyClasses"), coreProject.tasks.named("classes"))
        classpath = sourceSets["compatLegacy"].runtimeClasspath + files(coreProject.layout.buildDirectory.dir("classes/java/main"))
        mainClass.set("codes.castled.allium.compat.legacy.LegacyClassLoadingCheck")
        args(
            "codes.castled.allium.PluginStart",
            "codes.castled.allium.Allium"
        )
    }

    register("checkLegacyCompatibility") {
        group = "verification"
        description = "Compiles legacy-server compatibility sources with the configured legacy Java target."
        dependsOn(named("compileCompatLegacyJava"), checkLegacyClassLoading)
    }
}

sourceSets {
    named("main") {
        compileClasspath += paperApi
    }
    named("test") {
        compileClasspath += paperApi
    }
    named("compatModern") {
        compileClasspath += paperApi
        runtimeClasspath += paperApi
    }
}
