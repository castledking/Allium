# S24 Inspection Live Gate Results

## 2026-11-18 rerun (r9)

98/98 on Spigot 1.20.1 and on Paper 26.3 on jar `18e32537…` (runs `r9-spigot`, `r9-paper` under
`/mnt/storage/.allium-validation/20261118/s24-inspection/runs`). The layout checks now expect the
held hotbar cell to be empty instead of a marker pane.


**PASS: 196/196 live checks** across **Spigot 1.20.1** (Java 17) and **Paper 26.3** (Java 25) on identical frozen `build/libs/Allium.jar` (`SHA-256: bc12cf2a0c0c210b7c5586c45a822b33376e6cf608992ff8ef0a305b32d656c6`), SQLite storage backend with `economy.vault-provider-enabled: false`.

- **Spigot 1.20.1 / Java 17 / SQLite** (`spigot-r14`, superseding `spigot-r10` `86/86` on `ec97af2c`): **98/98 checks passed, 0 failures**
  - Run manifest: `/mnt/storage/.allium-validation/20261115/s24-inspection/runs/spigot-r14/manifest.json`
  - Scopes: `spigot:permissions-hierarchy-vanish`, `spigot:online-layout-confinement-edits-guards`, `spigot:offline-nbt-cas-loginfence`, `spigot:restart-persistence`
  - SQLite `allium_moderation_audit` rows persisted: `42` (`INVSEE_OPEN`, `INVSEE_EDIT`, `INVSEE_ABORT`, `ENDERCHEST_OPEN`, `ENDERCHEST_EDIT`, plus vanish events)
- **Paper 26.3 / Java 25 / SQLite** (`paper-r2`, superseding `paper-r1` `86/86` on `ec97af2c`): **98/98 checks passed, 0 failures**
  - Run manifest: `/mnt/storage/.allium-validation/20261115/s24-inspection/runs/paper-r2/manifest.json`
  - Scopes: `paper:permissions-hierarchy-vanish`, `paper:online-layout-confinement-edits-guards`, `paper:offline-nbt-cas-loginfence`, `paper:restart-persistence`
  - SQLite `allium_moderation_audit` rows persisted: `42` (`INVSEE_OPEN`, `INVSEE_EDIT`, `INVSEE_ABORT`, `ENDERCHEST_OPEN`, `ENDERCHEST_EDIT`, plus vanish events)

Full `./gradlew check shadowJar --offline` result: **799 unit tests** (`allium-api`: 4, `allium-core`: 278, root: 517; **+18** from S23 baseline 781: **+15** initial S24 [`InspectionLayoutTest` 5, `OfflinePlayerDataStoreTest` 4, `InspectionServiceTest` 6] plus **+3** S24 follow-up hardening [`OfflinePlayerDataStoreTest` +1, `InspectionServiceTest` +2]), **0 failures, 0 errors, 0 skipped**.

## Baseline 86-Check Breakdown (`spigot-r10` / `paper-r1`) & Follow-Up Closures (`spigot-r14` / `paper-r2`)

