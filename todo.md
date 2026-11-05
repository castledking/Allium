# Allium implementation checklist

[Roadmap and behavior contracts](docs/roadmap.md) are authoritative; see also the
[Give and Item Library design](docs/give-item-design.md) and the
[Text Formatting and Animation design](docs/text-formatting-design.md). Checked items
below mean code/scaffolding exists, not that production verification is complete. All
remaining items are planned. Keep work in independently reviewable vertical slices; run
checks appropriate to each slice.

## Existing code

- [x] Gradle/Shadow build and module structure (`allium-core` enforced free of Bukkit/Spigot/Paper dependencies).
- [x] Plugin entry point, module/command/listener managers and logger.
- [x] Config loading, backups, schema migration scaffolding and missing-key sync.
- [x] Message enum, locale files, language fallback and message manager.
- [x] Database setup and economy/Vault scaffolding.
- [x] Heal, feed, fly, god and gamemode executors (ported onto platform-neutral `CommandSpec` + `BukkitCommandRenderer` and verified across `self`, `other`, and `console` on Spigot 1.20.1, Paper 1.20, Paper 1.20.6, Spigot 26.3, and Paper 26.3).

## First implementation queue

These slices make the first coding sessions concrete. Each includes an observable
result and must preserve the existing uncommitted work.

- [x] **S01 / M0 — Baseline:** pin Gradle/build JDK and Java toolchains; compile
      shared code against Spigot 1.20/Java 17; audit Takion/InvUI/HikariCP and Shadow
      minimization; correct plugin API metadata; retire pre-1.20 scope. Gate: clean
      check/build/package, no-provider boot, and `/heal`, `/feed`, `/fly`, `/god`,
      `/gamemode` (`self`, `other`, `console`) smoke tests on Spigot 1.20.1/Java 17,
      Paper 1.20/Java 17, Paper 1.20.6/Java 21, Spigot 26.3/Java 25, and Paper 26.3/Java 25.
- [x] **S01.5a / M1 — Static text formatting + retire Takion:**
      implement Allium's static text formatter (`&`/`§` legacy codes, `&#RRGGBB`/`§x`
      hex, multi-stop `<gradient:...>` and `<rainbow:...>` tags, nested/reset
      formatting, and a `GlyphColorSource` hook ready for `S01.5b`
      `color(glyphIndex, length, timeMs, seed)`); output Adventure `Component`s on
      Paper (compiled against Paper's bundled `net.kyori:adventure-api:4.17.0` via
      `compileOnly`, never shaded or relocated; `Allium.jar` contains **zero**
      `net.kyori` classes) and legacy `§x` strings on Spigot (via Allium's own
      `FormattedText#toLegacy`) with identical visible results; route
      `MessageManager` and locale delivery through the formatter with Allium-owned
      sound playback; replace all remaining Takion call sites and remove
      `me.croabeast.takion:shaded` from `build.gradle.kts` and `minimize()` exclusions.
      Gate: jar contains zero `me.croabeast` and zero `net.kyori` classes; unit tests
      pin 2-stop/multi-stop gradients, rainbow, legacy, hex, nested/reset, empty and
      1-char strings on both Adventure and legacy paths; boot + localized message on
      min/max lanes; jar size reduced from **1,684,657 bytes** (before) to
      **1,395,353 bytes** (after, -289,304 bytes).
- [ ] **S01.5b — Animated text engine (deferred until the first animated target: tab, nametag, boss bar, or display entity):**
      implement deterministic per-glyph animation effects (`pulse`, `shift`, `sparkle`,
      and `phase-scroll` gradient/rainbow) using pure
      `color(glyphIndex, length, timeMs, seed) -> RGB`, optional OKLab interpolation,
      frame caching per `(text, effect, frame)`, delta-only pushes, and a single shared
      clock/scheduler. Gate: deterministic frame output in unit tests; one scheduler for
      N animated players; no main-thread spikes at the measured SF player count; Spigot
      legacy and Paper Adventure output match.
- [x] **S02 / M1 — Command families:** platform-neutral command spec in `allium-core`
      (tree, arguments, permission nodes, suggestion sources, message keys) rendered to
      Bukkit executors and completers (`BukkitCommandRenderer`, `CommandMapBridge`);
      canonical/alias/namespace policy (`CommandFamilyPolicy`) and
      `disabled-commands: []`; ported `/heal`, `/feed`, `/fly`, `/god`, and `/gamemode`
      onto the neutral command spec as proof. Gate: disabling a family blocks all Allium
      aliases (`gm`, `allium:gamemode`, `allium:gm`) and yields labels in Bukkit's
      `CommandMap` so another plugin owns the label; no command interception leaks,
      clear restart diagnostics on reload, and permission/visibility-aware completion;
      the five ported commands behave identically before and after the port across all
      5 lanes (Spigot 1.20.1, Paper 1.20, Paper 1.20.6, Spigot 26.3, Paper 26.3).
