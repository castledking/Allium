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
evaluationDependsOn(":allium-core")
val apiProject = project(":allium-api")
evaluationDependsOn(":allium-api")
val alliumApiVersion = providers.gradleProperty("alliumApiVersion").get()

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://repo.xenondevs.xyz/releases")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://nexus.scarsz.me/content/groups/public/")
    maven("https://jitpack.io")
    maven("https://repo.nexomc.com/releases")
    maven("https://repo.oraxen.com/releases")
    maven("https://repo.momirealms.net/releases/")
    maven("https://libraries.minecraft.net")
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

// Paper-only Brigadier command registration (Paper 1.20.6+), compiled against paper-api and
// loaded reflectively; Spigot never loads it.
val paperSources = listOf("paperCommands")
paperSources.forEach { name ->
    sourceSets.create(name) {
        compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    }
    configurations.named(sourceSets[name].compileClasspathConfigurationName) {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
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
    implementation(apiProject)

    add("nexoAdapterCompileOnly", "com.nexomc:nexo:1.29.1") { isTransitive = false }
    add("craftengineAdapterCompileOnly", "net.momirealms:craft-engine-core:26.9.1") { isTransitive = false }
    add("craftengineAdapterCompileOnly", "net.momirealms:craft-engine-bukkit:26.9.1") { isTransitive = false }
    add("oraxenAdapterCompileOnly", "io.th0rgal:oraxen:1.211.0") { isTransitive = false }
    add("griefpreventionHookCompileOnly", "com.github.GriefPrevention:GriefPrevention:16.18.2") { isTransitive = false }
    add("paperCommandsCompileOnly", "io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")

    // Shared baseline server API: Spigot 1.20
    compileOnly("org.spigotmc:spigot-api:$spigotApiVersion")

    // Provided optional runtime APIs (Paper Adventure is provided by Paper at runtime; isolated behind AdventureTextBridge)
    compileOnly("net.kyori:adventure-api:4.17.0")
    compileOnly("org.jetbrains:annotations:26.0.2")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    // /creativemanager reads and writes flags through LuckPerms when it is installed (never shaded).
    compileOnly("net.luckperms:api:5.4")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("net.kyori:adventure-text-serializer-plain:4.17.0")
    compileOnly("com.discordsrv:discordsrv:1.30.3") { isTransitive = false }
    compileOnly("com.github.retrooper:packetevents-api:2.12.1") { isTransitive = false }

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
    testImplementation("net.kyori:adventure-text-serializer-gson:4.17.0")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7.1")
    testImplementation("net.luckperms:api:5.4")
    testImplementation("com.discordsrv:discordsrv:1.30.3") { isTransitive = false }
    testImplementation("com.github.retrooper:packetevents-api:2.12.1") { isTransitive = false }
    testImplementation("com.github.retrooper:packetevents-netty-common:2.12.1") { isTransitive = false }
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.1")
    testImplementation("org.mockito:mockito-junit-jupiter:5.16.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.jetbrains:annotations:26.0.2")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.49.1.0")
}

// Core resource pack: generated from pack/manifest.json by allium-core's CorePackMain.
// The output is byte-identical for identical inputs (sorted, stored, fixed timestamps).
val corePackDir = layout.buildDirectory.dir("pack")
val corePack = tasks.register<JavaExec>("corePack") {
    group = "build"
    description = "Generates and validates the deterministic core resource pack."
    classpath = coreProject.the<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("codes.castled.allium.pack.CorePackMain")
    inputs.file("pack/manifest.json")
    inputs.file("pack/pack.png")
    inputs.dir("pack/assets")
    outputs.dir(corePackDir)
    doFirst { delete(corePackDir) }
    args("build", file("pack/manifest.json").absolutePath, file("pack").absolutePath, corePackDir.get().asFile.absolutePath)
}

// Optional local standalone pack: when SF's Nexo textures are available locally (and -PdistributablePack
// is not set), build the complete SF + invsee merged pack into git-ignored build/standalone-pack/ so
// standalone mode delivers it automatically as allium-core-pack.zip without committing non-distributable art.
val distributablePack = providers.gradleProperty("distributablePack").map { it.equals("true", ignoreCase = true) }.getOrElse(false)
val sfPluginsCandidate: File? = if (distributablePack) null else listOfNotNull(
    providers.gradleProperty("sfPlugins").orNull?.let(::File),
    System.getenv("SF")?.takeIf { it.isNotBlank() }?.let(::File),
    File("/mnt/storage/backups/survivalfun/daily.0/survivalfun/var/lib/pterodactyl/volumes/db85ca1b-2fae-4f63-848f-2870a86f6ed7/plugins")
).firstOrNull { File(it, "Nexo/pack/assets/sf/textures/items/tree_axe.png").isFile }
val vanillaClientCandidate: File? = if (distributablePack) null else listOfNotNull(
    providers.gradleProperty("vanillaClient").orNull?.let(::File),
    File(System.getProperty("user.home"), ".minecraft/versions/26.4-snapshot-6/26.4-snapshot-6.jar")
).firstOrNull { it.isFile }
val sfExportScriptCandidate: File? = if (distributablePack) null else listOfNotNull(
    providers.gradleProperty("alliumToolsDir").orNull?.let { File(it, "pack/tools/sf_export.py") },
    File(rootProject.projectDir.parentFile, "AlliumTools/pack/tools/sf_export.py")
).firstOrNull { it.isFile }
val standalonePackDir = layout.buildDirectory.dir("standalone-pack")
val standalonePack = tasks.register<Exec>("standalonePack") {
    group = "build"
    description = "Builds the local SF + invsee merged pack for standalone delivery (skipped if SF art, AlliumTools, or -PdistributablePack=true)."
    dependsOn(":allium-core:jar")
    onlyIf { sfPluginsCandidate != null && sfExportScriptCandidate != null }
    inputs.file("pack/manifest.json")
    inputs.file("pack/pack.png")
    inputs.dir("pack/assets")
    outputs.dir(standalonePackDir)
    doFirst {
        delete(standalonePackDir)
        val coreJar = coreProject.tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val cmd = mutableListOf(
            "python3",
            sfExportScriptCandidate!!.absolutePath,
            "--plugins", sfPluginsCandidate!!.absolutePath,
            "--allium-jar", coreJar.absolutePath,
            "--out", standalonePackDir.get().asFile.absolutePath,
            "--name", "allium-standalone-pack.zip",
            "--include-lazy-axe"
        )
        if (vanillaClientCandidate != null) {
            cmd += listOf("--vanilla-client", vanillaClientCandidate.absolutePath)
        }
        commandLine(cmd)
    }
}

// The addon API jar: allium-api plus the allium-core types its signatures use (command specs and the
// scheduler interfaces). Addons compile against it with compileOnly; Allium.jar provides it at runtime.
val apiJar = tasks.register<Jar>("apiJar") {
    group = "build"
    description = "Builds the Allium addon API jar (compileOnly for addons; never shade it)."
    archiveFileName.set("Allium-API-$alliumApiVersion.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from(apiProject.the<SourceSetContainer>()["main"].output)
    from(coreProject.the<SourceSetContainer>()["main"].output) {
        include("codes/castled/allium/command/spec/**")
        exclude("codes/castled/allium/command/spec/CoreCommandSpecs*", "codes/castled/allium/command/spec/CommandFamilyPolicy*")
        include("codes/castled/allium/scheduler/TaskScheduler*.class", "codes/castled/allium/scheduler/TaskHandle*.class")
    }
    manifest {
        attributes("Allium-API-Version" to alliumApiVersion)
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(targetJavaVersion)
    }

    // Pinned provider APIs use Java 21; adapters still emit Java 17 bytecode.
    (providerSources + paperSources).forEach { name ->
        named<JavaCompile>(sourceSets[name].compileJavaTaskName) {
            javaCompiler.set(providerJavaCompiler)
        }
    }
    named<JavaCompile>("compileTestJava") {
        javaCompiler.set(providerJavaCompiler)
    }

    processResources {
        // A plain token replace, not expand(): Groovy templates cap literal text at 65535 characters,
        // which plugin.yml has outgrown.
        val pluginVersion = project.version.toString()
        inputs.property("pluginVersion", pluginVersion)
        filesMatching("plugin.yml") {
            filter { line -> line.replace("\${version}", pluginVersion) }
        }
        // The deterministic core pack and its manifest ship inside the plugin jar.
        from(corePack) { into("pack") }
        from(standalonePack) {
            into("pack")
            include("allium-standalone-pack.zip", "allium-standalone-pack.zip.sha1", "allium-standalone-pack.zip.json")
        }
        from("pack/manifest.json") { into("pack") }
    }

    shadowJar {
        archiveBaseName.set("Allium")
        archiveClassifier.set("")
        archiveVersion.set("")
        providerSources.forEach { from(sourceSets[it].output) }
        hookSources.forEach { from(sourceSets[it].output) }
        paperSources.forEach { from(sourceSets[it].output) }

        // Preserve reflectively loaded InvUI version modules and HikariCP internals, and all allium-core classes
        minimize {
            exclude(project(":allium-core"))
            exclude(project(":allium-api"))
            exclude(dependency("xyz.xenondevs.invui:.*:.*"))
            exclude(dependency("com.zaxxer:HikariCP:.*"))
        }

        mergeServiceFiles()

        relocate("com.zaxxer.hikari", "codes.castled.allium.lib.hikari")
        relocate("xyz.xenondevs.invui", "codes.castled.allium.lib.invui")
        relocate("xyz.xenondevs.inventoryaccess", "codes.castled.allium.lib.inventoryaccess")
    }

    build {
        dependsOn(shadowJar, apiJar)
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

// DiscordSRV's distribution bundles an older unrelocated SnakeYAML. The server
// supplies Bukkit's version first; reproduce that parent-library order in SDK tests.
tasks.withType<Test>().configureEach {
    doFirst {
        val peers = classpath.filter { it.name.startsWith("discordsrv-") }
        classpath = files(classpath.filter { !it.name.startsWith("discordsrv-") }, peers)
    }
}
