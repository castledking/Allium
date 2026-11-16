# S24 Inspection Live Gate Results

**PASS: 172/172 live checks** across **Spigot 1.20.1** (Java 17) and **Paper 26.3** (Java 25) on identical frozen `build/libs/Allium.jar` (`SHA-256: ec97af2ced20491733e0f33e2d4c3c1b67f47f2e90a09493d27ce59e56794f5f`), SQLite storage backend with `economy.vault-provider-enabled: false`.

- **Spigot 1.20.1 / Java 17 / SQLite** (`spigot-r10`): **86/86 checks passed, 0 failures**
  - Run manifest: `/mnt/storage/.allium-validation/20261115/s24-inspection/runs/spigot-r10/manifest.json`
  - Scopes: `spigot:permissions-hierarchy-vanish`, `spigot:online-layout-confinement-edits-guards`, `spigot:offline-nbt-cas-loginfence`, `spigot:restart-persistence`
  - SQLite `allium_moderation_audit` rows persisted: `37` (`INVSEE_OPEN`, `INVSEE_EDIT`, `INVSEE_ABORT`, `ENDERCHEST_OPEN`, `ENDERCHEST_EDIT`, plus vanish events)
- **Paper 26.3 / Java 25 / SQLite** (`paper-r1`): **86/86 checks passed, 0 failures**
  - Run manifest: `/mnt/storage/.allium-validation/20261115/s24-inspection/runs/paper-r1/manifest.json`
  - Scopes: `paper:permissions-hierarchy-vanish`, `paper:online-layout-confinement-edits-guards`, `paper:offline-nbt-cas-loginfence`, `paper:restart-persistence`
  - SQLite `allium_moderation_audit` rows persisted: `36` (`INVSEE_OPEN`, `INVSEE_EDIT`, `INVSEE_ABORT`, `ENDERCHEST_OPEN`, `ENDERCHEST_EDIT`, plus vanish events)

Full `./gradlew check shadowJar --offline` result: **796 unit tests** (`allium-api`: 4, `allium-core`: 278, root: 514; **+15** from S23 baseline 781: `InspectionLayoutTest` 5, `OfflinePlayerDataStoreTest` 4, `InspectionServiceTest` 6), **0 failures, 0 errors, 0 skipped**.

## Live Scopes Verified (Both Lanes)

1. **`permissions-hierarchy-vanish`**: Console rejection (`PLAYER_ONLY`); root command gating; `/ec` self-open without `.others`; `allium.moderation.exempt` and explicit `allium.moderation.bypass` override; discipline staff-rank hierarchy enforcement (`allium.mute` vs `allium.ban`); tab-completion and execution vanish-blindness parity across no-`.offline`, `.offline`-only, and `.offline`+`bypass` (identical offline-unavailable response, never leaks vanished status or opens live/offline data); `allium.vanish.see` restoration.
2. **`online-layout-confinement-edits-guards`**: Exact 45-slot `/invsee` mapping (`0..26` -> `9..35` main storage, `27..35` -> `0..8` hotbar, `36..39` -> Helmet/Chestplate/Leggings/Boots, `40` -> offhand, `41..44` -> locked gray panes) and 27-slot `/enderchest` layout; Read-Only wire confinement (top clicks, shift-clicks, bottom shift-ins, locked panes); concurrent read-only viewers + live 150 ms inventory refresh; single-editor mutex with concurrent read-only viewers allowed; locked panes preserved under `.edit` clicks/drags; hotbar, armor, and offhand `.edit` writes; 1-tick target guard cancelling `PlayerDropItemEvent` & `PlayerSwapHandItemsEvent`; mid-session `.edit` revocation abort; `MailInventoryLock` busy exclusion for mutable edits (read-only allowed); target death abort with clean non-duplicated drops; target quit flush (`LOWEST`) writing final edits and preserving `BukkitValues` PDC keys.
3. **`offline-nbt-cas-loginfence`**: Requires BOTH `.offline` and `allium.moderation.bypass`; offline Read-Only byte-for-byte non-mutation; offline `/invsee` and `/enderchest` edits via `CraftBukkitPlayerDataCodec` (`LegacyBackend` on Spigot 1.20.1 and `CodecBackend` `TagValueInput`/`TagValueOutput`/`EntityEquipment` on Paper 26.3) preserving unrelated slots, `allium:mail_receipts`, `allium:kit_receipts`, `allium:s24_proof`, and `inspgate:marker` in `BukkitValues`; corrupt `.dat` and newer `DataVersion` (`9999999`) refusals; SHA-256 CAS abort on external `<uuid>.dat` modification without clobbering disk state; `AsyncPlayerPreLoginEvent` login fence aborting offline inspection and waiting for any in-flight flush before login completes; login state verification.
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