- [ ] **S03 / M1 — Preferences:** UUID repository, schema migrations, defaults,
      bounded asynchronous storage and safe session loading. Gate: SQLite/MySQL
      round-trip and reconnect/restart behavior without accidental default overwrite.
- [x] **S04 / M1 — Item contract:** parse, create, identify, match, clone and
      serialize vanilla/Allium references; first-colon namespace split (`craftengine:<ns>:<id>`);
      bare names resolve to vanilla only; behavior ID separate from appearance;
      once-per-reload `ce:` deprecation warning; `schema_version` (`1`) in stamped PDC
      and `ItemRef.serialize()`; server-secret `HmacSHA256` signature over
      `(provider, itemId, behaviorId, instanceUuid)` stored outside `config.yml`
      (`plugins/Allium/.item-hmac-secret`) required for `privileged = true` definitions
      (`handcuffs`, `phantom_obliterator`); read-only legacy `allium:custom_item_id`
      and `alliumcards` tag inspection (`ItemService#readLegacyMetadata`);
      platform-neutral `ProviderKey`, `ItemRef`, `ResolutionStatus`, `ProviderStatus`,
      `ItemHmacSigner`, and `LegacyItemMetadata` in `allium-core`. Gate: metadata
      round-trip, forged/missing/tampered/valid signature verification (surviving clone
      and appearance swap), strict provider-carrier matching and no AIR fallback across
      Spigot 1.20.1, Paper 1.20, Paper 1.20.6 (NBT-to-component boundary), Spigot 26.3,
      and Paper 26.3.
- [ ] **S04 follow-up — Item library:** shared Give/Enchant/Skull/Potion builders,
      bracket/brace/quote-aware grammar, `ItemFactory#createItemStack` / `ComponentAdapter`
      validation, cancellable `ItemGiveEvent`, configurable overflow policy, non-destructive
      `-e` equip, and async head resolution with target revalidation; bundle in core
      and expose API to addons (with an optional minimal M1 `GiveCommand` test harness).
      No extra required plugin, duplicate shaded API, internal command dispatch or
      duplicated item parsers.
- [ ] **S05 / M1 — Provider adapters:** Nexo, CraftEngine and Oraxen with ready/reload
      states, isolated optional classes and diagnostics. Gate: each present/absent,
      delayed readiness and reload; CE namespace preserved; no persisted-state loss.
- [ ] **S06 / M1 — First tool and pack:** Mob Disarmer through the item service,
      built-in visual plus all providers; inventory SF assets and stable IDs;
      generate the core pack with an explicit delivery owner. Gate: one authorized
      activation consumes once, protection respected, metadata/charges preserved.
- [ ] **S07 / M2 — Messaging:** msg/reply, acceptance/ignore/mute/vanish rules,
      `/msgtoggle [on|off]`, per-recipient notifications. Gate: blocked sends are
      silent; replies obey the same policy; preference persists; console/others
      permissions and invalid arguments behave predictably.
- [ ] **S08 / M2 — Sound preferences:** omitted volume/pitch defaults, legacy
      lang.yml importer, `/messagesounds` aliases `/msounds` and `/mfx`,
      volume/toggle/reset. Gate: zero, finite ranges, independent defaults,
      restoration and config changes; Allium owns message sound playback directly.

S01.5a, S02–S03 and S04–S05 can proceed after S01 with separate file ownership.
S06 depends on S04–S05; S07–S08 depend on S01.5a, S03 and the message pipeline.

## S01 Audit: Shaded Dependencies & Takion Retirement Plan

