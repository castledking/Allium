# Vendored dependencies

Three `provided`-scope dependencies are not served by any reachable Maven
repository, so they are committed here and installed into the local
repository before a build.

| File | Coordinate | Why it is vendored |
|---|---|---|
| `Relique-2.1.3-mc.26.1.2-alpha.1.jar` | `com.github.darksoulq:Relique:2.1.3-mc.26.1.2-alpha.1` | Relique publishes no `-api` artifact, and its author's repo now 404s for the whole `com/github/darksoulq` path. Used by the trading card equip slot. |
| `AbyssalLib-2.4.0-mc.26.2-alpha.4.jar` | `com.github.darksoulq:AbyssalLib:2.4.0-mc.26.2-alpha.4` | Relique's own compile dependency. Declared explicitly in the pom because the Relique jar carries no transitive graph, so Relique's registry types would otherwise be invisible to the compiler. |
| `decentholograms-2.10.2-local.jar` | `eu.decentsoftware.holograms:decentholograms:2.10.2-local` | Not published for 26.x; a local build of DecentHolograms. Used for the kitchen's pie assembly holograms. |

All three stay `provided`, so none is shaded into `Allium.jar`. Each is only
ever touched behind an `isPluginEnabled` check, which is what lets Allium load
and run on a server without them installed.

sha256:

```
55e6b9b3378b89fa8a9b6bc942af717e0bdbfc4b7954f7291e6b81938987ff95  Relique-2.1.3-mc.26.1.2-alpha.1.jar
606f34068426454954496cb281ffafc643eabab2cc888f3c49fb8df8c5be39dd  AbyssalLib-2.4.0-mc.26.2-alpha.4.jar
0b27cc7e61174dca6907976d01d4b010a0ff64ee1e146e68010a118a3255cddf  decentholograms-2.10.2-local.jar
```

## How they get into a build

`libs/dependencies.txt` lists the coordinates. `libs/install.sh` reads it and
runs `mvn install:install-file` for each jar; `.github/workflows/build.yml`
runs that script before compiling.

Locally, run `./libs/install.sh` after changing anything here — or after
changing a version — so a bad entry fails on your machine rather than in CI.
It fails loudly on a missing jar, so a typo in the manifest is caught before
a push.

## Adding or upgrading a dependency

Three places have to agree: the file in this directory, the coordinate in
`pom.xml`, and the row in `libs/dependencies.txt`.

1. Drop the new jar in here as `<artifactId>-<version>.jar`.
2. Add or update the `<dependency>` in `pom.xml` — keep `<scope>provided</scope>`.
3. Add or update the row in `libs/dependencies.txt`. The file name is
   relative to the repository root.
4. Run `./libs/install.sh && mvn install -DskipTests` to confirm.
5. Update the sha256 list above.

If you swap Relique, check whether the AbyssalLib version has to move with it.