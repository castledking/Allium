plugins {
    `java-library`
}

group = rootProject.group
version = rootProject.version

java {
    val javaVersion = JavaVersion.toVersion(providers.gradleProperty("targetJavaVersion").get())
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.jetbrains:annotations:26.0.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.1")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(providers.gradleProperty("targetJavaVersion").get().toInt())
}

tasks.test {
    useJUnitPlatform()
}
