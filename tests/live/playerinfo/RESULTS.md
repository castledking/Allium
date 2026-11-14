# S18 player info utilities & /i, /item live results

**PASS** (identical frozen `build/libs/Allium.jar` SHA-256 `5098ba4817791535ba17e55f007846053c0f35b352959b567779f18a5f129f99` and identical frozen harness `sourceHashes` across both servers).

- **Spigot 1.20.1 / Java 17 (SQLite WAL):** **33/33** checks passed (0 failures, 59.06s). Manifest: `/mnt/storage/.allium-validation/20261114/s18-playerinfo/runs/spigot-03/manifest.json` (`9722fc14...`).
- **Paper 26.3-141 / Java 25 (SQLite WAL):** **33/33** checks passed (0 failures, 52.84s). Manifest: `/mnt/storage/.allium-validation/20261114/s18-playerinfo/runs/paper-03/manifest.json` (`b91afd31...`).

All live runs executed on owned loopback ports `25780`–`25782` with three real-protocol bots (`InfoStaff` vanished staff, `InfoNorm` normal player, `InfoObs` staff observer) plus server console, and shut down cleanly after execution.

## Verified Cases (66 live checks total: 33 Spigot + 33 Paper)

1. **`/list` permission grouping and vanish visibility** (`InfoStaff`, `InfoNorm`, `InfoObs`, console):
   - Pre-vanish `/list` renders `3/20` grouped into `Staff (1): InfoStaff`, `Vip (1): InfoObs`, and `Players (1): InfoNorm` via `allium.list.group.<group>`.
   - Once `InfoStaff` vanishes, `InfoNorm` sees `2/20` with `InfoStaff` and `Staff` omitted cleanly, whereas `InfoObs`, `InfoStaff`, and console see `3/20` and `InfoStaff [Vanished]`.
2. **`/seen`, `/whois`, and vanish "looks offline" equivalence** (`InfoStaff`, `InfoNorm`, `InfoObs`, console):
   - Even with `allium.whois.location` temporarily granted to `InfoNorm`, `/seen InfoStaff` reports `InfoStaff is offline (last seen ... ago, <UTC>). First joined: <UTC>` (never `"online"`), and `/whois InfoStaff` renders the exact offline header/dates schema with no `Location:` and no `[Vanished]` marker while still returning seeded name history (`InfoStaff, OldStaffName`).
   - `InfoObs` (`allium.vanish.see` + `allium.whois.location`) and console see `InfoStaff is online [Vanished]` in `/seen` and `Location: world (100, -61, 100)` + `Known names: InfoStaff, OldStaffName` in `/whois`.
   - `/seen NeverJoined999` returns `No player called NeverJoined999 has played here.`.
3. **`/near` distance sort, vanish filtering, max radius, and `/seen`/`/whois` completion**:
   - At deterministic coordinates (`InfoStaff` 30m from `InfoNorm`, `InfoNorm` 50m from `InfoObs`, `InfoStaff` 80m from `InfoObs`), `InfoNorm` sees `InfoObs (50m)` only; `InfoObs` sees `InfoNorm (50m), InfoStaff (80m) [Vanished]`; `InfoStaff` sees `InfoNorm (30m), InfoObs (80m)`.
   - `/near 600` is rejected (`Radius cannot exceed 500 blocks.`) and console `/near` rejects non-players (`Only players can use /near.`).
   - Tab completion for `/seen Info` and `/whois Info` omits vanished `InfoStaff` for `InfoNorm` while including `InfoStaff` for `InfoObs`.