Breakdown of each **86-check** baseline lane (`spigot-r10`, `paper-r1`) by exact check index:
1. **Boot, preflight, restart readiness & log hygiene (5 checks):** #1 (boot/ready), #2 (`vault-provider-enabled=false`), #83 (post-restart boot/ready), #84 (post-restart Vault flag), #86 (server log clean).
2. **Permission fixture setup/teardown assertions (40 checks):** #3–#8, #10, #12, #14–#15, #17–#24, #28, #30, #32, #35–#39, #45–#46, #48, #54, #56–#57, #59, #61, #66–#68, #70, #73, #76.
3. **Command gating, hierarchy & vanish parity (11 checks):** #9 (console rejection), #11 (self `/ec`), #13 (seeded inventory/armor/EC), #16 (`bypass` overrides `exempt`), #25–#26 (`/invsee`/`/echest` tab-hiding vanished targets), #27/#29/#31 (vanish blindness parity without `.offline`, with `.offline`, with `.offline`+`bypass`), #33–#34 (`vanish.see` tab + open).
4. **Online layout, confinement, edits, guards, death & quit flush (18 checks):** #40–#44 (`/invsee` 45-slot layout, concurrent read-only viewers, RO confinement, live 150 ms refresh), #47/#49–#53 (`Edit` title, simultaneous RO+editor, locked panes `41..44`, hotbar/armor/offhand edits, 1-tick Drop/Swap guard), #55 (mid-session `.edit` revocation abort), #58 (`MailService` busy lock allows RO), #60/#62–#63 (27-slot `/ec` RO + `.edit`), #64 (target death aborts mutable `/invsee` & drops post-edit gear cleanly), #65 (target quit flush + `BukkitValues`).
5. **Offline `.dat`, CAS, login fence, audit & restart survival (12 checks):** #69 (offline edit preserves `allium:mail_receipts`/`allium:kit_receipts`), #71–#72 (offline RO non-mutation), #74–#75 (offline `/invsee` edit + PDC preservation), #77 (offline `/ec` edit), #78 (SHA-256 CAS conflict aborts without clobbering disk), #79 (target login observes offline edits & PDC), #80–#82 (SQL audit actions, `<= 512` sanitized detail, shutdown persistence), #85 (full server restart survival).

**S24 follow-up closures (`+12` checks per lane -> `98/98`):**
- Startup reflective codec self-test active (`offlineEditingEnabled == True`); unit test covers self-test failure disabling offline editing and logging loudly at `SEVERE`.
- Pending workflow hold gating & extended receipt/marker preservation: real SQLite `allium_mail` `PENDING` hold refuses offline `.edit` while allowing Read-Only and leaving `<uuid>.dat` untouched; clearing hold permits offline `.edit` while preserving `S/...;C/...;FUTURE/...`, `K/...;FUTURE/...`, and unknown third-party `unknownmod:custom_marker` verbatim (plus unit tests covering `HELD` economy reservations, `CLAIMING` mail, and `allium_kit_deliveries`).
- Older `DataVersion` (`current - 1`) refusal on offline `.edit`: Read-Only opens cleanly, `.edit` refuses and leaves `<uuid>.dat` untouched (plus unit test proving codec DataVersion rewrite refusal before rename).
- Target login racing an active offline edit click: `norm.connect()` sent immediately after offline `/invsee` `.edit` click waits at `AsyncPlayerPreLoginEvent` for async flush completion and observes `1xLAPIS_BLOCK` on join (same-tick pre-flush `reconcileUnflushedInvseeDrops` remains `unit-only` in `InspectionServiceTest`).

## Live Scopes Verified (Both Lanes)

