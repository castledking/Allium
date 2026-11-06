# S05 count-preserving CraftEngine runtime builds

Only this directory is owned by the implementation agent. Allium production code, trigger runner, docs/matrix and the original vendor checkout remain unchanged. No servers are started here; the parent performs native runtime tests.

Both requested source commits are available locally. The sources are isolated shared clones with detached HEADs, not the newer adjacent b3079c5a source. Only the checked-in one-line reset-preserve-count.patch is applied to provider Java sources. No official runtime jar is rewritten, no descriptor injected, and no credential files copied.

Full distributable task: `:bukkit:paper-loader:shadowJar`; output: `source/target/craft-engine-paper-plugin-<version>.jar`. This source task generates the native Paper descriptor, includes all provider runtime modules and resources, and embeds the proxy jar through its normal build configuration.

Build launcher (run inside each isolated source directory):

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk bash ./gradlew :bukkit:paper-loader:shadowJar --no-daemon --max-workers=2 -Dorg.gradle.jvmargs='-Xmx1024m -XX:MaxMetaspaceSize=512m' -Dorg.gradle.java.installations.paths=/usr/lib/jvm/java-17-openjdk,/usr/lib/jvm/java-21-openjdk,/usr/lib/jvm/java-25-openjdk --console=plain
```

The initial CE26.9.1 offline attempt failed because `de.eldoria.plugin-yml.bukkit:0.7.1` was not cached. Its log is retained. The online retry uses the existing local Gradle cache and declared public repositories.

Each build directory records exact source identity, patch SHA256, source/build differences, build logs and artifact/descriptor hashes. Runtime lane claims require parent test results; compilation alone does not establish compatibility.

## Build-only dependency correction

The declared Gson 2.14.0 resolves after a Geyser common 2.2.1-SNAPSHOT jar that bundles an older Gson. `BukkitCompatibilityManager.java:561` uses `JsonObject.asMap()`, so javac fails with the original order. `ce-26.9.1-2b9e6f59/compatibility-classpath.txt` captures the original order. `javap` confirms old Geyser Gson lacks asMap while the genuine declared Gson has it. The external `compatibility-gson-first.init.gradle` prepends the same resolved Gson only for compatibility's compileJava task. It changes no source or runtime dependency version and does not modify jar packaging. All provider source changes remain the single reset-count patch. The successful command includes `-I /mnt/storage/.allium-validation/20261106/ce-count-fix/builds/compatibility-gson-first.init.gradle --offline`.

This compiler-only correction is part of provenance, not a claim of byte-for-byte official artifact reproduction. These are full source builds of the exact requested commits. Intended native lanes are CE26.9.1 on Paper1.21.8/JDK21 and SF26.9.2 on Paper26.3/JDK25; the implementation agent does not mark either validated before the parent runs it.
