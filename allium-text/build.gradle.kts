plugins { `java-library` }

group = rootProject.group
version = rootProject.version

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(8)
}
