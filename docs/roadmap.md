# Allium recode roadmap

Status: implementation plan, 2026-11-05. Features below are planned unless the
baseline explicitly says they exist. This is the source of truth for scope and
sequencing; [the TODO](../todo.md) is the working checklist. This plan supersedes
older proposals that bundled Kitchen into Harvest, made the staple tools addons,
or required a custom addon classloader.

## Product boundary

Allium is a configurable essentials suite for Bukkit/Spigot and Paper, targeting
Minecraft **1.20 through 26.3**. A normal installation gets useful essentials,
shared custom-item support, the staple tools, and a default resource pack.
Vanilla clients can join. Nexo, CraftEngine, and Oraxen are optional integrations,
available throughout core and addons wherever items are accepted or produced.

| Ship in core | Ship as a separate addon |
| --- | --- |
| Commands, permissions, config/migrations, locale/messages/sounds, scheduler, player preferences, storage and public API | Curios: custom items with actions (the former Fun Items), custom enchantments, and equipment slots with their UI and loot (the Reliques port), on one engine; each part can be turned off |
| Homes, warps, spawn, teleport requests/back, kits, economy, chat/private messages/mail, moderation and staff utilities | Harvest: crops, soil, growth, fertilizers, sprinklers and crop rendering |
| Item references, provider adapters, serialization, matching and pack coordination | Kitchen: cooking, food preparation, stations and bags; depends on Harvest |
| Handcuffs, Mob Disarmer, Phantom Obliterator, Lazy Axe (off by default), rename service and consumable renamer | Pregen Ores: terrain jobs and custom ore placement, separately configurable |
| Staff CrowBar feed, independent of parties | SpawnerCraft: cores, heads and spawner recipes |
| Signs, optional timed broadcasts, diagnostics and import tools | Trading Cards: collection, boosts, upgrades, trade-ins and morphing |
| Core pack assets for core tools; provider bindings are configurable | Party Locator: membership, party locator bar and optional mcMMO sync |
| Shared item/provider and location-visibility contracts | |

Core modules can be disabled; addon implementation and assets are absent from the
core artifact. SpawnerCraft remains its own addon even if small. Kitchen is never
required to use Harvest. Optional cross-addon integrations must not create
dependency cycles. Trading Cards' Curios equipment slot, Harvest boosts and party radius
hooks activate only when those APIs are available; standalone cards retain their
base functionality.

Allium will not provide a dialog framework or generate dialog datapacks.
CommandPanels owns dialogs. Commands and inventory interfaces remain available
without it.
Mod-loader artifacts and a client companion are deferred
outside this Bukkit/Spigot release roadmap.

## What exists today

The working tree already contains significant uncommitted recode work. Preserve
it; the legacy repository is a reference, not a directory to copy wholesale.

- `PluginStart`, module/command/listener managers, config backup/key sync,
  locale fallback, database setup and basic message delivery exist.
- `/heal`, `/feed`, `/fly`, `/god` and `/gamemode` have implementations (verified
  across `self`, `other`, and `console` execution on all tested 1.20–26.3 lanes).
- Economy/Vault scaffolding exists, but SQL is synchronous and balance updates
  use read/modify/write. It is not a safe transaction foundation for `/pay` yet.
- `allium-core` houses the platform-neutral domain model and command specification
  with zero Bukkit/Spigot/Paper dependencies, enforced by the Gradle build.
- **S01 baseline (completed):** Gradle 9.5.1 is pinned with Java toolchains; shared
  code compiles against Spigot `1.20-R0.1-SNAPSHOT` with Java 17 bytecode (class
  version 61); `plugin.yml` declares `api-version: '1.20'`; pre-1.20 compatibility
  sources have been retired.
- S05 provider adapters and the recorded native gates pass for their explicit
  configurations, including count-preserving source-built CE runtimes. Stock
  CE reset updaters still lose stack quantity; deployment requires the verified
  fix and documented retention/converter prerequisites. See the
  [pinned provider matrix](s05-provider-matrix.md).
- **S06a (behavior only):** the Mob Disarmer (`allium:mob_disarmer`, signed but not privileged)
  works with its built-in item and with Nexo, CraftEngine and Oraxen appearances. Protection runs
  through a small `ProtectionService` with a GriefPrevention/GriefPrevention3D claim hook and a
  cancellable `AlliumProtectionCheckEvent`. Live results are in
  [the S06a results](../tests/live/tools/disarmer/RESULTS.md). There is no give command yet.
- **S06b (pack generation only):** the core resource pack is generated from
  [`pack/manifest.json`](../pack/README.md) at build time, byte-identically, and bundled in the jar.
  Built-in disarmers show their charge state by `custom_model_data` model selection on the same
  golden-hoe stack (1.20 overrides; 1.21.4+ item definitions in an overlay). Allium does not stamp
  `item_model`, so players who decline the pack keep a named vanilla golden hoe. The SF inventory is
  in [`s06b-sf-inventory.md`](s06b-sf-inventory.md). Delivery is S06c (below). Live results are in
  [the S06b results](../tests/live/pack/RESULTS.md).
- **S04 follow-up (item library + M1 `/give` harness):** item specs parse through a
  bracket/brace/quote-aware grammar over `ProviderKey`, build through `ItemService` (so Allium
  and provider items are stamped and signed) or the version-split `ComponentAdapter`, and are
  delivered with an overflow policy, target revalidation and a cancellable `ItemGiveEvent`.
  See [the give design status](give-item-design.md) and
  [the results](../tests/live/items/RESULTS.md).
- **S03 (player preferences):** per-UUID storage with schema migrations, configured defaults
  that are never written, bounded storage lanes and sessions that load before anything is
  enforced. Covers the three accept flags and the message sound volume with its restoration
  state. See [the results](../tests/live/preferences/RESULTS.md). `/msgtoggle` arrived with S07;
  the sound commands arrive with S08.
  - S03 follow-up: failed loads retry in the background with capped backoff, and unloaded or
    failed preferences fail closed (`acceptance` is `UNAVAILABLE`; see the contract below).
    Shutdown drains with a configurable timeout and names every write it could not save. The
    message-sound math (`SoundLevels` in `allium-core`, `MessageManager#playNotificationSound`)
    follows the contract below.
- **Scheduler boundary (M1 close-out):** `TaskScheduler`, implemented by `AlliumScheduler` in
  `allium-core`, is the one owner of Allium's work:
  - server-thread tasks, through `BukkitServerThread`, the only class that touches
    `BukkitScheduler`;
  - delayed async follow-ups on one `Allium-Timer` thread;
  - named bounded worker pools.

  `PluginStart#getScheduler` hands it to every slice, and `PluginStart` closes it last.
  `SchedulerBoundaryTest` fails the build if production code creates threads or executors, or
  uses the Bukkit scheduler, anywhere else. It does not make Allium Folia-ready; that still
  needs per-feature thread-ownership tests.