1. **`permissions-hierarchy-vanish`**: Console rejection (`PLAYER_ONLY`); root command gating; `/ec` self-open without `.others`; `allium.moderation.exempt` and explicit `allium.moderation.bypass` override; discipline staff-rank hierarchy enforcement (`allium.mute` vs `allium.ban`); tab-completion and execution vanish-blindness parity across no-`.offline`, `.offline`-only, and `.offline`+`bypass` (identical offline-unavailable response, never leaks vanished status or opens live/offline data); `allium.vanish.see` restoration.
2. **`online-layout-confinement-edits-guards`**: Exact 45-slot `/invsee` mapping (`0..26` -> `9..35` main storage, `27..35` -> `0..8` hotbar, `36..39` -> Helmet/Chestplate/Leggings/Boots, `40` -> offhand, `41..44` -> locked gray panes) and 27-slot `/enderchest` layout; Read-Only wire confinement (top clicks, shift-clicks, bottom shift-ins, locked panes); concurrent read-only viewers + live 150 ms inventory refresh; single-editor mutex with concurrent read-only viewers allowed; locked panes preserved under `.edit` clicks/drags; hotbar, armor, and offhand `.edit` writes; 1-tick target guard cancelling `PlayerDropItemEvent` & `PlayerSwapHandItemsEvent`; mid-session `.edit` revocation abort; `MailInventoryLock` busy exclusion for mutable edits (read-only allowed); target death abort with clean non-duplicated drops; target quit flush (`LOWEST`) writing final edits and preserving `BukkitValues` PDC keys.
3. **`offline-nbt-cas-loginfence`**: Startup reflective codec self-test (`CraftBukkitPlayerDataCodec#selfTest()`); requires BOTH `.offline` and `allium.moderation.bypass`; pending workflow hold (`HELD`/`PENDING`/`CLAIMING`/kit delivery) blocks offline `.edit` while permitting Read-Only; offline Read-Only byte-for-byte non-mutation; offline `/invsee` and `/enderchest` edits via `CraftBukkitPlayerDataCodec` (`LegacyBackend` on Spigot 1.20.1 and `CodecBackend` `TagValueInput`/`TagValueOutput`/`EntityEquipment` on Paper 26.3) preserving unrelated slots, `allium:mail_receipts` (`S`/`C`/`FUTURE`), `allium:kit_receipts` (`K`/`FUTURE`), `allium:s24_proof`, `inspgate:marker`, and `unknownmod:custom_marker` in `BukkitValues`; corrupt `.dat`, newer `DataVersion` (`9999999`), and mismatched/older `DataVersion` `.edit` refusals; SHA-256 CAS abort on external `<uuid>.dat` modification without clobbering disk state; `AsyncPlayerPreLoginEvent` login fence aborting offline inspection and waiting for any in-flight flush (both idle and active-click race) before login completes; login state verification.
4. **`restart-persistence`**: SQLite `allium_moderation_audit` row persistence across shutdown and reboot, bounded (`<= 512` chars) sanitized detail tokens, and final offline offhand edit (`2xGOLD_BLOCK`) + PDC marker survival across full server restart with clean logs.

## Bugs Caught & Fixed During Live Gating

- **`CraftBukkitPlayerDataCodec` NPE on offline `.dat` writes (`CraftInventoryPlayer` vs `CraftInventory`)**: Both `CodecBackend` and `LegacyBackend` initially wrapped NMS `PlayerInventory`/`Inventory` in `CraftInventoryPlayer`, whose `setItem` calls `getHolder()` (`this.player.getBukkitEntity()`), throwing `NullPointerException` when `player == null`. Switching both backends to plain `CraftInventory` writes slots `0..40` directly without touching `getHolder()`.
- **Offline GUI title truncation clipping `(Read-Only)` / `(Edit)` at 32 characters**: Naive `substring(0, 32)` clipped mode and offline indicators for offline `/invsee` titles (`§8Inventory: InspNorm [Offline] (Read-Only)`). Added `InspectionLayout.formatGuiTitle(...)` with progressive compaction (`Inv`/`EC`, `[Off]`, `(RO)`/`(Edit)`) so offline and mode indicators never clip even for 16-character player names.

## Limits

- Live gates exercised **SQLite only** with `economy.vault-provider-enabled: false` (MariaDB `ModerationRepository` SQLite/MariaDB schema is shared with S17/S19 unit/live suites; no separate MariaDB live run for S24).
- Offline `.dat` edits require both `allium.<command>.offline` and `allium.moderation.bypass` (and `.edit` to mutate); offline permission lookup plugins remain out of scope (consistent with S19).
- Sequential offline GUI clicks landing in separate ticks while an async `.dat` write is in flight receive `INSPECT_BUSY` until the prior write completes (CAS serialization guard).
- Spigot 1.20.1 logs the expected startup CraftBukkit vanish advisory (`Vanish is enabled on CraftBukkit...`).
- Non-vanilla Folia / custom NMS forks beyond Spigot 1.20.x and Paper 26.3+ are untested.
