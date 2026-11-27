plugins { java }

version = rootProject.version
base.archivesName.set("allium-legacy")
sourceSets.main { java.srcDir("../portable/src/main/java") }

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

dependencies {
    implementation(project(":allium-text"))
    compileOnly("org.spigotmc:spigot-api:1.12.2-R0.1-SNAPSHOT") { isTransitive = false }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(8)
}

// Packaged into the unified Allium.jar / Allium-Paper-<version>.jar (root owns plugin.yml).
tasks.processResources {
    exclude("plugin.yml")
}
