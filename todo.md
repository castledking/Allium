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

## M1 status (updated 2026-11-08)

All of M1's automated work is done:
- all nine roadmap deliverables are built and have recorded evidence (the minimal API since
  S09);
- the pack delivery this queue lists under M1 is done too (S06c);
- all five M1 gates are met.

Two items wait on others:
- **The S06b visual checklist:** a manual run in a real client, owned by the user. Until it
  passes, M1 is not signed off.
- **Stock CraftEngine:** deployment needs the patched build.

| M1 deliverable | Status | Evidence |
| --- | --- | --- |
| Minimal API | Done (S09): `allium-api` with item definitions and create/identify, command specs, the scheduler boundary and text; semantic API version 1.0.0, `allium-api` ranges in `plugin.yml`, refusal of mismatched and shaded addons; [docs/addon-api.md](docs/addon-api.md) | [S09 results](tests/live/addons/RESULTS.md): 38 live checks on Spigot 1.20.1 and Paper 26.3 |
| Preferences repository | Done (S03 and follow-up) | [preferences results](tests/live/preferences/RESULTS.md): 188, then 224 live checks; `1c67312`, `52ebe48` |
| Command-family policy | Done (S02) | [command-client gate](tests/live/command-client/README.md) and its `evidence/*.json` (Spigot 1.20.1, Paper 1.20.6, Paper 26.3); `de46a45`, `3be33f9` |
| Scheduler boundary | Done (this close-out): `TaskScheduler`/`AlliumScheduler` in `allium-core`, `BukkitServerThread`, all call sites migrated, `SchedulerBoundaryTest` forbids bypasses | `AlliumSchedulerTest` (6), `SchedulerBoundaryTest`; on jar `57ff304f`, the preference gate (224 checks, four lanes) and the item gate (105 checks, three lanes) passed again |
| Static text formatting, Takion retired | Done (S01.5a) | `TextFormatterTest`; [baseline gate](tests/live/baseline/RESULTS.md): 55 live checks on all five lanes. Spanish lang entries, a gradient, a hex colour and a message sound arrive identically on the Adventure and legacy paths, and the jar has no Takion or Adventure classes |
| Give/Enchant/Skull/Potion library | Done (S04 follow-up) | [items results](tests/live/items/RESULTS.md): 93 + 36 live checks. Not yet: `/i`, aliases, kits, `queue` overflow |
| Built-in/vanilla and three provider adapters | Done, conditionally (S05) | [provider matrix](docs/s05-provider-matrix.md), `tests/live/providers/evidence/`. Stock CE reset updaters lose stack counts; deploying requires the verified CE fix |
| Mob Disarmer across providers | Done (S06a) | [disarmer results](tests/live/tools/disarmer/RESULTS.md): 58 cases, 862 checks |
| Pack delivery (this queue's M1 item) | Done (S06c): `none`, `standalone` (export, operator-hosted URL with a startup SHA-1 check, required or optional) and `rpm-export` (mixer export with a carrier-collision report); added by UUID beside other packs on 1.20.3+ | [delivery results](tests/live/delivery/RESULTS.md): 52 live checks on Spigot 1.20.1, Paper 1.21.8 and Paper 26.3, and against a copy of SF's mixer |
| Core pack generation | Done; **visual check pending** (S06b) | [pack results](tests/live/pack/RESULTS.md): 125 live checks; [visual checklist](tests/live/pack/VISUAL-CHECKLIST.md) for a real client. Delivery: S06c, below |

| M1 gate | Status | Evidence |
| --- | --- | --- |
| Core is the only required plugin | Met | `plugin.yml` has `softdepend` only. Allium booted and passed with no item provider on S01's five lanes and on the Spigot 1.20.1 and Paper 26.3 lanes of S03, S04, S06a and S06b (S06a's 26.3 lane added only the optional GriefPrevention3D) |
| One behavior works standalone and with each supported provider | Met | S06a (built-in, Nexo, CraftEngine and Oraxen appearances on every case); S04 follow-up (each provider's disarmer given and signed) |
| Round-trip metadata/charges | Met | `ItemCoreContractTest`, `ItemServiceContractTest`; [baseline gate](tests/live/baseline/RESULTS.md): 100 item-contract checks on all five lanes, including signature rejection, an appearance swap and a restart across the NBT-to-component boundary; live in S06a (charges after provider reloads and a restart) and S06b (recharge visuals) |
| Provider readiness/reload safe | Met, for the recorded matrix | S05: `reload-cycle-final.log` and `restart-*.log` in `tests/live/providers/evidence/`; S06a persistence after `nexo`/`ce`/`oraxen` reloads. Conditional on the patched CE build |
| No vanilla-carrier false matches | Met | `ItemServiceContractTest#strictProviderCarrierNonMatchingAndAntiSpoofing`. Live (close-out): on Spigot 1.20.1, Paper 1.21.8 and Paper 26.3, a plain golden hoe and one with the disarmer's name and model data 2577020 are both identified as no Allium item ([items results](tests/live/items/RESULTS.md)) |

Open before M1 can close:
- [x] **S09 / M1 — Minimal versioned addon API.** The surface is cut to what Fun Items needs:
      - `allium-api`: item definitions (`allium:<id>`, behavior `<addon>:<id>`) with
        create/identify, command-spec registration for commands in the addon's `plugin.yml`, the
        scheduler boundary scoped to the addon, and Allium's text formatting. Exposed through
        Bukkit's `ServicesManager`.
      - Versioning: semantic API version 1.0.0, separate from the plugin version. Addons declare
        `allium-api: "^1.0"` (or `">=A <B"`), and a mismatched, shaded or invalid declaration
        disables only that addon, with a diagnostic.
      - `Allium-API-1.0.0.jar` holds only API types; addons use it `compileOnly`.
      - Restart-only installation and the deprecation policy are in
        [docs/addon-api.md](docs/addon-api.md).

      Gate passed 2026-11-08: 38 live checks on Spigot 1.20.1 and Paper 26.3; see
      [the results](tests/live/addons/RESULTS.md). It found and fixed a bug: addon items given
      through `/give` lost their default state.
- [x] **Pack delivery** (S06c, below).
- [ ] **S06b visual check** in a real client (owner: the user).

## Staging checklist (before deploying to SF)

Run on a staging copy of SF (its world, plugins and configs), never on the live server.
- [ ] **Real ResourcePackManager merge:** with `resource-pack.owner: rpm-export` (and
      `pack-file` set to the SF-art build), start the staging server.
      - ResourcePackManager must merge its full mixer, including `CraftEngine_resource_pack.zip`,
        which only the server user can read.
      - Confirm that the merged pack contains Allium's `golden_hoe` model and item definition.
      - Confirm that RPM's priority order puts Allium's carrier files where intended; note the
        `blaze_rod` collision with Nexo for when Phantom Obliterator ships.
      - Load the merged pack in a real client and run the disarmer rows of the
        [visual checklist](tests/live/pack/VISUAL-CHECKLIST.md).
- [ ] **Visual checklist with SF art,** on SF's server version.

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
- [x] **S03 / M1 — Preferences:** UUID repository (`allium_preferences`, nullable
      field = configured default, field-level patches with a `version`), schema migrations
      (`allium_schema`, newer schema refused), `preferences.defaults.*` plus
      `sounds.defaults.volume`, bounded per-UUID storage lanes (SQLite one serialized lane,
      MySQL four, drained on shutdown) and generation-safe sessions (load at pre-login;
      LOADING/FAILED never enforce defaults; stale loads discarded). Fields: the three accept
      flags, `message-sound-volume` and its restoration state, with the `/mfx` rules as
      code. Gate passed 2026-11-07: 188 live checks on Spigot 1.20.1 and Paper 26.3 against
      SQLite and MariaDB (round trip, restart, reconnect during a slow load, failed load,
      two concurrent writers, shutdown drain, no default overwrite); see
      [the results](tests/live/preferences/RESULTS.md). Follow-up passed 2026-11-07 with
      224 live checks on the same lanes. It adds:
      - background load retries with capped backoff, and fail-closed `acceptance`
        (`UNAVAILABLE` plus `preferences.unavailable`);
      - a live check that a login denied before `MONITOR` gets no session;
      - a configurable shutdown timeout that names every unsaved write (checked live with
        the database unreachable);
      - notification sound levels (recipient volume times message volume).

      Not yet: offline lookups for `/pay` and other offline-player uses, and cross-server
      push of other servers' writes. Cross-server enforcement must read the authoritative
      row (see the roadmap contract).
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
- [x] **S04 follow-up — Item library and M1 give harness:** `ItemSpecParser` over
      `ProviderKey` with a bracket/brace/quote-aware lexer (gated legacy `;` syntax), human
      tokens (enchantments, `owner:` with a bounded async skull cache, `potion:`/`effect:`/
      `color:`, `name:`/`lore:`, `unbreakable`), the version-split `ComponentAdapter`
      (`[...]` from 1.20.5, `{...}` before; vanilla items only), `ItemDeliveryService` with
      `drop`/`refuse`/`partial` overflow, target revalidation, cancellable `ItemGiveEvent` and
      non-destructive `-e`, and `/give` on the command spec as the M1 harness (signed items are
      minted one per stack; privileged behaviors need `allium.give.privileged`). Gate PASS:
      93 live checks on Spigot 1.20.1, Paper 1.21.8 (all providers) and Paper 26.3, plus 36
      compatibility checks (command blocks, `/execute`, `minecraft:give`, functions, non-op
      senders, skull fallbacks and one real Mojang lookup); see
      [results](tests/live/items/RESULTS.md). Not yet: `/i`, command item aliases, kits,
      addon API surface, claim-storage `queue` overflow.
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
- [x] **S06c / M1 — Pack delivery:** `resource-pack.owner` is `none` (default), `standalone` or
      `rpm-export`.
      - **standalone:** exports the bundled pack to `plugins/Allium/pack/` and sends the
        operator-hosted URL with its SHA-1. It verifies the hosted file once at startup and
        sends nothing on a mismatch. There is no built-in HTTP server.
      - **rpm-export:** writes only `Allium_core_resource_pack.zip` into RPM's mixer, from
        `onLoad`. It never edits RPM's config, and it reports carrier collisions, including SF's
        blaze rod. It has a dry-run mode.
      - **Multiple packs:** from 1.20.3 Allium adds its pack by its own UUID beside other packs;
        on 1.20–1.20.2 it warns that one delivery owner is mandatory.
      - **Policy:** required or optional, with configurable prompt and kick message. A decline
        keeps the vanilla fallbacks unless the pack is required.

      Gate passed 2026-11-08: 52 live checks (bot accept, decline and hash mismatch on all three
      lanes, coexistence with a second pack on 1.20.3+, and an rpm-export dry run and export
      against a copy of SF's mixer); see [the results](tests/live/delivery/RESULTS.md) and
      [docs/pack-delivery.md](docs/pack-delivery.md).
- [ ] **S07 / M2 — Messaging:** msg/reply, acceptance/ignore/mute/vanish rules,
      `/msgtoggle [on|off]`, per-recipient notifications. Gate: blocked sends are
      silent; replies obey the same policy; preference persists; console/others
      permissions and invalid arguments behave predictably.
- [ ] **S08 / M2 — Sound preferences:** omitted volume/pitch defaults, legacy
      lang.yml importer, `/messagesounds` aliases `/msounds` and `/mfx`,
      volume/toggle/reset. Gate: zero, finite ranges, independent defaults,
      restoration and config changes; Allium owns message sound playback directly.

### Recorded inputs for later slices

- [ ] **Handcuffs (M4):** port the behavior onto `allium:handcuffs`. Recorded from the
      [SF inventory](docs/s06b-sf-inventory.md): SF's four handcuff variants have collapsed
      identities. `handcuffs_restrained`, `claim_handcuffs` and `claim_handcuffs_restrained`
      all force PersistentData `nexo:id` to `handcuffs`, so Nexo's id tag no longer tells them
      apart. `handcuffs_restrained` also sets `allium:custom_item_id` to `handcuffs`, and
      `claim_handcuffs_restrained` has no `allium:custom_item_id` at all (only a stray
      `claim_handcuffs_restrained` key). The slice must decide how a claim handcuff and a
      restraining state are told apart (for example Nexo's registry id or the FISHING_ROD
      custom_model_data 1012–1015) before trusting either tag.
- [ ] **Legacy item importer (with handcuffs, M4):** importing SF handcuffs cannot key on
      `nexo:id` or `allium:custom_item_id` alone, for the reasons above; the restrained
      variants may be transient states rather than separate tools. Record each variant's
      mapping and its evidence before converting stacks; never upgrade a stack to a privileged
      behavior from a forged or collapsed tag.

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
