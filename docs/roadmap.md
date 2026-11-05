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
| Commands, permissions, config/migrations, locale/messages/sounds, scheduler, player preferences, storage and public API | Configurable Fun Items: click/consume actions, cooldowns, effects and novelty items |
| Homes, warps, spawn, teleport requests/back, kits, economy, chat/private messages/mail, moderation and staff utilities | Reliques: equipment, slots, effects, loot and its own UI |
| Item references, provider adapters, serialization, matching and pack coordination | Harvest: crops, soil, growth, fertilizers, sprinklers and crop rendering |
| Handcuffs, Mob Disarmer, Phantom Obliterator, rename service and consumable renamer | Kitchen: cooking, food preparation, stations and bags; depends on Harvest |
| Staff CrowBar feed, independent of parties | Pregen Ores: terrain jobs and custom ore placement, separately configurable |
| Signs, optional timed broadcasts, diagnostics and import tools | SpawnerCraft: cores, heads and spawner recipes |
| Core pack assets for core tools; provider bindings are configurable | Trading Cards: collection, boosts, upgrades, trade-ins and morphing |
| Shared item/provider and location-visibility contracts | Party Locator: membership, party locator bar and optional mcMMO sync |

Core modules can be disabled; addon implementation and assets are absent from the
core artifact. SpawnerCraft remains its own addon even if small. Kitchen is never
required to use Harvest. Optional cross-addon integrations must not create
dependency cycles. Trading Cards' Reliques slot, Harvest boosts and party radius
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
- `allium-core` is currently a build subproject with no Java sources. Its name
  does not mean a stable public API or a completed domain layer exists.
- **S01 baseline (completed):** Gradle 9.5.1 is pinned with Java toolchains; shared
  code compiles against Spigot `1.20-R0.1-SNAPSHOT` with Java 17 bytecode (class
  version 61); `plugin.yml` declares `api-version: '1.20'`; pre-1.20 compatibility
  sources have been retired.
- Private messages, preference commands, provider adapters, addons and pack
  delivery are not implemented in this recode. Some config module names are
  placeholders; do not count them as working features.

