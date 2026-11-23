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
    // JDBC drivers for storage tests only; servers provide their own at runtime.
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    testRuntimeOnly("com.mysql:mysql-connector-j:9.2.0")
    // The H2 that legacy Allium shaded, to build legacy-shaped databases for the importer tests.
    testRuntimeOnly("com.h2database:h2:2.1.214")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.test {
    useJUnitPlatform()
    // MySQL/MariaDB storage tests run only when a disposable database is supplied.
    val mysqlUrl = providers.environmentVariable("ALLIUM_TEST_MYSQL_URL").orNull?.takeIf { it.isNotBlank() }
    inputs.property("mysqlTestsEnabled", mysqlUrl != null)
    if (mysqlUrl != null) {
        environment("ALLIUM_TEST_MYSQL_URL", mysqlUrl)
        // A different database can be supplied without changing the source or the test filter.
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Runs against a live database") { true }
    }
    jvmArgs("-Xshare:off", "-Dnet.bytebuddy.experimental=true")
}
