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
      Bukkit executors and completers (`BukkitCommandRenderer`, `CommandMapBridge` with a
      single guarded reflective accessor, `CraftServer#syncCommands()`, and
      `Player#updateCommands()` for online players); canonical/alias/namespace policy
      (`CommandFamilyPolicy`) and `disabled-commands: []`; ported `/heal`, `/feed`,
      `/fly`, `/god`, and `/gamemode` onto the neutral command spec as proof. Gate:
      disabling a family blocks all Allium aliases (`gm`, `allium:gamemode`, `allium:gm`)
      and yields labels in Bukkit's `CommandMap` so another plugin owns the label;
      actual command-tree packets and suggestion round trips verified on Spigot
      1.20.1, Paper 1.20.6, and Paper 26.3 (see `tests/live/command-client/evidence/`); no command interception leaks, clear restart
      diagnostics on reload, and permission/visibility-aware completion; the five ported
      commands behave identically before and after the port across all 5 lanes.
- [ ] **S03 / M1 — Preferences:** UUID repository, schema migrations, defaults,
      bounded asynchronous storage and safe session loading. Gate: SQLite/MySQL
      round-trip and reconnect/restart behavior without accidental default overwrite.
- [x] **S04 / M1 — Item contract:** parse, create, identify, match, clone and
      serialize vanilla/Allium references; first-colon namespace split (`craftengine:<ns>:<id>`);
      bare names resolve to vanilla only; behavior ID separate from appearance;
      once-per-reload `ce:` deprecation warning; `schema_version` (`1`) in stamped PDC
      and `ItemRef.serialize()`; server-secret `HmacSHA256` signature (`v2:<keyId>:<hex>`)
      over `(provider, itemId, behaviorId, instanceUuid, canonicalState)` with keyring
      rotation (`active` + `retired` keys in `plugins/Allium/.item-hmac-secret`, included
      in `BackupManager`), automatic re-signing on `setState`, and loud
      `CRITICAL HMAC KEY MISMATCH` diagnostic on unknown key IDs; required for
      `privileged = true` definitions (`handcuffs`, `phantom_obliterator`); read-only
      legacy `allium:custom_item_id` and `alliumcards` tag inspection
      (`ItemService#readLegacyMetadata`); platform-neutral `ProviderKey`, `ItemRef`,
      `ResolutionStatus`, `ProviderStatus`, `ItemHmacSigner`, and `LegacyItemMetadata`
      in `allium-core`. Gate: metadata round-trip, forged/missing/tampered/state-edited/valid
      signature verification (surviving clone, `setState`, and appearance swap), strict
      provider-carrier matching and no AIR fallback across Spigot 1.20.1, Paper 1.20,
      Paper 1.20.6 (NBT-to-component boundary), Spigot 26.3, and Paper 26.3.
- [ ] **S04 follow-up — Item library:** shared Give/Enchant/Skull/Potion builders,
      bracket/brace/quote-aware grammar, `ItemFactory#createItemStack` / `ComponentAdapter`
      validation, cancellable `ItemGiveEvent`, configurable overflow policy, non-destructive
      `-e` equip, and async head resolution with target revalidation; bundle in core
      and expose API to addons (with an optional minimal M1 `GiveCommand` test harness).
      No extra required plugin, duplicate shaded API, internal command dispatch or
      duplicated item parsers.
- [x] **S05 / M1 — Provider adapter implementation:** isolated Nexo 1.29.1,
      CraftEngine core/bukkit 26.9.1 and Oraxen 1.211.0 compile-only API bindings;
      ready/reload/disable states, native ID/case preservation and diagnostics.
- [x] **S05 recorded live gate — conditional on patched CE:** isolated full builds
      of exact CE 26.9.1 and SF 26.9.2 preserve reset counts; each passes 20 native
      cases / 28 observations, including dynamic chains, same-stack repeats,
      64-item stacks, split/merge and partial drops. Stock builds still fail and
      are not covered by this PASS. Supplied Nexo/Oraxen all-three lifecycle,
      inventory, restart and RPM checks retain the recorded matrix scopes.
      See [provider matrix](docs/s05-provider-matrix.md) for hashes, retention and
      converter prerequisites. Stop/report at S05; S06 has not begun.
- [x] **S06a / M1 — Mob Disarmer behavior:** through `ItemService`, built-in visual plus
      Nexo/CE/Oraxen appearances and optional charge-variant swaps; `ProtectionService` with a
      GriefPrevention hook and cancellable check event; configurable charges, recharge, radius,
      break chance, drop rule (`slot-chance` default, as legacy, or `always`) and exclusions;
      legacy charge/timestamp import. Gate PASS: 58 live cases / 862 checks on Spigot 1.20.1,
      Paper 1.21.8 (all three providers) and Paper 26.3 (+GP3D), including a rejected-write
      fault case; see [results](tests/live/tools/disarmer/RESULTS.md).
