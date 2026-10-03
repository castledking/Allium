# Vendored dependencies

Two `provided`-scope dependencies the trading card module compiles against
are not published to any reachable Maven repository. Relique publishes no
`-api` artifact, and the `com/github/darksoulq` path on its author's Maven
repo (`https://croabeast.github.io/repo/`) now returns 404 for both of these,
including the directory and the metadata. They are committed here so a clean
checkout and a CI runner both build, and the build workflow installs them
into the local repository with `mvn install:install-file` before compiling.

Neither jar is shaded into `Allium.jar` — both stay `provided`, because
Allium only ever touches them behind an `isPluginEnabled` check. That is what
lets Allium load and run on a server without them installed.

| File | Coordinate | sha256 (first 16) |
|---|---|---|
| `Relique-2.1.3-mc.26.1.2-alpha.1.jar` | `com.github.darksoulq:Relique:2.1.3-mc.26.1.2-alpha.1` | `55e6b9b3378b89fa` |
| `AbyssalLib-2.4.0-mc.26.2-alpha.4.jar` | `com.github.darksoulq:AbyssalLib:2.4.0-mc.26.2-alpha.4` | `606f340684264549` |

## Upgrading

The version appears in three places that must agree: the pom coordinate, the
file name in this directory, and the `install:install-file` arguments in
`.github/workflows/build.yml`. Renaming a jar without updating the workflow
fails the build on a missing file, and bumping the pom without renaming fails
it on an unresolvable coordinate.

1. Drop the new jar in here, named `<artifactId>-<version>.jar`.
2. Update the `<version>` (and `<artifactId>`, if it changed) in `pom.xml`.
3. Update the `-Dfile`, `-DgroupId`, `-DartifactId`, `-Dversion` arguments in
   the workflow's "Install vendored Relique and AbyssalLib" step.
4. Confirm the sha256 row above is still accurate for the files in this
   directory.

AbyssalLib is Relique's own compile dependency. It is declared explicitly in
the pom because the Relique jar carries no transitive graph of its own, so
Relique's registry types would otherwise be invisible to the compiler. If you
swap Relique, check whether the AbyssalLib version has to move with it.