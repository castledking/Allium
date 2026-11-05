# Allium implementation checklist

[Roadmap and behavior contracts](docs/roadmap.md) are authoritative; see also the
[Give and Item Library design](docs/give-item-design.md). Checked items below mean
code/scaffolding exists, not that production verification is complete. All remaining
items are planned. Keep work in independently reviewable vertical slices; run checks
appropriate to each slice.

## Existing code

- [x] Gradle/Shadow build and module structure.
- [x] Plugin entry point, module/command/listener managers and logger.
- [x] Config loading, backups, schema migration scaffolding and missing-key sync.
- [x] Message enum, locale files, language fallback and message manager.
- [x] Database setup and economy/Vault scaffolding.
- [x] Heal, feed, fly, god and gamemode executors.

## First implementation queue

These slices make the first coding sessions concrete. Each includes an observable
result and must preserve the existing uncommitted work.

- [ ] **S01 / M0 — Baseline:** pin Gradle/build JDK and Java toolchains; compile
      shared code against Spigot 1.20/Java 17; audit Takion/InvUI/HikariCP and Shadow
      minimization; correct plugin API metadata; retire pre-1.20 scope. Gate: clean
      check/build/package and no-provider boot on minimum and maximum server lanes.
- [ ] **S02 / M1 — Command families:** canonical/alias/namespace policy and
      `disabled-commands: []`. Gate: disabling a family blocks all Allium aliases
      and lets another plugin own the label after restart; no command interception
      leaks, clear restart diagnostics and permission-aware completion.
- [ ] **S03 / M1 — Preferences:** UUID repository, schema migrations, defaults,
      bounded asynchronous storage and safe session loading. Gate: SQLite/MySQL
      round-trip and reconnect/restart behavior without accidental default overwrite.
- [ ] **S04 / M1 — Item contract:** parse, create, identify, match, clone and
      serialize vanilla/Allium references; first-colon namespace split (`craftengine:<ns>:<id>`);
      bare names resolve to vanilla only; behavior ID separate from appearance.
      Gate: metadata round-trip, strict provider-carrier matching and no AIR fallback.
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
      restoration and config changes; no duplicate Takion playback.

S02–S03 and S04–S05 can proceed in parallel after S01 with separate file ownership.
S06 depends on S04–S05; S07–S08 depend on S03 and the message pipeline.

## M0 — Foundation and migration inventory

- [ ] Record exact server/JDK/provider matrix and baseline current build results.
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
- [ ] Release assets/hashes, API compatibility metadata, license notices and upgrade guide.

## Scope decisions

- No Allium dialog framework/datapacks; CommandPanels owns dialogs.
- Shared Nexo/CraftEngine/Oraxen support and staple tools are core.
- Kitchen is separate from Harvest; SpawnerCraft is always its own addon.
- Addons use Bukkit loading, not a custom nested-jar classloader.
- Automatic downloads stay off and are deferred.
- Client companion and Fabric/Quilt/Forge/NeoForge work is deferred beyond this roadmap.