- **M1 status:** see the [M1 status in the checklist](../todo.md#m1-status-updated-2026-11-08).
  Every deliverable, including pack delivery, has recorded evidence, and all five gates are met.
  The S06b visual check is pending in a real client.
- **S09 (minimal addon API):** `allium-api` 1.0.0, with item definitions and create/identify,
  command specs, the scheduler boundary and text. Addons declare `allium-api` ranges in
  `plugin.yml`; mismatched and shaded addons are refused. See
  [the addon API](addon-api.md) and [the results](../tests/live/addons/RESULTS.md).
- **S06c (pack delivery):** `resource-pack.owner` `standalone` exports the pack and sends an
  operator-hosted URL, verified against the bundled SHA-1. `rpm-export` writes into
  ResourcePackManager's mixer and reports carrier collisions. From 1.20.3 Allium's pack is added
  by UUID beside other packs. See [pack delivery](pack-delivery.md) and
  [the results](../tests/live/delivery/RESULTS.md).
- **S07 (private messaging):** `/msg`, `/reply`, `/ignore`, `/unignore` and `/msgtoggle`. They
  share one policy, rechecked at delivery: acceptance (failing closed), ignore, and mute and vanish
  hooks. Blocked sends are silent for the recipient and look the same to the sender, and message
  bodies are untrusted input. See [messaging](messaging.md) and
  [the results](../tests/live/messaging/RESULTS.md).
- **S08 (message sounds):** `/messagesounds` (`/msounds`, `/mfx`) with `volume`, `toggle` and
  `reset`.
  - **Persistence:** the setting and its restoration state survive restarts.
  - **Reload:** `/allium reload` applies new `sounds.*` values and preference defaults at once.
  - **Lang entries:** an entry's omitted volume and pitch fall back separately (and lang sync no
    longer puts them back), and `sound: none` turns a sound off.
  - **Import:** a dry-run-first `/allium import lang [apply]` imports a pre-recode `lang.yml`.
  - See [message sounds](sounds.md), [lang import](lang-import.md) and
    [the results](../tests/live/sounds/RESULTS.md).
- Social spy, `/paytoggle`, `/tptoggle` and the M5 addons are not implemented in this recode
  yet. Some config module names are placeholders; do not count them
  as working features.

## Architecture decisions

### Platform-neutral module rule (`allium-core`)

Domain logic, value objects, cryptographic item verification, and the command
specification (tree, arguments, permission nodes, suggestion sources, message keys,
and `disabled-commands` family policy) live in `allium-core` with **no Bukkit,
Spigot, or Paper dependency**, enforced by `allium-core/build.gradle.kts`.
`ProviderKey`, `ItemRef` (minus `ItemStack`-bound code), `ResolutionStatus`,
`ProviderStatus`, `ItemHmacSigner`, and `LegacyItemMetadata` live in `allium-core`;
Bukkit `ItemStack`/PDC adapters and command executor/completer renderers live in
the plugin module.

### Runtime and compatibility

Use the **Spigot 1.20 API and Java 17 bytecode** for the shared plugin baseline.
Use a pinned Gradle wrapper and Java toolchains rather than whatever JDK happens
to launch Gradle. Audit shaded libraries for Java 17 compatibility, including
InvUI and any version-specific internals. Build newer adapters with their own
toolchains only if required; load them behind capability checks.

Keep Paper-only types out of shared signatures, fields and eagerly loaded
classes. Paper conveniences, modern item components and locator packets belong
behind narrow adapters. Do not use NMS or reflection as the default architecture.
Retire pre-1.20 compatibility work after preserving any reusable behavior.

