# Allium addon API

The addon API is how separate plugins (the M5 addons, starting with Curios) build on Allium. It is
deliberately small. It covers what Curios' item engine needs, and more is extracted only when a
real consumer needs it.

## Using it

An addon is an ordinary Bukkit plugin.

```yaml
# plugin.yml
name: Curios
main: example.Curios
api-version: '1.20'
depend: [Allium]          # required: Allium enables first
allium-api: "^1.0"        # the Allium API range this build needs
commands:
  curios:                 # Bukkit registers it; Allium runs it through a command spec
    permission: curios.use
```

```kotlin
// build.gradle.kts
dependencies {
    compileOnly(files("libs/Allium-API-1.0.0.jar"))   // never implementation/shadow
}
```

Extend `AlliumAddon` (recommended):

```java
public final class Curios extends AlliumAddon {
    @Override
    protected void onAddonEnable(AddonConnection allium) {
        allium.items().define(new ItemDefinition("launch_wand", Material.BLAZE_ROD, "&6Launch Wand", null, Map.of(), true));
        allium.commands().register(spec, context -> allium.text().send(context.sender(), "&aHello"));
        allium.scheduler().runOnServerLater(() -> { /* ... */ }, 1_000L);
    }

    @Override
    protected void onAddonDisable() {
        // optional; Allium releases items, commands and scheduled work by itself
    }
}
```

- **The base class:** `AlliumAddon` makes `onEnable` and `onDisable` final.
  - `onEnable` calls `AlliumApi.get().connect(this)` first, and only on success calls
    `onAddonEnable`.
  - A refused addon (wrong API range, shaded API, no declaration) logs Allium's reason and disables
    itself. It never reaches its own code, so it cannot register anything.
- **An addon that extends `JavaPlugin` directly must call `connect` first**, before anything
  else in `onEnable`, and stop if it throws:

```java
@Override
public void onEnable() {
    AddonConnection allium;
    try {
        allium = AlliumApi.get().connect(this);
    } catch (AddonRefusedException | IllegalStateException e) {
        getLogger().severe(e.getMessage());
        getServer().getPluginManager().disablePlugin(this);
        return;
    }
    // only now register anything
}
```

`connect` returns the addon's handle, an `AddonConnection`, with four parts:

| Part | What it does |
| --- | --- |
| `items()` | `define` registers `allium:<id>`, with the behavior `<addon>:<id>`. Every Allium feature can then create it (`/give`, later kits and rewards). `create` builds any reference Allium understands and never falls back to air. `identify` says what a stack is, by its stamped and verified identity, never by its carrier material. |
| `commands()` | `register(spec, handler)` runs a command declared in the addon's `plugin.yml` through Allium's command spec. Allium checks the base permission and completes from the spec's tree. The spec's namespace must be the addon's lowercase name. |
| `scheduler()` | Allium's scheduler boundary (`TaskScheduler`), scoped to the addon. Its server-thread tasks belong to the addon, and everything still scheduled is cancelled when the addon disables. |
| `text()` | Allium's formatter: `&`/`§` codes, `&#RRGGBB`, and `<gradient>`/`<rainbow>`. Paper players get components and others get legacy text, with the same visible result. |

Everything an addon registers is released when it disables: its item definitions, command
executors and scheduled work.

## The API jar

`./gradlew build` writes `build/libs/Allium-API-<version>.jar`. It contains:
- the `codes.castled.allium.api` package;
- the command-spec types in `codes.castled.allium.command.spec` (except Allium's own specs and
  policy);
- `TaskScheduler` and `TaskHandle`.

It holds no implementation, only the abstract `AlliumAddon` base class: `Allium.jar` provides
everything else at runtime.

Before release, the per-addon handle was renamed from `AlliumAddon` to `AddonConnection`, so the
base class could take the name. API 1.0.0 has not been released, so its version is unchanged.

## Versioning

- **A separate version:** the API has a semantic version, separate from the plugin version:
  `alliumApiVersion` in `gradle.properties`, checked against
  `AlliumApiService.API_VERSION`. `AlliumApi#version()` returns it at runtime.
- **Minor releases** (1.1, 1.2, …) only add. Existing interfaces, methods and record components
  keep working, and an addon built for 1.0 runs on any 1.x.
- **Major releases** (2.0) may remove what earlier minors deprecated, and may change signatures.
  An addon declares the majors it supports.
- **Patch releases** change no API surface.
- **Declarations:** an addon declares the range it needs in `plugin.yml`:
  - `"^1.2"` (or `"1.2"`) means `>=1.2.0 <2.0.0`.
  - `">=1.2 <3.0"` is an explicit range: lower bound included, upper excluded.
  - Declare the lowest minor whose features you use.

### Deprecation policy

- **Marking:** a deprecated member is marked `@Deprecated(since = "1.x")`. Its javadoc names the
  replacement and the earliest major that may remove it.
- **Lifetime:** it keeps working for the rest of that major. Removal happens only in a later
  major, and never in a minor or patch release.
- **Release notes:** they list every deprecation and every removal, with its replacement.
- **Behavior:** behavior changes that could break a working addon count as breaking. They wait for
  a major, unless they fix a security problem.

## What Allium refuses

Allium checks every installed plugin once, when Allium itself enables. At that moment the others
are loaded but not yet enabled.

| Case | Diagnostic (logged once at startup) | Result |
| --- | --- | --- |
| The declared range excludes this Allium's API (for example `^2.0` on API 1.0.0) | `Refusing addon X: X needs Allium API ^2.0 (>=2.0.0 <3.0.0), but this Allium provides API 1.0.0. …` | `connect` throws `AddonRefusedException` with the same message, and Allium disables the addon as soon as it enables |
| The addon's jar contains its own copy of the API classes (shaded) | `Refusing addon X: X bundles its own copy of the Allium API (…). Allium refuses it: rebuild it with the Allium API as compileOnly, never shaded.` | Disabled the same way. Its copy of `AlliumApi.get()` cannot find Allium's service anyway |
| `allium-api` does not parse | `Refusing addon X: X declares allium-api: "…", which is not a valid range …` | Disabled |
| No `allium-api` key | none: the plugin is not treated as an addon | `connect` throws `AddonRefusedException`, asking for the declaration |
| Allium is not installed | Bukkit's own message: the plugin `depend`s on Allium | Bukkit does not load the addon |

- **Shading check:** Allium detects shading by asking the addon's own classloader for
  `AlliumApi`, `CommandSpec` and `TaskScheduler`. If any of them comes back as a class other than
  Allium's, the jar bundled it.
- **Why the addon still enables:** Bukkit has no way to veto another plugin's enable, so a refused
  addon's `onEnable` still runs.
  - An `AlliumAddon` stops there: its `connect` fails and it disables itself before its own code.
  - A raw `JavaPlugin` addon that calls `connect` first does the same.
  - One that does not is disabled by Allium right after it enables, but could already have run
    code.
  - Allium and every other addon keep running.

## Installing and updating addons

Installing, updating or removing an addon needs a **server restart**.
- Drop the jar into `plugins/` and restart.
- Allium does not support hot-loading or reloading addons (PlugMan and similar tools are
  unsupported). Its addon check runs once, when Allium enables.
- A plugin enabled later is checked when it first calls `connect`.
- Update Allium and its addons together when a release changes the API major.