### Bytecode & Shadow Minimization Findings
- **Allium shared classes:** Compiled against `org.spigotmc:spigot-api:1.20-R0.1-SNAPSHOT` with `--release 17` (class version 61 / Java 17). `allium-core` compiles with zero Bukkit/Spigot/Paper dependencies.
- **HikariCP 5.1.0 (`com.zaxxer:HikariCP`):** Class version 55 (Java 11). Excluded from Shadow `minimize()` because pool and proxy classes are loaded reflectively.
- **InvUI 1.49 (`xyz.xenondevs.invui`):** `invui-core`, `inventory-access`, and `inventory-access-r16..r18` (1.20.0–1.20.4) are class version 55–61 (Java 11–17). `inventory-access-r19..r26` (1.20.5–1.21.11) are class version 65 (Java 21), loaded reflectively via `InventoryAccessRevision` only on 1.20.5+ servers (which require Java 21+, verified on Paper 1.20.6 / OpenJDK 21). Excluded from Shadow `minimize()` so `Class.forName` lookups succeed.
  - **Open M4 Risk (InvUI on 26.x):** `InventoryAccessRevision` matches `26 > 1` and selects `R26` (compiled against `1.21.11` NMS internals) on `26.x`. Matching `26 > 1` is **not** real `26.x` support. Before any GUI slice in M4/M5, check upstream InvUI for `26.x` support; if none exists, place the GUI layer behind an adapter with a plain Bukkit inventory implementation.
- **Adventure API 4.17.0 (`net.kyori:adventure-api:4.17.0`):** Declared strictly as `compileOnly` in `build.gradle.kts` (not `implementation`, never shaded or relocated). At runtime on Paper, `AdventureTextBridge` links directly against Paper's bundled `net.kyori.adventure.*` classes; on Spigot, `TextSender` uses Allium's own `FormattedText#toLegacy` (`§x` hex + legacy formatting) without loading `AdventureTextBridge`.
  - **Jar audit for `net.kyori` classes:** `jar tf build/libs/Allium.jar | grep -i kyori` returns **0 classes** (`build/libs/Allium.jar` contains no `net.kyori.*` classes at all).

### Takion (`me.croabeast.takion:shaded:1.4`) Inventory & Retirement (Completed in S01.5a)
1. **Call sites in recode:**
   - Formerly only two files referenced Takion: `PluginStart.java` (`new TakionLib(this)`) and `MessageManager.java` (`takion.getLoadedSender().setTargets(player).send(text)` in `broadcast()`).
   - **Critical 26.3 Boot Failure:** On Spigot 26.3 and Paper 26.3, `new TakionLib(this)` triggers `me.croabeast.common.util.ServerInfoUtils.<clinit>`, which matches `Bukkit.getVersion()` against `1\.(\d+(\.\d+)?)`. On 26.3 (`SERVER_VERSION = 0.0 < 20.5`), it splits `Bukkit.getServer().getClass().getPackage().getName()` (`org.bukkit.craftbukkit`, length 3) and indexes `[3]`, throwing `ArrayIndexOutOfBoundsException: Index 3 out of bounds for length 3`.
2. **Takion jar grep for animation APIs:**
   - `me.croabeast.prismatic.PrismaticAPI` and `me.croabeast.prismatic.color.MultiColor` (`$Gradient`, `$Rainbow`) provide **static** string/component colorization only (`applyGradient`, `applyRainbow`, `colorize`). There is **no** per-frame or phase-scroll text animation API in Takion's Prismatic package.
   - `me.croabeast.takion.bossbar.AnimatedBossbar` only cycles through a `List<String>` of bossbar titles/colors/styles on a timer.
3. **Retirement Result (S01.5a):**
   - Implemented `codes.castled.allium.text` (`TextFormatter`, `FormattedText`, `GlyphColorSource`, `TextSender`, `AdventureTextBridge`), routed `MessageManager` and sound playback through it, and removed `me.croabeast.takion:shaded:1.4` and its relocation/minimization rules from `build.gradle.kts`.
   - `build/libs/Allium.jar` contains **zero** `me.croabeast` classes and **zero** `net.kyori` classes; jar size dropped from **1,684,657 bytes** to **1,395,353 bytes** (-289,304 bytes).

## M0 — Foundation and migration inventory

- [x] Record exact server/JDK/provider matrix and baseline current build results.
- [ ] Validate plugin lifecycle, command unregistering and safe module reload.
- [ ] Validate config snapshots before replacing active configuration.
- [ ] Establish bounded scheduler/storage ownership and dependency cleanup.
- [ ] Inventory H2, Harvest DB, Reliques YAML, pending card payouts and world PDC.
- [ ] Record source revisions/licenses and asset provenance before ports.

## M3 — Movement and economy

