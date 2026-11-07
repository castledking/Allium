import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.attributes.java.TargetJvmVersion

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
    maven("https://repo.xenondevs.xyz/releases")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://jitpack.io")
    maven("https://repo.nexomc.com/releases")
    maven("https://repo.oraxen.com/releases")
    maven("https://repo.momirealms.net/releases/")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
    }
}

// Provider APIs remain compile-only and never enter shared/main signatures.
val providerSources = listOf("nexoAdapter", "craftengineAdapter", "oraxenAdapter")
val providerJavaCompiler = javaToolchains.compilerFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}
val providerTestLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}
providerSources.forEach { name ->
    sourceSets.create(name) {
        compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    }
    configurations.named(sourceSets[name].compileClasspathConfigurationName) {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}

// Optional protection hooks: compiled against public plugin APIs, Java 17 bytecode, loaded reflectively.
val hookSources = listOf("griefpreventionHook")
hookSources.forEach { name ->
    sourceSets.create(name) {
        compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    }
}

sourceSets.test {
    hookSources.forEach { name ->
        compileClasspath += sourceSets[name].output
        runtimeClasspath += sourceSets[name].output
    }
    providerSources.forEach { name ->
        compileClasspath += sourceSets[name].output
        runtimeClasspath += sourceSets[name].output
    }
}
configurations.testCompileClasspath {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
}
configurations.testRuntimeClasspath {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
}

dependencies {
    implementation(coreProject)

    add("nexoAdapterCompileOnly", "com.nexomc:nexo:1.29.1") { isTransitive = false }
    add("craftengineAdapterCompileOnly", "net.momirealms:craft-engine-core:26.9.1") { isTransitive = false }
    add("craftengineAdapterCompileOnly", "net.momirealms:craft-engine-bukkit:26.9.1") { isTransitive = false }
    add("oraxenAdapterCompileOnly", "io.th0rgal:oraxen:1.211.0") { isTransitive = false }
    add("griefpreventionHookCompileOnly", "com.github.GriefPrevention:GriefPrevention:16.18.2") { isTransitive = false }

    // Shared baseline server API: Spigot 1.20
    compileOnly("org.spigotmc:spigot-api:$spigotApiVersion")

    // Provided optional runtime APIs (Paper Adventure is provided by Paper at runtime; isolated behind AdventureTextBridge)
    compileOnly("net.kyori:adventure-api:4.17.0")
    compileOnly("org.jetbrains:annotations:26.0.2")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("me.clip:placeholderapi:2.11.6")

    // Shaded runtime dependencies (audited for Java 17 bytecode compatibility)
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

    // Testing
    testImplementation("org.spigotmc:spigot-api:$spigotApiVersion")
    testImplementation("com.nexomc:nexo:1.29.1") { isTransitive = false }
    testImplementation("net.momirealms:craft-engine-core:26.9.1") { isTransitive = false }
    testImplementation("net.momirealms:craft-engine-bukkit:26.9.1") { isTransitive = false }
    testImplementation("io.th0rgal:oraxen:1.211.0") { isTransitive = false }
    testImplementation("com.github.GriefPrevention:GriefPrevention:16.18.2") { isTransitive = false }
    testRuntimeOnly("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
    testImplementation("net.kyori:adventure-api:4.17.0")
    testImplementation("net.kyori:adventure-text-serializer-legacy:4.17.0")
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

    // Pinned provider APIs use Java 21; adapters still emit Java 17 bytecode.
    providerSources.forEach { name ->
        named<JavaCompile>(sourceSets[name].compileJavaTaskName) {
            javaCompiler.set(providerJavaCompiler)
        }
    }
    named<JavaCompile>("compileTestJava") {
        javaCompiler.set(providerJavaCompiler)
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
        providerSources.forEach { from(sourceSets[it].output) }
        hookSources.forEach { from(sourceSets[it].output) }

        // Preserve reflectively loaded InvUI version modules and HikariCP internals, and all allium-core classes
        minimize {
            exclude(project(":allium-core"))
            exclude(dependency("xyz.xenondevs.invui:.*:.*"))
            exclude(dependency("com.zaxxer:HikariCP:.*"))
        }

        mergeServiceFiles()

        relocate("com.zaxxer.hikari", "codes.castled.allium.lib.hikari")
        relocate("xyz.xenondevs.invui", "codes.castled.allium.lib.invui")
        relocate("xyz.xenondevs.inventoryaccess", "codes.castled.allium.lib.inventoryaccess")
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        javaLauncher.set(providerTestLauncher)
        useJUnitPlatform()
        jvmArgs("-Xshare:off", "-Dnet.bytebuddy.experimental=true")
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}