- [x] **S06b / M1 — Core pack (generation):** asset manifest with stable IDs, allocations and
      texture provenance (`pack/`); SF inventory ([report](docs/s06b-sf-inventory.md)) and a local
      SF art export recipe; deterministic pack (7,055 bytes, SHA-1 `2413dc17…`, identical on
      JDK 17/21/25 and clean builds) with a 1.20 base and a 1.21.4+ item-definition overlay;
      charge states are model selections on the same stack; `resource-pack.owner` stub (`none`).
      Automated gate PASS: clean against Mojang client jars 1.20.1–26.3 and 125 live checks on
      Spigot 1.20.1, Paper 1.21.8 and Paper 26.3; see [results](tests/live/pack/RESULTS.md).
      Visual check pending: [checklist](tests/live/pack/VISUAL-CHECKLIST.md).
- [ ] **Pack delivery (separate step) / M1:** one delivery owner (standalone URL/hash or RPM export), no
      duplicate pushes, conflict reports for shared vanilla carrier files.
- [ ] **S07 / M2 — Messaging:** msg/reply, acceptance/ignore/mute/vanish rules,
      `/msgtoggle [on|off]`, per-recipient notifications. Gate: blocked sends are
      silent; replies obey the same policy; preference persists; console/others
      permissions and invalid arguments behave predictably.
- [ ] **S08 / M2 — Sound preferences:** omitted volume/pitch defaults, legacy
      lang.yml importer, `/messagesounds` aliases `/msounds` and `/mfx`,
      volume/toggle/reset. Gate: zero, finite ranges, independent defaults,
      restoration and config changes; Allium owns message sound playback directly.

S01.5a, S02–S03 and S04–S05 can proceed after S01 with separate file ownership.
S06a depended on S04–S05; S06b depended on S06a; pack delivery depends on S06b. S07–S08 depend on S01.5a, S03 and the message pipeline.

## S01 Audit: Shaded Dependencies & Takion Retirement Plan

### Bytecode & Shadow Minimization Findings
- **Allium shared classes:** Compiled against `org.spigotmc:spigot-api:1.20-R0.1-SNAPSHOT` with `--release 17` (class version 61 / Java 17). `allium-core` compiles with zero Bukkit/Spigot/Paper dependencies.
- **HikariCP 5.1.0 (`com.zaxxer:HikariCP`):** Class version 55 (Java 11). Excluded from Shadow `minimize()` because pool and proxy classes are loaded reflectively.
- **InvUI 1.49 (`xyz.xenondevs.invui`):** `invui-core`, `inventory-access`, and `inventory-access-r16..r18` (1.20.0–1.20.4) are class version 55–61 (Java 11–17). `inventory-access-r19..r26` (1.20.5–1.21.11) are class version 65 (Java 21), loaded reflectively via `InventoryAccessRevision` only on 1.20.5+ servers (which require Java 21+, verified on Paper 1.20.6 / OpenJDK 21). Excluded from Shadow `minimize()` so `Class.forName` lookups succeed.
  - **Open M4 Risk (InvUI on 26.x):** `InventoryAccessRevision` matches `26 > 1` and selects `R26` (compiled against `1.21.11` NMS internals) on `26.x`. Matching `26 > 1` is **not** real `26.x` support. Before any GUI slice in M4/M5, check upstream InvUI for `26.x` support; if none exists, place the GUI layer behind an adapter with a plain Bukkit inventory implementation.
- **Adventure API 4.17.0 (`net.kyori:adventure-api:4.17.0`):** Declared strictly as `compileOnly` in `build.gradle.kts` (not `implementation`, never shaded or relocated). At runtime on Paper, `AdventureTextBridge` links directly against Paper's bundled `net.kyori.adventure.*` classes; on Spigot, `TextSender` uses Allium's own `FormattedText#toLegacy` (`§x` hex + legacy formatting) without loading `AdventureTextBridge`.
  - **Jar audit for `net.kyori` classes:** `jar tf build/libs/Allium.jar | grep -i kyori` returns **0 classes** (`build/libs/Allium.jar` contains no `net.kyori.*` classes at all).

### Takion (`me.croabeast.takion:shaded:1.4`) Inventory & Retirement (Completed in S01.5a)
- `TakionLib` initialization (`LibInit.java`), `MessageManager`, `AlliumLogger`, and `StringUtils` were replaced with Allium's native `codes.castled.allium.text.*` (`LegacyAndTagParser`, `FormattedText`, `TextSender`, `AdventureTextBridge`, `SoundPlayer`).
- `me.croabeast.takion:shaded:1.4` and its repository were removed from `build.gradle.kts`.