- [ ] Shared safe teleport service, warmup/cooldown, cancellation and expiry.
- [ ] TP requests/accept/deny, `/tptoggle`, pending-request invalidation and staff overrides.
- [ ] Spawn, homes/limits/confirmation, warps/access and back.
- [ ] Transactional balances, conditional withdrawal, transfer journal and failure handling.
- [ ] Balance/pay/eco, `/paytoggle`, offline policy and consistent Vault integration.
- [ ] Kits using shared item identity and configurable cooldowns/costs.

## M4 — Complete core

- [ ] **Open Risk — GUI / InvUI on 26.x:** before any GUI slice (inspection/restore, kits, warps, or addon menus), check upstream InvUI for real `26.x` support (`26 > 1` matching `R26` / `1.21.11` is not support); if unavailable, implement the GUI layer behind an adapter with a plain Bukkit inventory implementation.
- [ ] Handcuffs: moderation and optional claims bridge; complete state restoration.
- [ ] Phantom Obliterator: bounded search, named-mob exclusion, configurable charge policy.
- [ ] Rename service and token consumption committed together; no temporary permissions.
- [ ] Staff CrowBar feed independent of parties; explicit visibility policy and rate budget.
- [ ] Signs: `enabledSigns`, color/warp first, common service permissions and click budget.
- [ ] Signs: kit/balance/heal/repair/disposal/workbench/anvil/mail/info.
- [ ] Buy/sell/trade signs after transaction and stock recovery design.
- [ ] Timed broadcasts, **disabled by default**, filtered audiences and reload cleanup.
- [ ] AFK, seen/whois/near, speed/time/weather, give/item/enchant (porting `Selectors.java` to the command layer, player-only `/give` targeting, `minecraft:give` collision checks, and full modifier completion) and nicknames.
- [ ] Moderation, vanish/freeze, staff notes, inventory/ender-chest inspection and restore.
- [ ] Mail, chat channels, formatting, social spy and per-player locale.
- [ ] Home/warp access, economy bounds, command costs/cooldowns and other config parity.
- [ ] Permission catalogue, tab completion, diagnostics and audit trails.
- [ ] Player-name completion at every target position, especially `/mail gift`;
      bounded offline-name cache, alias coverage, permission/vanish filtering and
      no blocking work per keystroke.
- [ ] Explicit follow-up coverage for pet/offline teleport and next-login restores.

## M5 — Addons

- [ ] Minimal versioned API exercised by a real native Bukkit test addon.
- [ ] Standard `plugins/` installation, dependency checks and restart-only install/update.
- [ ] Fun Items: typed actions/triggers, cooldown scopes, outcomes and safe command policy.
- [ ] Harvest: crop growth/soil/renderers, its own data and pack; no Kitchen dependency.
- [ ] Kitchen: separate addon depending on Harvest; station/item/world PDC migration.
- [ ] Reliques: equipment/slot API, effects, transactions and persistence.
- [ ] Reliques UX regression: cards unequipped in `/relics` can immediately be
      placed in player inventory while the menu stays open. Verify cursor sync,
      clicks/shift-click/hotbar/drag, full inventory, close/disconnect and exactly-once
      boost updates; no stuck cursor, item loss or duplication.
- [ ] SpawnerCraft: own addon, recipe validation and safe ingredient/result accounting.
- [ ] Pregen Ores: separate terrain/ore controls, deterministic resumable chunk jobs.
- [ ] Trading Cards: stable tags/equipment/payouts and optional peer integrations.
- [ ] Trading Cards: BeastTokens API + lmbishop Quests/ExcellentQuests bonuses;
      shared preview/payout calculation, booster order, rounding and no double credit.
- [ ] Party Locator: membership, locator visibility/radius and optional mcMMO sync.
- [ ] Preserve core CrowBar when Party Locator is absent.
- [ ] Core artifact contains no addon implementation or addon-only assets.

## M6 — Release

- [ ] Legacy Allium and Essentials importers: dry-run, backups, mappings and reports.
- [ ] Repeat-run idempotency, interrupted-import recovery and staging rollback rehearsal.
- [ ] Domain, SQLite/MySQL, real Spigot/Paper and optional-provider verification.
- [ ] Full version matrix, pack-format compatibility and vanilla-client fallbacks.
- [ ] Profile command/event/idle costs, CrowBar fan-out and addon workloads.
- [ ] Fix slow `/core reload` behavior in the recode reload path: staged snapshots,
      bounded main-thread swap and targeted updates. Profile at peak SF player
      counts, preserve transactions, coalesce reloads and report timing/restart needs.
- [ ] Conservative core jar budget < 5,000,000 bytes; verify host limits independently.
