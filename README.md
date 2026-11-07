# Allium

A configurable essentials suite for Bukkit/Spigot and Paper. This repository is
the recode of [the current Allium](../Allium), with shared item-provider support
and separately installed gameplay addons.

**Status:** foundation and five basic commands exist. The target is Minecraft
**1.20–26.3**; that range is a release goal, not a verified compatibility claim.

Start with the [implementation roadmap](docs/roadmap.md) and
[working checklist](todo.md). They distinguish existing code, planned behavior,
implementation order and acceptance gates.

## Core and addons

Core includes essentials commands, permissions, config migrations, messages and
sounds, player preferences, storage, signs, optional timed broadcasts, and the
shared item/resource-pack services. Handcuffs, Mob Disarmer, Phantom Obliterator,
renaming and the staff CrowBar locator feed remain core features. The default
distribution includes a resource pack for core tools and works without an external
item plugin. Nexo, CraftEngine and Oraxen are optional providers used consistently
wherever a feature accepts or produces items.

Give, Enchant, Skull and Potion will share a reusable item-building library bundled
inside core. Commands and addons use one API; Allium remains the only required
plugin, with no separate library plugin or required item editor.

Gameplay systems ship separately:

- Fun Items: configurable actions, cooldowns and novelty items inspired by ItemTag.
- Reliques: equipment, slots and effects.
- Harvest: crops, soil, growth, fertilizers and sprinklers.
- Kitchen: cooking and preparation; depends on Harvest.
- Pregen Ores: generation jobs and ore placement.
- SpawnerCraft: head/core recipes and typed spawners.
- Trading Cards: collection, boosts and upgrades.
- Party Locator: parties and the locator bar.

The planned installation uses ordinary Bukkit plugins, each depending on Allium:

```text
plugins/
  Allium-<version>.jar
  Addon-Harvest-<version>.jar
  Addon-Kitchen-<version>.jar
  Addon-FunItems-<version>.jar
```

Other addons follow the same naming. Publish them as assets on the same GitHub
release, with compatible API metadata. This replaces the earlier custom
`plugins/Allium/addons/` loader proposal. Addon loading is not implemented yet;
the existing runtime config still contains older placeholder keys. Automatic
downloads remain deferred and off by default.

Allium will not build a dialog system; CommandPanels handles dialogs.
Mod-loader builds and a client companion are deferred
outside the server-plugin roadmap. The [Harvest design](docs/harvest-addon-plan.md)
retains the crop-rendering investigation, with Kitchen separated into its own addon.

## Implementation order

1. **M0:** reproducible build, Spigot baseline, dependency audit and compatibility checks.
2. **M1:** minimal shared API, player storage, command ownership, all item providers,
   default pack and a working Mob Disarmer through the shared service.
3. **M2:** private messages, persistent preferences, language sound defaults and `/mfx`.
4. **M3:** teleportation/homes/warps, atomic economy, `/paytoggle`, `/tptoggle` and kits.
5. **M4:** complete core utilities, staple tools, renamer, CrowBar, signs and timed broadcasts.
6. **M5:** extract and ship addons against proven APIs.
7. **M6:** migration rehearsal, profiling, full compatibility validation and release.

The roadmap defines command semantics, data contracts and completion gates. Core
alpha does not wait for every addon. Import planning starts with M0.

## Existing foundation

- Gradle Kotlin DSL with Shadow packaging.
- Module, command and listener managers.
- Config loading, backup-before-upgrade, migrations and missing-key insertion.
- Locale files in `lang/`, a `Messages` enum and English fallback.
- Native static text formatting and sound playback; Takion has been retired.
- HikariCP database setup for MySQL/SQLite and economy/Vault scaffolding.
- Implementations of `/heal`, `/feed`, `/fly`, `/god` and `/gamemode`.

These are scaffolding and implemented code paths, not a production-readiness claim.
Transactions, asynchronous persistence, provider readiness and lifecycle cleanup
need the verification described in M0–M4.

## Building

```bash
./gradlew clean build
```

Shared code compiles against **Spigot 1.20** and emits **Java 17** bytecode.
The three optional provider source sets compile against pinned Java 21 APIs but
also emit Java 17 bytecode; install JDK 17 and 21 for the configured toolchains.
Provider tests run on Java 21. Output is `build/libs/Allium.jar`.

Provider API pins, actual server runs and open validation rows are documented in
[the S05 matrix](docs/s05-provider-matrix.md). Native CE reset quantity checks
require the recorded count-preserving source patch: stock 26.9.1/26.9.2 lose items.
The patched runtime gate and its exact artifact hashes are recorded separately
from stock results. Supplied Nexo 1.30-dev-36f4ce686 and Oraxen 1.220.1 are
explicitly runtime-verified builds. The Nexo 1.29.x and Oraxen 1.211.x API lines
are compile-only, not runtime-verified support claims; see the matrix for the
separate source-built Oraxen experiment.
The Mob Disarmer (S06a) passed its live gate on Spigot 1.20.1, Paper 1.21.8 with all
three providers, and Paper 26.3 with GriefPrevention3D; see
[the S06a results](tests/live/tools/disarmer/RESULTS.md). The optional GriefPrevention hook
compiles against the public GriefPrevention 16.18.2 API (Java 17 bytecode). It loads only
when GriefPrevention or GriefPrevention3D is enabled.
Core remains the only required plugin; external providers are compile-only and
are not bundled. Current bundled libraries are HikariCP 5.1.0 and InvUI 1.49.
InvUI's 26.x GUI compatibility remains an explicit later-slice risk.

Servers need the Java version required by their own release and installed provider
plugins. The shared Java 17 bytecode target does not lower server/provider runtime
requirements. See the [compatibility plan](docs/roadmap.md#runtime-and-compatibility).

## Source layout

```text
src/main/java/codes/castled/allium/
  PluginStart.java
  command/                 Command registration and current essentials executors
  core/                    Module lifecycle
  module/                  Essentials and economy modules
  config/                  Loading, backup, migrations and default-key sync
  database/                JDBC setup and economy table
  message/                 Message keys, locales and delivery
  listener/                Listener registration and god-mode handling
  protection/              ProtectionService, hooks and the cancellable check event
  tool/disarmer/           Mob Disarmer behavior, settings and listener
  compat/                  Compatibility interfaces/providers
src/*Adapter/             Isolated Nexo/CraftEngine/Oraxen API source sets
src/griefpreventionHook/   Isolated GriefPrevention claim hook
src/main/resources/        plugin.yml, config.yml and lang/
allium-core/               Platform-neutral command specs, item identity/signing and tool policy
```

## License

Project license remains undecided. Essentials and ItemTag are GPLv3 reference
projects; resolve licensing and attribution before directly incorporating their
source or configuration prose. Server asset exports must retain provenance and
redistribution permissions.