## Architecture decisions

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
1.21, and Java 25 for 26.x. Paper currently recommends Java 21 for its 1.20–1.21
line and requires Java 25 for 26.1+; see [Paper's requirements][paper-java].
Verify exact Spigot/server requirements when pinning the matrix. Java 17 output
does not imply newer servers can run on Java 17.

#### Recorded S01 Server/JDK Baseline & Command Smoke-Test Matrix

| Lane | Server Build | Runtime JDK | No-Provider Boot & Lifecycle | `/heal`, `/feed`, `/fly`, `/god`, `/gamemode` (`self`, `other`, `console`) |
| --- | --- | --- | --- | --- |
| Minimum (Spigot 1.20.1) | Spigot `3871-Spigot-d2eba2c-3f9263b` (MC 1.20.1) | OpenJDK 17 (`java-17-openjdk`) | **PASS** — clean enable, SQLite connect, modules loaded, clean disable | **15/15 PASS** |
| Minimum (Paper 1.20) | Paper `git-Paper-17` (`c287e92`, MC 1.20) | OpenJDK 17 (`java-17-openjdk`) | **PASS** — clean enable, SQLite connect, modules loaded, clean disable | **15/15 PASS** |
| Intermediate (Paper 1.20.6, InvUI `r19+` boundary) | Paper `1.20.6-151-a4f0f5c` (MC 1.20.6) | OpenJDK 21 (`java-21-openjdk`) | **PASS** — clean enable, SQLite connect, modules loaded, clean disable | **15/15 PASS** |
| Maximum (Spigot 26.3) | Spigot `4660-Spigot-766de51-bd6d9dc` (MC 26.3) | OpenJDK 25 (`java-25-openjdk`) | **PASS** — clean enable, SQLite connect, modules loaded, clean disable | **15/15 PASS** |
| Maximum (Paper 26.3) | Paper `26.3-141-54024d3` (`world_version: 5023`, protocol 777) | OpenJDK 25 (`java-25-openjdk`) | **PASS** — clean enable, SQLite connect, modules loaded, clean disable | **15/15 PASS** |

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
Bukkit entity/world/inventory operations run on the server thread. SQLite has a
serialized write path; MySQL has proper transactions. Money uses a declared scale
and rounding policy, atomic conditional withdrawals, and transactional transfers.
Vault's synchronous API needs an explicit consistency strategy and measured
behavior; do not return success before a durable operation is secured.

Startup builds validated configuration snapshots before enabling services.
Failures disable the affected capability with a useful diagnostic. Shutdown
unregisters commands/listeners/services, cancels tasks, restores temporary player
state and drains bounded persistence work. Config reload validates before swapping
and keeps the old configuration on failure. Addon installation/removal requires
restart; do not promise arbitrary plugin hot loading.

### Addon packaging

Use ordinary Bukkit plugin jars in `plugins/`, with `depend: [Allium]` and a
versioned Allium API. Kitchen additionally depends on Harvest. Bukkit owns
classloading and lifecycle; Allium supplies registration contracts and compatibility
checks. This replaces the earlier `plugins/Allium/addons/` custom-loader proposal.
API compatibility checks still run with native plugin loading.

Publish `Allium-<version>.jar`, `Addon-FunItems-<version>.jar`,
`Addon-Reliques-<version>.jar`, `Addon-Harvest-<version>.jar`,
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
The existing resolver in legacy Allium supports Nexo and CraftEngine but does
not have an active Oraxen implementation; Oraxen needs a real adapter.

Separate stable **Allium behavior identity** from the provider reference supplying
its appearance. Selecting `nexo:handcuffs` must not require a second implementation
of restraint logic. Preserve provider metadata and Allium state during provider
updates. Names/lore/model data alone never grant privileged item behavior.

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
`Component`s on Paper (behind an isolated adapter with no Paper types in shared
signatures) and legacy `§x` strings on Spigot with identical visible results.
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
Keep disabled-command registration changes restart-scoped initially; config reload
can immediately guard dispatch but must report when a restart is needed to yield
labels. Do not implement a global command blocker by accident.

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
Example target schema (planned, not current runtime configuration):

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
respects protection and handles equipment/drop probabilities once. Phantom
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

### Timed broadcasts and configurable Fun Items

Timed broadcasts ship in core with `enabled: false`. Define ordered/random groups,
interval/start delay, audience/world/permission filters, minimum player count,
locale keys and supported chat/actionbar/title channels. Reload cancels old jobs
before replacing them; no catch-up spam after downtime. Sending uses the shared
message/sound pipeline. A normal broadcast is text delivery, not arbitrary console
command execution.

Fun Items is an optional action engine inspired by ItemTag. Start with right-click
air/block/entity, left-click, sneak variants and consumption; give actions typed
player/target/location/hand context and ordered outcomes. Definitions can use any
core item provider. Cooldowns have explicit shared-group, player and item-instance
scopes; material cooldown visuals are separate from authoritative cooldown state.
Support messages/sounds, effects, particles, projectiles, bounded delays and
allowlisted player/console commands. Never temporarily OP a player.

Respect cancelled/protected interactions and distinguish hands. Validate configured
actions at load time; use server-owned definitions rather than executable commands
trusted directly from arbitrary item metadata. Delayed actions revalidate targets
and held-item requirements and are cancelled on lifecycle changes. Charge/use
consumption follows a documented success outcome; arbitrary command sequences are
not rollback-safe transactions. Start with small presets such as launch wand and
confetti, then port suitable legacy novelty items. Specialized core tools call
their own domain services and never require this addon.

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
| M5 — Addon family | Native plugin API test addon, Fun Items; Harvest then Kitchen; Reliques; SpawnerCraft; Pregen Ores; Trading Cards; Party Locator | Each addon boots alone with its hard dependencies; core jar contains none of them; disabled/missing optional peers work; restart and migration checks pass |
| M6 — Migration and release | Audited importers, staging migration, profiling, compatibility matrix, release automation and operator docs | Dry-run/backup/idempotency/rollback rehearsal; no unexplained data loss; published tested version/provider matrix; release artifacts and pack manifests verified |

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

Reliques inventory UX has a known legacy regression: removing a card from its
equipped slot in `/relics` leaves it impossible to place in the player's inventory
until the menu closes. Treat this as a required addon fix, not an acceptable
workaround. Equipment, cursor and player-inventory state must commit together and
be synchronized immediately. Acceptance: unequip, place into any valid inventory
slot and continue using the open menu without closing/reopening. Cover ordinary
clicks, shift-click, hotbar swaps, drag events, rapid repeated clicks, full
inventories, close/disconnect and restart persistence. Preserve metadata and
apply/remove boosts once; reject invalid moves without trapping, losing or
duplicating the card. Add a regression check at the real menu transaction boundary
and a live client check for cursor synchronization.

Inspect legacy H2 `database/sfcore`, Harvest's separate database, Reliques equipment
YAML, serialized ItemStacks, Trading Cards pending payouts, and item/entity/chunk
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
