# S05 count-preserving provider build recipe

Small provenance retained from
`/mnt/storage/.allium-validation/20261106/ce-count-fix/builds` on 2026-11-06.
Both full Paper distributions were built from the exact requested source commits,
with only `buildItem(context)` → `buildItem(context, item.count())` changed in
`ResetOperation.java`:

- CE 26.9.1: `2b9e6f59a0bf4dac7f71958c0ea96c14b8e44985`.
  JAR SHA-256: `9b86b1044e0d97d800e1722b1ca2b01f2582d44269a28508da7b9b79babfcd41`.
- SF 26.9.2: `94b74598312d5a16216caed1c07fe3966d854096`.
  JAR SHA-256: `d779823f05922747a70a268888610f408dcfa151b194ef46d28d483393d74852`.

The external Gradle init script puts the already declared Gson **2.14.0** ahead
of Geyser common **2.2.1-SNAPSHOT**'s bundled older Gson for
`:bukkit:compatibility:compileJava` only. It changes classpath ordering, with no
dependency version or runtime packaging change. The build uses its normal
`:bukkit:paper-loader:shadowJar` task, generated native Paper descriptor, provider
modules/resources and embedded proxy. No official runtime JAR was rewritten.

## Retained files and references

`builder-README.md`, `historical-verify-artifacts.py.txt`, both init scripts and the reset patch
are verbatim originals. Each version directory retains its original
`provenance.json`, actual source diff, packaged `javap` disassembly and small
successful build log (`build-online-gson-first.log.txt`, renamed to avoid the
repository log ignore rule). The provenance embeds descriptor, manifest and properties
content and hashes, so separate copies are unnecessary.

`retention-audit.json` is a separate, new record of read-only checks performed
during retention. It binds original paths and hashes, records archive/source
checks and hashes all eight dependency reports per version. Those larger reports
and full compiler classpaths remain external; only their references and hashes
are retained. All **233 CE / 237 SF** unique reported file inputs matched their
recorded hashes. Tracked source JAR fingerprints are recorded without copying
JARs. No source checkout, cache, credentials or large logs are included.

Absolute paths in original provenance and the audit identify external artifacts;
they are not relative repository links. `patch_path` points to the identical
parent-directory patch; the copy here has the same SHA-256
`e592edc6278ca90d853bd729e2fd0aaa54457498f9ff5df8f3ccd7517dcaa7d1`.
The init script SHA-256 is
`f572ec4280e7b57bd7d710bcbb502b5528962c95618a8675b4797f6d41cd3f6f`.

## Incomplete original fingerprint verification

The external builder's final **`verification.json` and `SHA256SUMS` are absent**.
Its original provenance lacks the verifier's final archive/dependency fingerprint
fields. The original verifier is retained as `historical-verify-artifacts.py.txt`, a
source record of a known broken check, not a supported verification command:
it expects external `source/` trees beside itself, writes provenance/output files,
and incorrectly requires packaged shaded class bytes to equal unshaded compiler
output. Both existing artifacts fail that equality. Shadow relocates Sparrow NBT
references and reindexes constants. Do not run this script against the retained
directory or interpret its presence as completed verification.

Independent read-only checks passed: exact source commits, one-line tracked
change, recorded diff/reverse patch check, no staged/untracked changes, JAR hashes
and sizes, ZIP CRCs, descriptors equal to generated output and recorded content,
embedded proxy and required runtime classes. Fresh JDK 25 `javap -c` output equals
each saved disassembly and shows `Item.count()` passed to
`ItemDefinition.buildItem(ItemBuildContext,int)`. Both packaged reset classes have
SHA-256 `8193914d60d7726fc194cb8154446dda01af71e4213dbf5570f9caa3fd75d243`
and Java class major version 65; unshaded compiler output has SHA-256
`9789c11a0e5234d108295a0ffbd0122debe6d770c6a27adc5bceed292e89c41c`.

Dependency reports do not hash project class directories or cover plugin/buildSrc
resolution and all task inputs. Each also lists an absent `common-files` Java
classes path with a null hash (its compile task is `NO-SOURCE`), misleadingly
labelled `jar` by the report script. Configuration reports do not express the
init script's effective compiler ordering. These are partial snapshots, not a
dependency lock. Native timestamps/builder metadata and mutable/local snapshot
dependencies prevent a promise of byte-identical reproduction. Build claims here
are limited to the genuine successful task logs, exact source diff, descriptors/hashes and actual packaged bytecode inspected. Runtime claims
require the separately generated S05 native trigger evidence; retained provenance's
`validated: false` remains the original builder statement.

## Historical reproduction recipe (not executed during retention)

Use fresh external directories, the original local source repository (upstream:
`https://github.com/Xiao-MoMi/craft-engine`), JDKs 17/21/25 at the paths below,
Gradle wrapper 9.7.1, and the original available Gradle/Maven dependency caches.
Local/snapshot dependencies may need restoring from the recorded external inputs.
This recipe documents the successful online command; `builder-README.md`'s claim
that the successful command used `--offline` conflicts with the successful logs
and provenance. Initial offline attempts failed on missing cached dependencies.

From the Allium repository root, this Bash recipe clones the provider sources
and builds full distributions outside the repository:

```bash
retained=$(realpath tests/live/providers/trigger/builds)
work=/absolute/path/to/new-s05-provider-builds
mkdir -p "$work"
while read -r lane commit; do
  src="$work/$lane/source"
  git clone --shared --no-checkout /mnt/storage/devops/craft-engine "$src"
  git -C "$src" checkout --detach "$commit"
  git -C "$src" config user.name 'Allium S05 validation'
  git -C "$src" apply --check "$retained/reset-preserve-count.patch"
  git -C "$src" apply "$retained/reset-preserve-count.patch"
  (
    cd "$src" || exit
    JAVA_HOME=/usr/lib/jvm/java-25-openjdk bash ./gradlew \
      -I "$retained/compatibility-gson-first.init.gradle" \
      :bukkit:paper-loader:shadowJar --no-daemon --max-workers=2 \
      -Dorg.gradle.jvmargs='-Xmx1024m -XX:MaxMetaspaceSize=512m' \
      -Dorg.gradle.java.installations.paths=/usr/lib/jvm/java-17-openjdk,/usr/lib/jvm/java-21-openjdk,/usr/lib/jvm/java-25-openjdk \
      --console=plain
  ) || exit
done <<'SOURCES'
ce-26.9.1-2b9e6f59 2b9e6f59a0bf4dac7f71958c0ea96c14b8e44985
sf-26.9.2-94b74598 94b74598312d5a16216caed1c07fe3966d854096
SOURCES
```

Outputs are `source/target/craft-engine-paper-plugin-26.9.{1,2}.jar` in the
corresponding lane. `record-resolved-inputs.init.gradle` documents the original
optional `s05ResolvedInputs` tasks for eight modules; it was retained unchanged
and was not invoked during retention.

To check just this small retained set without builds, servers or external inputs:

```sh
(cd tests/live/providers/trigger/builds && sha256sum --check SHA256SUMS)
```

This directory's `SHA256SUMS` is newly generated for retained text files only,
excluding itself. It is not the missing original builder checksum output.