Runtime lanes to verify are Java 17 for early 1.20, Java 21 for 1.20.5/1.20.6 and
1.21, and Java 25 for 26.1+. Paper currently recommends Java 21 for its 1.20–1.21
line and requires Java 25 for 26.1+; see [Paper's requirements][paper-java].
Verify exact Spigot/server requirements when pinning the matrix. Java 17 output
does not imply newer servers can run on Java 17.

#### Recorded S01 / S02 / S04 Server/JDK Baseline & Smoke-Test Matrix

| Lane | Server Build | Runtime JDK | No-Provider Boot & Lifecycle | `/heal`, `/feed`, `/fly`, `/god`, `/gamemode` (`self`, `other`, `console`) | S02 Command-Tree Packets, Suggestions & `knownCommands` Access | S04 Item Contract, State HMAC & Keyring |
| --- | --- | --- | --- | --- | --- | --- |
| Minimum (Spigot 1.20.1) | Spigot `3871-Spigot-d2eba2c-3f9263b` (MC 1.20.1) | OpenJDK 17 (`java-17-openjdk`) | **PASS** — clean enable, SQLite connect, modules loaded, clean disable | **15/15 PASS** | **PASS** (`Server#getCommandMap()` -> `v1_20_R1.command.CraftCommandMap#getKnownCommands()`, `syncCommands()` + `player.updateCommands()`) | **PASS** (`s04:privileged-hmac-valid`, `s04:privileged-hmac-resigned-on-setState`, `s04:privileged-hmac-state-tamper-rejected`) |
| Minimum (Paper 1.20) | Paper `git-Paper-17` (`c287e92`, MC 1.20) | OpenJDK 17 (`java-17-openjdk`) | **PASS** — clean enable, SQLite connect, modules loaded, clean disable | **15/15 PASS** | **PASS** | **PASS** |
| Intermediate (Paper 1.20.6, NBT-to-component & InvUI `r19+` boundary) | Paper `1.20.6-151-a4f0f5c` (MC 1.20.6) | OpenJDK 21 (`java-21-openjdk`) | **PASS** — clean enable, SQLite connect, modules loaded, clean disable | **15/15 PASS** | **PASS** (`Server#getCommandMap()` -> `craftbukkit.command.CraftCommandMap#getKnownCommands()`, Paper Brigadier `BukkitCommandNode` updated via `syncCommands()` + `player.updateCommands()`) | **PASS** (`s04:privileged-hmac-valid`, `s04:privileged-hmac-resigned-on-setState`, `s04:privileged-hmac-state-tamper-rejected`) |
| Maximum (Spigot 26.3) | Spigot `4660-Spigot-766de51-bd6d9dc` (MC 26.3) | OpenJDK 25 (`java-25-openjdk`) | **PASS** — clean enable, SQLite connect, modules loaded, clean disable | **15/15 PASS** | **PASS** | **PASS** |
| Maximum (Paper 26.3) | Paper `26.3-141-54024d3` (`world_version: 5023`, protocol 777) | OpenJDK 25 (`java-25-openjdk`) | **PASS** — clean enable, SQLite connect, modules loaded, clean disable | **15/15 PASS** | **PASS** (`Server#getCommandMap()` -> `craftbukkit.command.CraftCommandMap#getKnownCommands()`, Paper Brigadier `BukkitCommandNode` updated via `syncCommands()` + `player.updateCommands()`) | **PASS** (`s04:privileged-hmac-valid`, `s04:privileged-hmac-resigned-on-setState`, `s04:privileged-hmac-state-tamper-rejected`) |

The original S02 smoke harness inspected server command maps/dispatchers with proxy
players. It did not prove client updates. The corrective follow-up additionally
connects real offline protocol clients to loopback disposable servers at Spigot
1.20.1, Paper 1.20.6 and Paper 26.3: before/after command-tree packets and suggestion
responses pass. Evidence and reproducible validation code are in
[`tests/live/command-client/`](../tests/live/command-client/). Paper 26.3 uses a
minimal protocol-777 client with packet IDs obtained from the exact server's own
protocol details, not a guessed older-version schema. This verifies wire behavior;
it is not a screenshot-based human-client test.

#### Open M4 Risk: InvUI on 26.x

InvUI `1.49`'s `InventoryAccessRevision` compares major version integers (`26 > 1`),
which causes it to select `R26` (built for `1.21.11` NMS internals) when running on
Minecraft `26.x`. Matching `26 > 1` runs a `1.21.11` NMS revision against `26.x` and
is **not** real support. **Required action before any GUI slice (M4/M5):** check
upstream InvUI for `26.x` support; if none exists, place the GUI layer behind an
adapter with a plain Bukkit inventory implementation fallback.

At minimum, run real-server checks on Spigot and Paper at 1.20, 1.20.4, 1.20.6,
1.21.1, 1.21.4, the final 1.21 patch, 26.1, 26.2 and 26.3. Pin exact build IDs
and expand coverage at API breaks. Publish support only for tested lanes.
Locator HUDs and modern visuals need capability-specific fallbacks; their
absence must not prevent core essentials from loading. Provider versions have
their own supported server ranges. Folia is deferred until thread ownership is
tested across every enabled feature; a scheduler wrapper alone is insufficient.

### Services, persistence and lifecycle

Keep domain decisions separate from Bukkit commands/listeners and JDBC. Use
constructor injection and small services with explicit ownership; avoid growing
the plugin entry point into a service locator used by all gameplay code.
Extract APIs when a concrete consumer needs them, not a framework for every class.

Use UUIDs for player identity. Preferences, requests, balances and item state have
explicit schemas and migrations. Database/file work runs on bounded executors;
Bukkit entity/world/inventory operations run on the server thread. All of it goes
through `TaskScheduler`: `runOnServer`, `runAsyncLater` and `boundedPool`. No slice
creates its own threads or uses the Bukkit scheduler directly. SQLite has a
serialized write path; MySQL has proper transactions. Money uses a declared scale
and rounding policy, atomic conditional withdrawals, and transactional transfers.
Vault's synchronous API needs an explicit consistency strategy and measured
behavior; do not return success before a durable operation is secured.

Startup builds validated configuration snapshots before enabling services.
Failures disable the affected capability with a useful diagnostic. Shutdown
unregisters commands/listeners/services, cancels tasks, restores temporary player
state and drains bounded persistence work with a configurable timeout (for
preferences, `preferences.storage.shutdown-timeout-millis`). On timeout, every
write that was not saved is logged by name, and shutdown continues. Config reload validates before swapping
and keeps the old configuration on failure. Addon installation/removal requires
restart; do not promise arbitrary plugin hot loading.

### Addon packaging

Use ordinary Bukkit plugin jars in `plugins/`, with `depend: [Allium]` and a
versioned Allium API. Kitchen additionally depends on Harvest. Bukkit owns
classloading and lifecycle; Allium supplies registration contracts and compatibility
checks. This replaces the earlier `plugins/Allium/addons/` custom-loader proposal.
API compatibility checks still run with native plugin loading.

As built in S09 ([addon API](addon-api.md)):
- Addons compile against `Allium-API-<apiVersion>.jar` with `compileOnly`.
- Each declares `allium-api: "<range>"` in its `plugin.yml`.
- Allium checks every plugin once, when Allium enables. It refuses a range that excludes its API
  version, and a jar that bundles the API classes; Allium and the other addons keep running.
- The API version is semantic and separate from the plugin version. Deprecated members survive
  until the next major.
- Installing, updating or removing an addon needs a server restart.

Publish `Allium-<version>.jar`, `Addon-Curios-<version>.jar`, `Addon-Harvest-<version>.jar`,
`Addon-Kitchen-<version>.jar`, `Addon-PregenOres-<version>.jar`,
`Addon-SpawnerCraft-<version>.jar`, `Addon-TradingCards-<version>.jar` and
`Addon-PartyLocator-<version>.jar` as assets on the same GitHub release.
Version-lock first-party release sets initially and expose a separate API version.
Core must not shade addon implementations. Automatic downloads are deferred and
off by default; a future installer can stage verified artifacts for a restart.
Manual installation remains sufficient.

### Items and packs are core infrastructure

Define one `ItemRef`/`ItemService` contract for creation, identification, matching,
cloning, serialization and diagnostics. All item-bearing features use it: give,
kits, GUIs, signs, recipe ingredients/results, rewards, loot and addon definitions.
Do not silently match a provider item as its vanilla carrier material.

Library-ize the Give/Enchant/Skull/Potion implementation as a reusable build
module bundled in core, not another required Bukkit plugin. Expose a narrow item
construction/modification API: immutable definitions, provider/material lookup,
enchantments, player/profile/custom-texture heads, potion types/effects, metadata,
validation, serialization and delivery outcomes. Command-specific permissions and
sender feedback stay in thin executors. Kits, rewards, signs and addons reuse the
same builders instead of parsing command strings or invoking `/give` internally.
Share grammar and suggestions where appropriate without tying library consumers
to player permissions or UI text. Inventory overflow and delivery failure are
explicit outcomes, not silent drops. Resolve remote head profiles off-thread with
bounded caching/timeouts, then mutate inventory on-thread. Isolate version-sensitive
enchantment/potion registries and profile APIs behind adapters. Addons compile
against the core API and never shade another copy; core is the only required
plugin. Optional provider plugins add item sources, not baseline functionality.
Extract only cohesive existing logic; do not make all commands depend on an
unrelated external item editor or a new framework.

Canonical references are `minecraft:stone`, `allium:handcuffs`,
`nexo:mob_disarmer`, `oraxen:item_renamer` and
`craftengine:sf_ores:silver_ingot`. Split at the first colon so CraftEngine retains
its namespace and local ID. Import legacy `vanilla:`, `ce:` and known CE pack
namespace forms explicitly; ambiguous old references require a mapping report.
At runtime, deprecated `ce:` input warns **once per distinct reference per startup
or reload** rather than on every resolution. The existing resolver in legacy Allium
supports Nexo and CraftEngine but does not have an active Oraxen implementation;
The recode now has a real isolated Oraxen adapter, with native validation for the
supplied 1.220.1 runtime. The [S05 matrix](s05-provider-matrix.md) records coexistence
requirements and the remaining native CE reset quantity failure.

Separate stable **Allium behavior identity** from the provider reference supplying
its appearance. Selecting `nexo:handcuffs` must not require a second implementation
of restraint logic. Preserve provider metadata and Allium state during provider
updates. Names/lore/model data alone never grant privileged item behavior.
Because PDC tags are forgeable by creative clients and `/give` component syntax,
stamped PDC and serialized `ItemRef`s carry an explicit `schema_version` (`1`),
and `AlliumItemDefinition` declares `privileged = true|false` (`handcuffs` and
`phantom_obliterator` are privileged). Privileged behaviors require a valid
server-secret `HmacSHA256` signature (`v2:<keyId>:<64-char-hex>`) over
`(provider, itemId, behaviorId, instanceUuid, canonicalState)` stored in PDC
(`allium:instance_uuid`, `allium:signature`), where `canonicalState` is a
lexicographically key-sorted encoding of `allium:state_*` (`ItemHmacSigner#encodeCanonicalState`).
Definitions can also be `signed` without being privileged (`mob_disarmer`): new stacks carry
an instance UUID and MAC, a present MAC is always verified, and an unsigned stack of that
behavior is still accepted. Appearance refreshes keep a verified MAC and never attach a
privileged behavior the source stack lacked. Every signed/privileged state mutation
(`ItemService#setState`, `ItemService#updateState`) first verifies the original identity/state, then re-signs the stack with the active key. Rejected items remain unchanged. Directly editing `allium:state_*` in PDC invalidates verification. Keyring secrets (`active`
key for signing plus `retired` keys valid for verification) are generated on first
run and stored outside `config.yml` (`plugins/Allium/.item-hmac-secret`, `0600`
permissions) so they are never leaked when configs are posted; multi-server networks
that share inventories share the same `.item-hmac-secret` file. Items carrying an
unknown `keyId` log a loud `CRITICAL HMAC KEY MISMATCH` diagnostic instead of
degrading silently. Signing prevents minting and editing of privileged items and
their state, **not** creative-mode stack duplication (granting privileged items in
creative mode is a permission concern). Legacy `allium:custom_item_id` and
`alliumcards` PDC tags are read-only for the importer (`ItemService#readLegacyMetadata`)
and never grant runtime privileged behavior prior to migration.

Providers report absent/loading/ready/failed states. Wait for their item-ready and
reload events, invalidate caches, and keep persisted state when definitions are
temporarily unavailable. Unresolved ingredients/results disable the affected
recipe or reward with its config path; never substitute AIR or discard crops.
Optional API classes are compiled against provided dependencies and loaded only
when a compatible provider is available. No provider is required for core tools.

Generate a default core resource pack from an asset manifest, with stable IDs and
model allocations for pre-component and modern item-model formats. Package core
tool assets with the default distribution; addon packs contain addon assets.
The observed `$SF` deployment has Nexo definitions in
`Nexo/items/sf/items.yml`, textures under `Nexo/pack/assets/sf/`, and an Allium pack
in ResourcePackManager's mixer. Treat these as migration input. Inventory IDs,
charge variants, model allocations and asset provenance before export. Some
existing YAML has duplicate `itemname` keys; normalize rather than blindly copy.

Support a standalone configured URL/hash and merging through ResourcePackManager
or a provider pack pipeline. Select one delivery owner to avoid duplicate pack
pushes. Report conflicts, unsupported pack formats and missing assets. Never
rewrite another plugin's configuration silently. Players declining the pack keep
working vanilla-item fallbacks unless the server explicitly requires the pack.
Ship distributable assets only; provide a local export recipe for server-only art.

### Text formatting and animated text

Allium owns its text formatting, color parsing, sound playback and animated text
subsystem in core, retiring Takion (`me.croabeast.takion:shaded`) as a dependency.
Static formatting (`S01.5a`) covers legacy `&`/`§` codes, `&#RRGGBB`/`§x` hex, and
multi-stop `<gradient:...>` and `<rainbow:...>` tags, emitting Adventure
`Component`s on Paper (compiled against Paper's bundled `net.kyori:adventure-api`
as `compileOnly`, never shaded or relocated; `Allium.jar` contains **zero**
`net.kyori` classes) and legacy `§x` strings on Spigot (via Allium's own
`FormattedText#toLegacy`) with identical visible results.
Animated text (`S01.5b`, deferred until the first animated target) evaluates
`pulse`, `shift`, `sparkle`, and `phase-scroll` effects as pure deterministic
functions `color(glyphIndex, length, timeMs, seed) -> RGB` on one shared clock and
bounded scheduler with frame caching and delta-only pushes. See the
[Text Formatting & Animation Design](text-formatting-design.md) for the full
specification and the `UnlimitedNametags` phase-animation reference analysis.

## Required behavior contracts

### Command ownership and signs

`disabled-commands: []` controls Allium command families. A canonical entry such
as `msg` disables its aliases and `allium:msg` forms too. Normalize case and strip
an optional slash; reject unknown entries with diagnostics. Allium must neither
execute a disabled family nor reserve its usable labels from another plugin.
Test Bukkit's command-map ownership and collisions on real Spigot and Paper.
After `CommandMapBridge` mutates `knownCommands`, it invokes `CraftServer#syncCommands()`
and calls `player.updateCommands()` for all online players so client-side Brigadier
tab completion updates immediately. All reflection in `CommandMapBridge` is isolated
in a single guarded accessor (`CommandMapBridge.GuardedAccessor`) with a clear
diagnostic on failure. Keep disabled-command registration changes restart-scoped
initially; config reload can immediately guard dispatch but must report when a
restart is needed to yield labels. Do not implement a global command blocker by accident.

Mail/gift persistence is implemented through conditional ownership and player-data receipts.
Before a receipt advances database escrow/claim state, verify its saved player-data contents;
`Player.saveData()` returning alone is insufficient because platform save failures may be logged
internally. Recovery must verify any same-session receipt too, retain it on database read failure,
and require immutable acknowledgement when a row has already been removed. Cross-server mail
acceptance/ignore enforcement reads authoritative values as well as pending local refusals. Keep
playerdata, database, server ownership ID and signing keys in a coordinated M6 rollback snapshot;
SQLite uses online backup or `VACUUM INTO`, never a raw copy of an open WAL database. See
[mail contracts and import mapping](mail.md).

Every player-target argument has position-aware tab completion, including
`/mail gift <player>` and subsequent gift arguments. Filter by sender permission,
vanish/visibility and prefix; support quoted/multiword arguments where needed.
Mail may target offline users, so use a bounded cached name/UUID index instead of
disk/SQL/network lookups for each keystroke. Avoid enumerating all offline players
on the main thread. Test aliases, empty/partial names, console, insufficient
permissions and multiple argument positions. Completion must not reveal hidden
staff or recipients the command does not permit.

Use familiar Essentials names `enabledSigns: []` and `sign-use-per-second`.
Start with `color` and `warp`, then `kit`, `balance`, `heal`, `repair`, `disposal`,
`workbench`, `anvil`, `mail` and `info`. Buy/sell/trade follow after transaction
safety. Import `colour` as `color`; document key normalization.

Sign types have separate create/use/break permissions and a shared per-player
click budget. Use server-owned PDC for actionable identity, not colored headers.
Handle front/back, hanging/waxed signs, chunk reload and destruction. Respect
protection cancellations, process one permitted hand, and prevent duplicate
transactions. Sign actions call the same service policies as commands: warp
permissions, kit cooldowns, mute state, costs and destination safety still apply.
Disabling a command affects command entry points; disabling its module also
disables related signs. Public sign access is explicit and cannot silently bypass
per-warp permissions via a legacy `Everyone` line.

### Player preferences and notification sounds

Persist `accept-messages`, `accept-payments`, `accept-teleport-requests` and an
optional `message-sound-volume` per UUID. New players inherit configured defaults.
All three toggle commands use **on = accept, off = reject**, with clear responses;
the Essentials importer must convert its differently named/inverted fields.
Bare `/msgtoggle`, `/paytoggle`, `/tptoggle` flip the setting; `on|off` is
idempotent. Other-player administration needs an explicit permission. Console
requires a target. Invalid syntax never toggles the sender as a fallback.

Enforce preferences in services, not only executors. `/msg` and `/reply` share
ignore/mute/vanish/privacy rules; blocked messages play no sound. `/pay` enforces
recipient acceptance and ignore policy, including offline users, while legitimate
shop/Vault payouts remain separate transaction types. `/tptoggle off` rejects new
requests and cancels pending incoming requests; acceptance rechecks policy.
Staff force-teleports use separate audited permissions. Preference bypasses are
explicit, never implied by a message sender's display name or alias.

**Fail closed (S03 contract for S07/S08 and M3).** A recipient whose preferences are
not usable is never treated as accepting. That covers three cases: a load still in
progress (`LOADING`), a load that failed (`FAILED`), or no session on this server.
- Teleport requests, payments and private messages to that recipient are refused
  with `preferences.unavailable` ("…settings are still loading. Try again
  shortly."), and no notification sound plays.
- Services ask `PreferencesModule#acceptance(uuid, flag)`, which answers `ACCEPTS`,
  `REFUSES` or `UNAVAILABLE`. They never substitute configured defaults for an
  unloaded player.
- A failed load is retried in the background with exponential backoff and a
  capped number of attempts (`preferences.load-retry`), so a database blip does
  not last the whole session.
- An offline recipient (for example, M3 `/pay` to an offline player) needs an
  authoritative read of their stored row, with configured defaults applied to the
  unset fields. If that read fails, the action fails closed the same way.

**Cross-server enforcement reads authoritative values.** A session holds the row
loaded at join plus its own writes. It sees another server's writes only through
its own next write or the next join. Anything enforced across servers must read the
authoritative row from the database, or use a future invalidation channel, never a
session cached on another server. This includes a request or payment that starts
on another backend and a proxy-wide `/msg`. Field-level patches mean concurrent
writes are never lost, but a cached session can be stale for reads.

`/messagesounds`, `/msounds` and `/mfx` are aliases:

- No arguments reports current state and usage.
- `/mfx volume <0-1>` stores a finite numeric override; reject NaN/infinity and
  out-of-range values without changing it.
- `/mfx toggle` sets an audible value to `0`; from `0`, it restores the last
  positive override or configured fallback. If that fallback is also zero, report
  that sounds remain muted. Persist restoration state across reconnect/restart.
- `/mfx reset` removes the override and restoration state, following the configured
  fallback immediately and after future config changes.

Support legacy `lang.yml` imports into the recode's `lang/<locale>.yml` files.
Messages may stay plain strings or declare text plus a sound. A sound ID alone is
valid. Missing volume and pitch resolve **independently** from `config.yml`.
Example schema. The `sounds` and lang parts are built (S08: [message sounds](sounds.md),
[lang import](lang-import.md)); signs and broadcasts are still planned:

```yaml
# config.yml
disabled-commands: []
enabledSigns: []                 # e.g. [color, warp]
sign-use-per-second: 4
sounds:
  defaults:
    volume: 1.0
    pitch: 1.0
  messages:
    fallback-volume: 1.0
broadcasts:
  enabled: false
```

```yaml
# lang/en_US.yml
messaging:
  received:
    text: "{sender}: {message}"
    sound: minecraft:entity.experience_orb.pickup
```

For an incoming private message, effective volume is the player's override if
present, otherwise `sounds.messages.fallback-volume`, **multiplied by** that
message's explicit volume or `sounds.defaults.volume`. This makes zero mute
reliably while keeping relative authored sound levels. Effective pitch is the
message's pitch or `sounds.defaults.pitch`. Message preferences affect private
message notifications, not every sound Allium emits. Allium owns message sound
playback directly; missing/disabled/invalid sound IDs leave the text usable and
produce a bounded diagnostic. Validate fallback volume in [0,1], finite
per-sound volume >= 0, and pitch in the supported playback range.

### Staple tools and renaming

Handcuffs belong to moderation. Preserve target authorization, claim integration,
restraint limits and original player-state restoration on release, quit, death,
teleport and shutdown. Missing claim APIs disable claim cuffs only. Do not carry
over the legacy default logout `ban {player}` without an explicit configured policy.

Mob Disarmer targets supported nonplayer mobs, excludes armor stands/players,
respects protection and handles equipment/drop probabilities once. As built in S06a, only the
main hand acts. A disarm takes every piece out of its slot before anything drops, then decides
each piece once by `drop-rule`. The default, `slot-chance`, matches pre-recode Allium: each
piece rolls the drop chance its slot had (1 or more always drops, 0 never does), and villager
gear and an enderman's carried block always drop. `always` drops every piece. Either way the
slot's chance is then zeroed. If the tool's state write is rejected, the equipment and chances
are put back and nothing drops (verified live with a fault point that only a
`-Dallium.fault-injection=true` JVM flag can enable). A disarm that removes nothing spends nothing. Phantom
Obliterator defaults to unnamed phantoms within 64 blocks; empty scans consume no
charge. Bound searches to loaded entities/chunks and respect protections/events.
Expose charges, recharge, radius and break chance as configuration rather than
scattering constants. Preserve legacy three-charge and depleted timestamps on
import; a server can choose the old break chances as a preset. Recheck the held
stack before committing, consume once, and retain state through model swaps.

Own a `RenameService` used by `/rename` and consumable renamers. Validate the target
stack, allowed formatting, length, provider update behavior and token before
committing a rename and consuming exactly one token. Cancel/no-op/invalid input
spends nothing. Handle inventory swaps, disconnects, full inventories and provider
item refreshes. A permanent rename permission bypasses the token explicitly.
No polling inventory scans, temporary ItemEdit permission grants or delayed guesses
at another command's success. A provider adapter can integrate with supported
ItemEdit APIs later; core renaming works without ItemEdit or dialogs.

Staff CrowBar is the `crowbar:player_data` locator feed, **not a crowbar item**.
Keep `allium.crowbar`, scope recipients and exposed locations explicitly, and
provide a core visibility policy even with no Party Locator installed. Party
policy composes through an optional hook. Rate-limit/batch updates and measure
cost; do not port the every-tick all-player scan unchanged.

#### Data-driven core tool architecture (`plugins/Allium/tools/*.yml`)

Staple tools are defined in individual YAML documents under `plugins/Allium/tools/`.
Each definition binds a persistent signed item identity (`id: allium:<name>`) to a
registered core behavior (`behavior: allium:<behavior>`). The behavior owns the fixed
operational transaction in Java (e.g. `DisarmBehavior`: stripping before dropping, single-roll
probability, atomic rollback on write failure, protection checks, and click-guard rate limiting).
Settings in YAML cannot alter these invariants.

Key contracts of this pattern:
1. **Behavior vs. Definition Separation:** Java maintains the single transaction implementation
   (`allium:disarm`, `allium:cuff`, `allium:obliterate_phantoms`). The YAML definition supplies
   parameters (charges, recharge time, radius, target exclusions, drop rules), appearances
   (default, provider aliases, charge-variant sets), permissions, messages, and sounds.
2. **Multiple Definitions per Behavior:** Multiple distinct tool definitions can bind to the same
   core behavior with independent parameters (e.g. a standard `allium:mob_disarmer` with 3 charges
   and a 6-block radius alongside a custom `allium:wand_disarmer` with 5 charges and a 3-block
   radius, as the live gate uses). Each
   tool keeps its own identity, state, and click-guard windows.
3. **Strict Rejection without Half-Loading:** Every definition file is validated against its schema
   and registered behavior type at startup. If any setting or reference is invalid (e.g. unknown
   keys, nonexistent behaviors, invalid entity types or permission nodes), the file is rejected
   whole with path-specific diagnostics and never half-loaded with fallback defaults.
4. **Model for Handcuffs and Phantom Obliterator:** This architecture serves as the direct template
   for Handcuffs (`allium:cuff`) and Phantom Obliterator (`allium:obliterate_phantoms`).
5. **Bridge to M5 Curios:** In Milestone 5, the Curios addon will expose core behaviors as typed
   actions (`disarm`, `fell_tree`, `obliterate_phantoms`) that its items, enchantments and
   equipment slots can trigger. Curios calls the core behavior; it never reimplements it.

Port designs for the next two core tools follow this pattern:
- **Phantom Obliterator** (`allium:obliterate_phantoms`): [design](phantom-obliterator-design.md).
- **Lazy Axe** (`allium:fell_tree`, off by default): [design](lazy-axe-design.md). It is the
  legacy `tree_axe`.

### Timed broadcasts

Timed broadcasts ship in core with `enabled: false`. Define ordered/random groups,
interval/start delay, audience/world/permission filters, minimum player count,
locale keys and supported chat/actionbar/title channels. Reload cancels old jobs
before replacing them; no catch-up spam after downtime. Sending uses the shared
message/sound pipeline. A normal broadcast is text delivery, not arbitrary console
command execution.

### Curios addon: custom items, enchants and equipment

**Curios** (`Addon-Curios-<version>.jar`, plugin name `Curios`, `depend: [Allium]`) merges three
addons planned separately so far:
- **Fun Items:** custom items with actions.
- **Custom enchantments:** a new feature.
- **Reliques:** equipment slots, relic items, their loot and UI.

They share one **trigger → conditions → actions** engine, with cooldowns and usage, and attach
it in three places:

| Attachment point | Where definitions live | When its triggers fire | Examples |
| --- | --- | --- | --- |
| **The item itself** | `curios/items/<id>.yml`: a signed Curios item on any core provider appearance | The item is used: right/left click on air, a block or an entity (sneak variants included), consume, launch | A launch wand, confetti, ported legacy novelty items |
| **An enchant on an item** | `curios/enchants/<id>.yml`, with settings per level | The enchanted item is used or worn: melee hit, bow or trident hit, mine block, take damage, hold, an equip interval | A lifesteal sword enchant, a vein-mining pickaxe enchant |
| **An equipped slot** | `curios/slots.yml` and `curios/relics/<id>.yml`, the Reliques port | The item sits in a Curios slot (charm, head, chest, belt, ring and so on): a passive interval, on hit, on damage taken, on kill | Relic abilities, passive attributes, the Trading Cards slot |

**The engine:**
- **Triggers:** each trigger declares which attachment points it supports, so a misplaced
  trigger is a load-time error.
- **Conditions and actions:** one implementation serves all three points.
- **Cooldowns:** scoped by shared group, player or item instance. An enchant cooldown is keyed by
  enchant and player, and a slot cooldown by slot item and player.
- **Vanilla enchantments on equipped relics** keep counting toward their effects, as in legacy
  Reliques.

**Module toggles:** `curios/config.yml` has `modules.items`, `modules.enchants` and
`modules.slots`.
- **Turning one off** unregisters its listeners, commands and definitions.
- **Item state is untouched:** nothing on any item is deleted or rewritten. Enchant state and
  lore stay, equipped items stay in storage, and items stay inert until the module is back.
- **Each module boots alone,** with the others off.

**Core behaviors are typed actions.**
- **The actions:** `disarm`, `fell_tree` and `obliterate_phantoms` call the core behaviors
  through the addon API. The core behavior owns its transaction (taking before dropping,
  rollback, charges only on success, protection), and Curios supplies only the context and that
  behavior's declared parameters.
- **No reimplementation:** Curios never reimplements a core tool, and core tools never require
  Curios.
- **API version:** this needs a backwards-compatible minor version of the addon API, 1.1, that
  exposes registered core behaviors as invokable typed actions.

**Rules carried over from the Fun Items contract (they apply to all three points):**
- **Items:** definitions can use any core item provider.
- **Cooldown visuals:** material cooldown visuals are separate from the authoritative cooldown
  state.
- **Effects:** messages and sounds, effects, particles, projectiles, bounded delays and
  allowlisted player or console commands are supported.
- **No OP:** a player is never temporarily made OP. No action grants permissions.
- **Commands:** typed actions come first, and a command is an allowlisted escape hatch. See the
  [ExecutableItems comparison](fun-items-reference.md) for what to adopt and reject.

Respect cancelled/protected interactions and distinguish hands. Validate configured
actions at load time; use server-owned definitions rather than executable commands
trusted directly from arbitrary item metadata. Delayed actions revalidate targets
and held-item requirements and are cancelled on lifecycle changes. Charge/use
consumption follows a documented success outcome; arbitrary command sequences are
not rollback-safe transactions. Start with small presets such as launch wand and
confetti, then port suitable legacy novelty items. Specialized core tools call
their own domain services and never require this addon.

**Ported from Reliques:**
- **Content:** the ten Antiquities relics, the slot layout and death rules, loot rolls, the
  textured equipment UI, and the inventory regression fix below. The fix is required.
- **Trading Cards:** its optional equipment-slot hook targets Curios. With Curios absent, cards
  keep their base functionality.

**Custom enchantments:** see the [enchantment design](curios-enchants-design.md). Item-stored
state, rendered in lore, is the source of truth on every lane. Registry-backed 1.21+
enchantments are an optional later layer. Anvil, grindstone, enchanting table and villager trade
rules are explicit.

**Build order inside M5:**
1. The engine with items, plus the core-behavior actions.
2. Slots (the Reliques port, its import and the UX fix).
3. Enchantments.

## Delivery sequence and completion gates

Work in small vertical slices with runnable commands and realistic tests. Numbers
express dependency order, not promised dates. An addon prototype may run earlier
to exercise an API, but extraction must not postpone a stable core.

| Milestone | Deliverable | Completion gate |
| --- | --- | --- |
| M0 — Reproducible baseline | Pin build/toolchains; Spigot baseline; correct plugin metadata; audit libraries, schema handling, command lifecycle and existing five commands | Clean build/check/package succeeds on supported build JDK; real Spigot/Paper boot at range endpoints; all optional integrations absent; accurate support matrix recorded |
| M1 — Shared contracts and flagship item | Minimal API, preferences repository, command-family policy, scheduler boundary, native static text formatting module (retiring Takion), shared Give/Enchant/Skull/Potion library, built-in/vanilla and three provider adapters; Mob Disarmer across providers; core pack generation | Core is the only required plugin; one behavior works standalone and with each supported provider; round-trip metadata/charges; provider readiness/reload safe; no vanilla-carrier false matches |
| M2 — Communication and preferences | `/msg`, `/reply`, ignore/mute/social spy, `/msgtoggle`, sound defaults and `/mfx` aliases; mail and notification routing | Overrides survive restart; zero/default/reset/omitted pitch-volume work; failed or blocked sends are silent; privacy, console and permission cases covered |
| M3 — Movement and money | Safe TP service, requests/tptoggle/back/spawn/homes/warps; atomic economy/pay/paytoggle/Vault; kits via item service | Request expiry/movement cancellation/pending invalidation; safe destinations; offline preferences; no lost or duplicated funds on concurrent transfers or failures |
| M4 — Complete core | Remaining staff/player utilities; handcuffs/Obliterator/renamer/CrowBar; signs; timed broadcasts; config parity and diagnostics | Core-only acceptance run passes; permissions checked at every entry point; rename failure spends nothing; broadcasts remain idle by default; bounded CrowBar/entity work |
| M5 — Addon family | Native plugin API test addon; Curios (engine and items, then equipment slots, then enchantments); Harvest then Kitchen; SpawnerCraft; Pregen Ores; Trading Cards; Party Locator | Each addon boots alone with its hard dependencies; core jar contains none of them; disabled/missing optional peers work; restart and migration checks pass |
| M6 — Migration and release | Audited importers, staging migration, profiling, compatibility matrix, release automation and operator docs | Dry-run/backup/idempotency/rollback rehearsal (live SQLite snapshots via online backup or `VACUUM INTO`, never a raw copy); no unexplained data loss; published tested version/provider matrix; release artifacts and pack manifests verified |

M0–M4 yield the core alpha. M5 can proceed in parallel after APIs stabilize, and
each addon can release independently; core readiness does not require every addon.
Importer inventory and dry-run design start in M0, not at the end of M6.

Within M4, prioritize useful Essentials parity over novelty: command permissions
and tab completion; gamemode/fly/god/heal/feed/speed; AFK/seen/whois/near;
vanish/freeze/kick/ban/mute/jail/warn/staff notes; time/weather/enchant/give/item;
inventory and ender-chest inspection/restore. Sensitive actions need audit trails,
others permissions and explicit behavior with offline players. Home limits use
the maximum granted limit plus staff overrides. Keep pet teleport and offline
restore behavior as explicit follow-up slices, not accidental omissions.

Additional config parity: cooldowns/bypasses, teleport delay/safety/expiry,
multi-home limits and overwrite confirmation, per-warp access, starting/min/max
balances and minimum pay, command costs, AFK behavior, join/quit/MOTD/rules,
social-spy/ignore policy and per-player locale. Import known settings with a
conversion report; do not expose nonfunctional configuration keys.

## Migration and acceptance

Trading Cards must integrate quest-specific flat token bonuses with the
BeastTokens API and both supported quest providers (lmbishop Quests and
ExcellentQuests). Reward previews, completion text and actual credited amounts
must share one calculation; static quest lore is not proof of an applied bonus.
Distinguish flat Quest Tokens from generic Token Yield and probabilistic Token
Fortune, define rounding and booster order, and prevent duplicate application.
Acceptance includes the reported SF case: a base 2-token quest with a +6.225
quest bonus must not still credit 2. Pin the intended integer payout/rounding rule
and verify it against the displayed reward. Optional providers must remain optional.

The legacy Reliques inventory UX, now ported to Curios' equipment slots, has a known
regression: removing a card from its equipped slot in `/relics` leaves it impossible to place in
the player's inventory
until the menu closes. Treat this as a required addon fix, not an acceptable
workaround. Equipment, cursor and player-inventory state must commit together and
be synchronized immediately. Acceptance: unequip, place into any valid inventory
slot and continue using the open menu without closing/reopening. Cover ordinary
clicks, shift-click, hotbar swaps, drag events, rapid repeated clicks, full
inventories, close/disconnect and restart persistence. Preserve metadata and
apply/remove boosts once; reject invalid moves without trapping, losing or
duplicating the card. Add a regression check at the real menu transaction boundary
and a live client check for cursor synchronization.

Inspect legacy H2 `database/sfcore`, Harvest's separate database, legacy Reliques equipment
YAML (imported by Curios), serialized ItemStacks, Trading Cards pending payouts, and item/entity/chunk
PDC. Essentials userdata is a separate import source. Preserve UUIDs, item IDs,
existing `allium:custom_item_id`/`alliumcards` tags, model variants, crop ownership
and ore-generated markers. Harvest's spawner overlays and SpawnerCraft's recipes
have different owners. Kitchen extraction must retain world-bound stations and
in-progress cooking. Party membership currently needs a deliberate persistence
design rather than assuming there is an existing durable table.

Every importer has dry-run output, backups, source/version checks, resumable
checkpoints, repeat-run idempotency and conflict decisions. Leave source data
untouched and produce a skipped/unmapped report. Remove no existing world content
merely because an addon or provider is absent. Stage on a full world/database copy;
rollback restores the complete pre-cutover snapshot, not just the plugin jar.
Snapshots and backups of a live SQLite database use SQLite's online backup API or
`VACUUM INTO`, never a raw copy of the files. Allium runs SQLite in WAL mode, so
recent commits live in `allium.db-wal` until a checkpoint, and a raw copy of
`allium.db` alone can miss them or be torn. A raw file copy is safe only after a
clean server stop. MySQL snapshots use a consistent dump
(`mysqldump --single-transaction`) or the operator's database backups. The
rollback rehearsal restores from these snapshots and verifies the row counts
and schema versions in `allium_schema`.
`BackupManager` and all migration/cutover snapshots must include
`plugins/Allium/.item-hmac-secret` (preserving `0600` POSIX permissions) alongside
YAML and database backups so existing signed privileged items in player inventories,
ender chests, and containers continue to verify after restore or server migration.
Migrate required features before cutover; never run both plugins as simultaneous
owners of balances, item use or crop growth. Excluded features remain unimported.

Verification is risk-based: domain tests for policy/state/amounts; SQLite and MySQL
integration tests for persistence/transactions; real server tests for command
ownership, events, signs, inventory state, packets and optional classloading.
Exercise no-provider and individual-provider installations as well as the actual
SF Nexo + CraftEngine + ResourcePackManager mix. Do not use a happy-path mock
test as evidence of cross-version runtime compatibility.

Profile idle and loaded servers; record hardware, player/entity counts, p95/p99
main-thread time, DB latency, pack size and addon overhead. Release gates include
no blocking DB calls in hot command/event paths, no unbounded player scans or
chunk loads, and bounded task/cache lifetimes. Set budgets from measured baselines.
Keep core jar below a conservative 5,000,000-byte project budget; verify current
host limits separately. Do not remove reflective resources through unsafe Shadow
minimization just to reach that budget. Publish hashes, dependency/license notices,
API version, supported server/provider builds and a concise upgrade guide.

Reload performance is a specific release requirement: legacy `/core reload` is
reported slow at high player counts. The recode's `/allium reload` (with an
intentional `/core reload` compatibility route) must avoid global teardown/rebuild
and synchronous per-player storage or inventory scans. Parse files and prepare
immutable snapshots off-thread; perform a bounded validated swap and only the
necessary Bukkit updates on-thread. Reload affected services, not every subsystem.
Coalesce repeated reload requests, report progress and timings, preserve active
mail/gift/GUI/item transactions, and keep the previous snapshot on validation
failure. Mark restart-only changes explicitly. Reproduce on a staging copy at
recorded idle and peak player counts, measure command latency and p95/p99 tick
pauses, then set a numerical budget from that baseline. Include slow storage,
large locale/item files and repeated reloads; do not declare success from an empty
server test.

## Reference audit

Local source evidence used for this plan:

- `/mnt/storage/repos/Allium`: `item/ItemResolver*`, `items/impl/*`,
  `items/HandcuffsItem`, `packetevents/CrowBarDataSender`, `harvest/`,
  `harvest/kitchen/`, `tradingcards/`, `spawnercraft/`, `docs/RELIQUES.md`,
  `docs/RESTORE_SNAPSHOTS.md`, `server-config/sf-ores-kitchen/IMPLEMENTATION.md`.
- `/mnt/storage/devops/ItemTag`: `actions/ActionsUtility`,
  `command/itemtag/Actions`, `command/itemtag/SecurityUtil`.
- `/mnt/storage/devops/Essentials`: `Settings`, `EssentialsToggleCommand`,
  `Commandmsgtoggle`, `Commandpaytoggle`, `Commandtptoggle`, `SignWarp`,
  `EssentialsSign`, `SignPlayerListener`, and the default `config.yml`.
- `/mnt/storage/devops/UnlimitedNametags`: `placeholders/PlaceholderManager`,
  `config/Formatter`, `config/GlowOverride`, `packet/GlowOverrideComputer`.

Essentials and ItemTag carry GPLv3 licenses. Use their behavior as reference;
decide Allium's license and attribution before directly incorporating source or
configuration prose. Capture source revisions when implementation begins.

Provider implementation should follow the public
[Nexo API][nexo-api], [Oraxen API][oraxen-api] and
[CraftEngine API setup][ce-api]. Nexo's documented asynchronous item registration
is a concrete reason to model readiness rather than treating `onEnable` order as
sufficient. API versions and pack formats must be pinned during implementation.

[paper-java]: https://docs.papermc.io/paper/getting-started/
[nexo-api]: https://docs.nexomc.com/community-guides/api
[oraxen-api]: https://docs.oraxen.com/developers/api
[ce-api]: https://github.com/Xiao-MoMi/craft-engine#craftengine-api