4. **`/afk`, vanish broadcast suppression, real-bed night skip, movement un-AFK, auto-AFK, and kick-on-afk**:
   - `/afk Tea break` by vanished `InfoStaff` suppresses public AFK broadcast for `InfoNorm` while delivering it to `InfoObs` and console; un-AFKing `InfoStaff` preserves `sleepingIgnored=true` while vanished.
   - `InfoObs` entering AFK shows `[AFK]` in `/near` and `/list`, sets `obsSleepingIgnored=true`, and when `InfoNorm` sleeps in a real `RED_BED` at `time=14000`, vanilla night-skip advances to morning (`TimeSkipEvent` `NIGHT_SKIP`, `worldTime < 2000`) because both vanished staff and AFK observer are sleeping-ignored.
   - Real protocol movement (`1.6` blocks) un-AFKs `InfoObs` and clears `obsSleepingIgnored=false`.
   - With `info.afk.auto-afk-seconds: 3` and `info.afk.kick-on-afk: false` (default), AFK players remain connected; with `kick-on-afk: true`, non-exempt `InfoNorm` is kicked (`You were kicked for being AFK.`) while `allium.afk.kick.exempt` `InfoObs` stays connected.
5. **`/i` and `/item` self-use `/give` harness**:
   - Without `allium.give`, `InfoNorm` is blocked and receives zero items (`plugin.yml` `permission: allium.give` also hides `/i` from the Brigadier tree); console `/i diamond` prints `/give <player> ...` usage.
   - After `allium.give` grant, `/i diamond 3 name:"&aGem Blade"` delivers a custom-named 3x diamond stack; `/i allium:handcuffs 1` is denied without `allium.give.privileged` (and succeeds for `InfoStaff` holding `allium.give.privileged`); `/item emerald 1 --overflow=refuse` refuses cleanly when `InfoNorm`'s inventory is full.
6. **Restart persistence & offline vs vanished-online equivalence**:
   - Disconnecting vanished `InfoStaff`, restarting the full server, and reconnecting `InfoNorm` + `InfoObs` preserves `first_join`, pre-vanish `last_seen`, and `OldStaffName` history in SQL.
   - `InfoNorm`'s offline `/seen` and `/whois` timestamps/lines match the prior vanished-online responses (`extract_date(vanished) == extract_date(offline)` and identical header/UUID/history lines).
   - Reconnecting `InfoStaff` after restart preserves `vanish=true`, preserves pre-vanish `last_seen`, and keeps `InfoStaff` appearing offline in `/seen`.

## Gradle unit & integration suite (SQLite + MariaDB 10.11)

- `./gradlew check test shadowJar --rerun-tasks` with `ALLIUM_TEST_MYSQL_URL` pointed at MariaDB 10.11 (`allium-gate-mariadb`): **0 failures, 0 skipped** (`root`: 349, `allium-core`: 192, `allium-api`: 4 = **545 JUnit XML tests**).
- `PlayerInfoRepositoryTest` (2 tests) and `PlayerNameRepositoryTest` (3 tests) execute schema migrations, vanish-safe `last_join`/`last_seen` preservation, and name-history backfills on both **SQLite** and **MariaDB 10.11**.
- `PlayerInfoServiceAndCommandsTest` (5 tests) covers `/list`, `/seen`, `/whois`, `/near`, `/afk`, `/i`/`/item`, auto-AFK/kick-on-afk, vanish-coordinated sleep, and main-thread zero-sync-SQL verification.

## Limits

- **Live server DB backend:** both live server gates (`spigot-03`, `paper-03`) ran against SQLite (`WAL`); **MariaDB 10.11** for S18 storage (`allium_player_info`, `allium_player_name_history`) is verified via JUnit integration tests (`PlayerInfoRepositoryTest`, `PlayerNameRepositoryTest`), not a separate S18 live server launch.
- **Vault `Permission` primary-group fallback:** live `/list` grouping exercised `allium.list.group.<group>`; the optional runtime Vault `Permission` primary-group lookup (`VaultGroupResolver`) is inspected/`unit-only` (Vault fallback returns empty cleanly when no Vault plugin is installed).
- **World / test environment detail:** live `InfoGate` fixture uses `difficulty=peaceful` so sleeping night-time checks do not accumulate stray hostile entities across restarts, and `onTimeSkip` reads `TimeSkipEvent#getSkipReason()` reflectively because Paper 26.3 moved `SkipReason` onto `ClockTimeSkipEvent`.

[Combined manifest](evidence/s18-playerinfo-manifest.json).
