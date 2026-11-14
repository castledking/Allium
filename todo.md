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
| Give/Enchant/Skull/Potion library | Done (S04 follow-up) | [items results](tests/live/items/RESULTS.md): 93 + 36 live checks. Not yet: `/i`, aliases, `queue` overflow |
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

## M2 status — done (2026-11-13)

**Live verified:** the S07/S08/S11/S12 communication and preferences slices have passed their
recorded gates. The requested sender-disconnect and abrupt mid-claim process-termination cases
ran live on both server lanes with SQLite and MySQL; the exact assertions are listed in
[the M2 close-out](tests/live/mail/RESULTS.md#m2-close-out-requested-live-recovery-cases). S11 is commit `caec390`. S12 adds mail/gifts, cached offline completion and shared
notifications: **541 checks** on Spigot 1.20.1 and Paper 26.3 with SQLite and MySQL, plus overlapping
two-server MySQL claims and cross-version signed-item transfer. See [S12 results](tests/live/mail/RESULTS.md).

**Unit only:** the final build passes **441 tests** and `shadowJar`; 50 selected mail/preference
repository checks also passed against SQLite and disposable MySQL. Unit coverage includes typed
PDC, cancelled-kick quarantine, failed save proofs and interrupted schema migrations.

M3 economy costs are reserved, not implemented by mail. Physical database outages and the pack's
manual visual checklist remain outside the S12 live claims. S13 begins M3 after this close-out.

## M3 status — in progress

| Slice | Commit | Live lanes | Storage backends | Test count | Limits |
| --- | --- | --- | --- | --- | --- |
| **S13** Teleports, requests, `/back`, `/spawn` | `bcff02a` | Spigot 1.20.1 (`15/15` cases, `117/117` checks); Paper 26.3 (`13/15` original + `22/22` death supplement + `84` retained audit checks); [results](tests/live/teleport/RESULTS.md) | SQLite WAL live; no MySQL live | `488` (`+47`) | No MySQL/MariaDB live, Folia, selectors, economy, or cross-server teleport; raw Paper client moved-too-quickly warnings |
| **S14** Economy design only | `4104707`, `a8330a2`, `799db88` | Not run (`unit-only` + standalone JDBC probe on SQLite WAL FULL/NORMAL & durable MySQL, 6 kill cases) | Probe benchmarked SQLite WAL & MySQL; engine unimplemented | Probe + scaffold tests (`EconomyModuleRegistrationTest`) | Replacement economy & gated Vault adapter remain unimplemented; `economy.scaffold-vault-provider-enabled: false` temporary safety gate |
| **S15** Homes & private warps | `ce6350d` | Spigot 1.20.1 (`12/12` cases, `406/406` checks); Paper 26.3 (`12/12` cases, `406/406` checks); [results](tests/live/travel/RESULTS.md) | SQLite WAL live; no MySQL live | `518` (`+30`) | SQLite live only; no MySQL, Folia, provider plugins, pet teleport, offline restore, or cross-server sync |
| **S16** Free kits & durable receipt recovery | `1404bc5`, `c295bb6` | Spigot 1.20.1 (`7/7` cases, `65/65` checks); Paper 26.3 (`7/7` cases, `65/65` checks); `shared-mysql` two-server Spigot 1.20.1 (`1/1` case, `11/11` checks) = `15` cases / `141` checks; [results](tests/live/kits/RESULTS.md) | SQLite WAL live (`spigot`, `paper`); MariaDB 11.4 live (`shared-mysql`) & JUnit | `531` (`1404bc5`); `528` after S16 follow-up/S17 merge (see `019fb55`) | Single-server lanes used `KitGate` test `nexo` stub (Paper 1.21.8 covers real providers); paid kits denied (`cost > '0.00'`) & `[Kit]` signs deferred |
| **S17** Persistent `/vanish`, `/mute`, `/unmute`, `/mutes` | `44ed23b`, `019fb55` | Spigot 1.20.1 (`4/4` scopes, `176/176` checks); Paper 26.3 (`4/4` scopes, `176/176` checks); Spigot 1.20.1 MariaDB 10.11 (`1/1` scope, `71/71` checks) = `423` checks; [results](tests/live/moderation/RESULTS.md) | SQLite WAL & MariaDB 10.11 (both live + JUnit) | `528` (`44ed23b`) -> `537` (`019fb55`) | Spigot 1.20.1 lacks Paper APIs for vanilla tab filtering, advancement broadcasts, and collision XP orb pickup (startup warning emitted); sign/book/anvil text outside chat/commands |
| **S18** Player info utilities & `/i`, `/item` | `a81cf3b` | Spigot 1.20.1 (`33/33` checks); Paper 26.3 (`33/33` checks); [results](tests/live/playerinfo/RESULTS.md) | SQLite WAL live; MariaDB 10.11 & SQLite via JUnit | `545` (`+8`: `349` root + `192` core + `4` api) | Live lanes on SQLite only (`PlayerInfoRepositoryTest` & `PlayerNameRepositoryTest` cover MariaDB 10.11); Vault primary-group fallback unit-only |

Economy implementation remains later work ([economy-design.md](docs/economy-design.md)): Vault double amounts use a narrow cent-boundary snap while core decimals stay strict, and `economy.scaffold-vault-provider-enabled` remains off by default until the replacement economy engine ships.

## Staging checklist (before deploying to SF)

Run on a staging copy of SF (its world, plugins and configs), never on the live server.
- [ ] **Real ResourcePackManager merge:** with `resource-pack.owner: rpm-export` (and
      `pack-file` set to the SF-art build), start the staging server.
      - ResourcePackManager must merge its full mixer, including `CraftEngine_resource_pack.zip`,
        which only the server user can read.
      - Confirm that the merged pack contains Allium's `golden_hoe` model and item definition.
      - Confirm that RPM's priority order puts Allium's carrier files where intended.
      - Confirm that CraftEngine's pack (unreadable here) does not override `phantom_membrane`, the
        Phantom Obliterator's carrier since S10. If it does, move the carrier before the art ships.
      - Check `netherite_axe`, which Nexo's pack overrides, before the Lazy Axe gets art.
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
      [results](tests/live/items/RESULTS.md). Not yet: `/i`, command item aliases,
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
- [x] **S07 / M2 — Messaging:** `/msg` (`tell`, `w`, `whisper`, `m`, `t`), `/reply` (`r`),
      `/ignore`, `/unignore` and `/msgtoggle [on|off] [player]`; see
      [docs/messaging.md](docs/messaging.md).
      - **Policy:** one `MessagePolicy` in `allium-core`, shared by msg and reply and checked
        again at delivery. It covers mute, vanish, self, ignore and acceptance, and unloaded
        preferences or ignore lists fail closed.
      - **Privacy:** toggled-off and ignoring recipients give the same generic sender message.
        A vanished recipient looks offline. A blocked send gives the recipient no message and no
        sound. Bypass needs explicit permissions (`allium.msg.bypass.toggle`,
        `allium.msg.bypass.ignore`, both default false).
      - **Untrusted bodies:** formatting is shown literally unless the sender has
        `allium.msg.color`, `.hex` or `.gradient`, and nothing interactive can be injected. On
        Spigot, bodies are sent as explicit components, so URLs are not auto-linked.
      - **Storage and hooks:** ignore lists are in their own table, `allium_ignores`. Mute and
        vanish are hooks with no-op defaults, plus a reader for the `vanished` player metadata.
      - **Reply target:** the most recent partner, cleared when they go offline. The console can
        send and reply.

      Gate passed 2026-11-08: 46 live checks on Spigot 1.20.1 and Paper 26.3, with two bots and
      the console; see [the results](tests/live/messaging/RESULTS.md). It found and fixed a bug:
      Spigot turned URLs in message bodies into clickable links.
- [x] **S11 / M2 — Social spy:** `/socialspy [on|off]` (`allium.socialspy`, default op), a
      per-UUID opt-in in the preferences table (migration v2, `social_spy`); see
      [docs/messaging.md](docs/messaging.md#social-spy-s11).
      - **What a spy sees:** delivered `/msg` and `/reply` traffic between others, including the
        console. Never their own conversations, and never a blocked attempt (toggle, ignore,
        mute, offline, self). Both are verified live.
      - **Exempt and vanished:** `allium.socialspy.exempt` (default nobody, not under `allium.*`)
        on either participant hides the traffic. A spy must be able to see both participants
        (vanish metadata, Bukkit hidePlayer, `allium.vanish.see`). Both are verified live.
      - **Console log:** `messaging.socialspy.log-to-console`, default off and documented as a
        privacy setting. Verified live: off by default, and on only after an explicit opt-in.
      - **Notifications:** messages and spy copies go through one `NotificationService` (one
        text, at most one sound, scaled by the spy's `/mfx` volume). Verified live: the spy
        copy has no sound by default; with a configured sound it follows `/mfx`.

      Gate passed 2026-11-12: 734 live checks (367 on each of Spigot 1.20.1 and Paper 26.3),
      with three bots and the console, including persistence across a restart; see
      [the results](tests/live/socialspy/RESULTS.md). Unit tests: 78 new, covering the policy,
      the router, the command and the storage migration.
- [x] **S12 / M2 — Mail and gifts:** `/mail read|send|gift|claim|delete|clear`; see
      [docs/mail.md](docs/mail.md). Live verified: 541 checks, zero failures, with the same final artifact across five suites.
      - Pending escrow and conditional claim tokens; exact current payload, shared item-delivery
        cancellation/plans, remainder retained, immutable acknowledgements and saved-receipt readback.
      - Bounded memory-only name and mailbox-ID completion with permission/visibility filtering;
        authoritative execution lookup, recipient acceptance/ignores and unloaded-state refusals.
      - One notification path for unread/new mail; no mail-body spying; reload keeps in-flight work.
      - Future M6 source mapping documented; no legacy importer and no M3 money debit implemented.
- [x] **S13 / M3 — Safe teleports and requests:** one service for requests, back,
      spawn and audited staff force moves; safety, cancellable warmups, cooldowns,
      expiry, current privacy checks and persistent travel state. Gate: 15 named
      cases on Spigot 1.20.1 and Paper 26.3, with a focused Paper death-back
      supplement and retained-evidence audit. See [results](tests/live/teleport/RESULTS.md).
      SQLite live only; no Folia, selectors or cross-server transport claim.
- [x] **S14 / M3 — Economy design only:** exact minor units, atomic SQLite/MySQL
      transfers, journal/recovery, authoritative offline payment policy, reserved
      mail/kit costs and legacy import mapping. The disposable JDBC probe measured
      both backends (including 3-operation burst totals; MySQL misses `p99 <= 2 ms`
      by an order of magnitude, so synchronous Vault is SQLite-only); the actual
      synchronous Vault adapter still requires its own performance and failure gate.
      The scaffold Vault provider is off by default (`economy.scaffold-vault-provider-enabled: false`)
      as a **temporary state** (release condition: the replacement economy engine replaces it).
      No economy implementation was added.
- [x] **S15 / M3 — Homes and warps:** `/sethome`, `/home`, `/delhome`, `/homes`,
      `/setwarp`, `/warp`, `/delwarp`, `/warps` on TeleportService. Atomic persisted
      home limits; snapshot/revision/connection-bound overwrite confirmation;
      false-default per-warp access and completion; warmup-time access/revision
      rechecks. See [contract](docs/homes-warps.md) and [live results](tests/live/travel/RESULTS.md).
- [x] **S16 / M3 — Kits (free kits only; paid kits stay denied until reservations exist):**
      `/kit`, `/kits`, `/kit <name> [player]`, `/createkit <name> [cooldown] [once]` and
      `/delkit <name>`; see [docs/kits.md](docs/kits.md) and [live results](tests/live/kits/RESULTS.md).
      - Kits stored under `plugins/Allium/kits/*.yml` and built through `ItemSpecParser`,
        `ItemLibrary` and `ItemService` so provider items (`nexo:`, `craftengine:`, `oraxen:`)
        and signed/privileged Allium items mint fresh instances.
      - False-default per-kit permission (`allium.kits.<name>`), persisted per-player cooldowns
        and one-time status (`allium_kit_usages`), and all-or-nothing `REFUSE` overflow policy
        via `ItemDeliveryService` so full/partial inventory or cancelled `ItemGiveEvent` never
        burns cooldown or one-time status.
      - Durable delivery records (`allium_kit_deliveries`, `allium_kit_claim_receipts`) and
        saved `player.dat` receipt verification (`allium:kit_receipts` + `PlayerDataReceiptProof`)
        so disconnect or crash mid-delivery never double-delivers or burns cooldown without items.
      - Paid kits (`cost > 0`) are explicitly denied until M3 economy reservations exist; sign
        hooks remain for the signs slice; no console command execution.
- [x] **S17 / M3 — Vanish and mute:** real persistent `VanishSource` and `MuteSource`
      implementations (`ModerationService`, `ModerationRepository`, bounded `Allium-Moderation`
      SQL executor); see [docs/vanish-mute.md](docs/vanish-mute.md) and
      [S17 results](tests/live/moderation/RESULTS.md).
      - **Vanish:** `/vanish [on|off] [player]` (`/v`), persisted in `allium_vanish`, hiding
        from non-`allium.vanish.see` players (`hidePlayer`), tab list, `ServerListPingEvent`,
        join/quit broadcasts, mob targeting, item/arrow pickup, physical interactions, sculk
        vibrations, sleep checks, silent `ENDER_CHEST`/lidded container mirror, tab completion,
        `/msg`/`/reply`/`/ignore`/`/msgtoggle`/`SocialSpy`/`/tpa`/`/tpahere`/`/tpaccept`/`/tpdeny`/
        `/heal`/`/kit`, and relog/restart persistence without revealing the player.
      - **Mute:** `/mute <player> [duration] [reason]`, `/unmute <player> [reason]`, `/mutes`
        (`/mutelist`, `/mute list`), applied across public chat, `/msg`, `/reply`, `/mail send`,
        and `/mail gift`, with remaining-time notices, automatic non-restart timed expiry, and
        SQL (`allium_moderation_audit`) + console audit logs.
      - **Follow-ups:** vanilla `/list`, `/minecraft:msg`/`/tp`/`@a`/`@p`/tab filtering (with
        documented Spigot 1.20.1 Brigadier/advancement/XP-pickup limits), `moderation.vanish.allow-public-chat`,
        mute chat `HIGHEST` re-cancel, configurable `moderation.mute.muted-commands`, digit-leading
        duration rejection, fail-closed `LOADING`/`FAILED` + backoff retries, explicit
        `allium.vanish.container.live`, and visibility recomputation on join/`/vanish`/`/allium reload`.
      - Gate passed: 176/176 checks on Spigot 1.20.1 (SQLite), 176/176 on Paper 26.3 (SQLite),
        and 71/71 on Spigot 1.20.1 (MariaDB 10.11); 537 JUnit tests pass across SQLite and MariaDB.
- [x] **S18 / M3 — Player info utilities and `/i`, `/item`:** vanish-aware `/list` (`/online`),
      `/seen <player>`, `/whois <player>`, `/near [radius]`, `/afk [message]` (`/away`), and `/i`/`/item`
      self-use aliases over `GiveCommand`; see [docs/player-info.md](docs/player-info.md) and
      [S18 results](tests/live/playerinfo/RESULTS.md).
      - **Looks-offline & visibility rule:** every lookup re-evaluates `PlayerNameVisibility#canSeeTarget`,
        rendering offline-identical responses for vanished targets across `/list`, `/seen`, `/whois`
        (even with `allium.whois.location`), `/near`, `/afk` broadcasts, and tab completion;
        pre-vanish `last_seen` is preserved across vanished quit, relog, and server restart.
      - **AFK & sleep coordination:** configurable `info.afk.auto-afk-seconds` and off-by-default
        `kick-on-afk` (`allium.afk.kick.exempt`); shared `setSleepingIgnored(vanished || afkSleepIgnored)`
        verified live with real-bed night skip.
      - **Bounded SQL:** dedicated single-worker `Allium-PlayerInfo` pool (`allium_player_info` v1 and
        `allium_player_name_history` v2) with zero sync SQL on the main thread.
      - Gate passed: 33/33 live checks on Spigot 1.20.1 and 33/33 on Paper 26.3; 545 JUnit tests
        pass against SQLite and MariaDB 10.11.
- [ ] **Travel follow-up — Pet teleport.** Explicit future slice, not provided by S15.
- [ ] **Travel follow-up — Offline home/restore administration and legacy import.**
      Explicit future slice, not provided by S15.
- [x] **Data-driven core tools (before S08):** the Mob Disarmer is `tools/mob_disarmer.yml`,
      bound to the one Java behavior `allium:disarm`, which owns the transaction.
      - **Validation:** definitions are validated at load time with path-specific diagnostics,
        and an invalid one is rejected whole.
      - **Several definitions per behavior:** each has its own signed identity and state (unit
        tests).
      - **Migration:** config.yml's old `tools.mob-disarmer` section moves to the file on first
        start, and existing stacks keep their IDs, state and MACs (unit tests).
      - **Live:** 153 checks on each of Spigot 1.20.1 and Paper 26.3, with Allium.jar
        `b334b1e0…` (runs in `/tmp/allium-s06a-20261108/runs/`). Every existing S06a case passed
        with the YAML-defined default. The pattern is in the
        [roadmap](docs/roadmap.md#data-driven-core-tool-architecture-pluginsalliumtoolsyml).
- [x] **Data-driven tools: gate gaps** (closed in S10, 486 live checks; see the
      [disarmer results](tests/live/tools/disarmer/RESULTS.md#s10-data-driven-definitions-the-gaps-closed-2026-11-09)):
      - `allium:wand_disarmer` used live, with its own charges, radius and drop rule.
      - The invalid definition's six path-specific diagnostics checked word for word, and it
        registered nothing.
      - The claim case on 26.3 with GriefPrevention3D 19.0.5.
      - A live upgrade from the S07 jar: state and MAC intact, and `recharge-seconds: 90` moved
        and applied.
      - Provider appearances on 1.21.8.
- [x] **Design slice (docs only, before S08):**
      - [ExecutableItems comparison](docs/fun-items-reference.md).
      - The **Curios** addon restructure in the [roadmap](docs/roadmap.md#curios-addon-custom-items-enchants-and-equipment):
        Fun Items, custom enchantments and Reliques become one addon with one engine and three
        attachment points.
      - [Custom enchantment design](docs/curios-enchants-design.md), with AdvancedEnchantments
        and EcoEnchants as references.
      - [Lazy Axe port](docs/lazy-axe-design.md) and [Phantom Obliterator port](docs/phantom-obliterator-design.md).
- [x] **S08 / M2 — Sound preferences:** `/messagesounds` (`/msounds`, `/mfx`) with `volume <0-1>`,
      `toggle` and `reset`; see [docs/sounds.md](docs/sounds.md).
      - **Input:** only plain decimals from 0 to 1 are accepted; anything else changes nothing.
      - **Storage:** the setting and its restoration state persist, and they fail closed while a
        player's preferences load.
      - **Reload:** `/allium reload` re-reads the preference defaults, so a reset player follows a
        changed `sounds.messages.fallback-volume` at once.
      - **Lang entries:** an entry's omitted volume and pitch fall back separately to
        `sounds.defaults`. Lang sync no longer puts back a removed `volume` or `pitch`.
        `sound: none` turns a sound off. `messaging.sent`'s sound, if any, plays for the sender,
        scaled by their own `/mfx` volume (`sounds.messages.scale-sent-sound`, default true; live
        check on Spigot 1.20.1).
      - **Import:** `/allium import lang [apply]` imports a pre-recode `lang.yml`; it is a dry run
        unless `apply`, backs up the target, keeps customized messages, can be rerun, and reports
        what it dropped. See [docs/lang-import.md](docs/lang-import.md).

      Gate passed 2026-11-09: 110 live checks (55 on each of Spigot 1.20.1 and Paper 26.3), with
      two bots and the console; see [the results](tests/live/sounds/RESULTS.md). It found and
      fixed two bugs: lang sync re-added omitted sound values, and preference defaults were read
      only at startup.
- [x] **S10 / M4 — Tools: Phantom Obliterator and Lazy Axe** (after S08), implemented from the
      [Obliterator](docs/phantom-obliterator-design.md) and [Lazy Axe](docs/lazy-axe-design.md)
      designs as `allium:obliterate_phantoms` and `allium:fell_tree` definitions. The operator
      guide is [docs/tools.md](docs/tools.md).
      - **Phantom Obliterator** (`tools/phantom_obliterator.yml`, enabled):
        - a privileged signed identity on a phantom membrane (moved off `blaze_rod`);
        - protection asked per target, spend first, and refunds bound to the same instance UUID;
        - pre-recode tools adopted in place only while `/allium adopt-window <duration>` is open.
          The window is console-only by default and logged; a creative-forged tag is never
          adopted or signed.
      - **Lazy Axe** (`tools/lazy_axe.yml`, **off by default**):
        - natural-tree detection, and a paced bottom-up fell within a per-tick budget;
        - a re-entry guard and `LazyAxeFellEvent`;
        - a protection check and recheck per log, and a stop on chunk unload, item swap or quit;
        - `stop-before-break`, the sneak modes and `allium.treeaxe.use`;
        - in-place `tree_axe` import.
      - **Carrier suppression:** an Allium tool is never used up as a material. That covers
        crafting and the crafter, smelting and fuel, brewing, the anvil's second slot, smithing,
        the grindstone, trades and hopper transfers. Repairing or enchanting a tool in its own
        slot still works.
      - **Gates passed:**
        - **2026-11-12, the Lazy Axe:** 251 live checks
          ([results](tests/live/tools/lazyaxe/RESULTS.md)).
        - **2026-11-12, suppression:** 63 ([results](tests/live/tools/suppression/RESULTS.md)).
        - **Reruns on the same jar:** the Obliterator 177
          ([results](tests/live/tools/obliterator/RESULTS.md)) and the disarmer gaps 486
          ([results](tests/live/tools/disarmer/RESULTS.md)).
        - **Lanes:** Spigot 1.20.1, Paper 26.3 with SF's GriefPrevention3D 19.0.5, and a Paper
          1.21.8 provider appearance check per tool.
      - **Fixed after the first S10b commit:**
        - The shipped `lazy_axe.yml` failed to load once enabled: its core item was bound to the
          behavior type.
        - Suppression blocked every custom item and the anvil's first slot, and its crafter hook
          never fired.
        - The adoption window could only be opened from the test fixture.
      - **2026-11-12 follow-up, verification separated:**
        - **Live:** 28 fresh-install checks (14 per lane, Spigot 1.20.1 and Paper 26.3)
          show the shipped disabled axe breaks one log and, after enabling/restarting,
          fells a world-generated spruce. `/give` warns when its definition is disabled.
        - **Live, Spigot only:** 79 Obliterator regression checks after charge-model selection.
        - **Automated pack validation:** a local SF export adds Lazy Axe and Obliterator
          textures and resolves against client jars 1.20.1, 1.21.8 and 26.3. SF texture
          bytes remain outside git; [scope and hashes](docs/pack-delivery.md).
        - **Still unverified:** rendered client visuals and the deployment merge choice for
          the confirmed `netherite_axe` carrier collision. No SF deployment was made.
        - **Still open:** an offline importer for privileged item data.

### Recorded inputs for later slices

- [ ] **Curios (M5):** one addon (`Addon-Curios-<version>.jar`) in place of the separately
      planned Fun Items and Reliques addons, plus custom enchantments. See the
      [roadmap](docs/roadmap.md#curios-addon-custom-items-enchants-and-equipment).
      - **Engine:** one trigger, condition, action and cooldown engine, attached to the item
        itself, an enchantment on an item, or an equipped slot.
      - **Toggles:** each module can be turned off, without touching item state.
      - **Core behaviors** are typed actions (`disarm`, `fell_tree`, `obliterate_phantoms`) and
        are never reimplemented. This needs addon API 1.1.
      - **Trading Cards:** its optional slot hook targets Curios.
      - **Order:** the engine and items first, then the slots (the Reliques port, with its import
        and the inventory UX fix), then enchantments.
      - **SF:** keep the enchantment module off while AdvancedEnchantments is installed, until a
        migration is decided.

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
