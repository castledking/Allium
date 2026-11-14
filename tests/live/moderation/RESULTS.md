# S17 vanish and mute live results

**PASS** (initial S17 gate + S17 follow-up suites including live MariaDB 10.11).

## Latest S17 follow-up suites (frozen jar `d35785e22029c03dfae6e05540f9b24479f9148fc25772b2b0cf18210ddff483`)

- **Spigot 1.20.1 / Java 17 (SQLite WAL):** 4/4 scopes and **176/176** checks passed (0 failures, 78s). Manifest: `/mnt/storage/.allium-validation/20261114/s17-moderation/runs/spigot-fu-r5/manifest.json`.
- **Paper 26.3-141 / Java 25 (SQLite WAL):** 4/4 scopes and **176/176** checks passed (0 failures, 71s). Manifest: `/mnt/storage/.allium-validation/20261114/s17-moderation/runs/paper-fu-r5/manifest.json`.
- **Spigot 1.20.1 / Java 17 (MariaDB 10.11 container `allium-gate-mariadb`):** 1/1 scope and **71/71** checks passed (0 failures, 36s). Manifest: `/mnt/storage/.allium-validation/20261114/s17-moderation/runs/mariadb-fu-r5/manifest.json`.

**Initial S17 runs (frozen jar `e6917924...`, superseded by follow-ups above):**
- `spigot-r5`: 3/3 cases, 75/75 checks passed (`/mnt/storage/.allium-validation/20261114/s17-moderation/runs/spigot-r5/manifest.json`).
- `paper-r2`: 3/3 cases, 75/75 checks passed (`/mnt/storage/.allium-validation/20261114/s17-moderation/runs/paper-r2/manifest.json`).

All live runs executed on owned loopback ports with three real-protocol bots (`ModStaff`, `ModNorm`,
`ModObs`) plus server console, and shut down cleanly after execution.

## Verified Cases (423 follow-up checks total: 176 + 176 + 71)

1. **`vanish visibility, tab completion, and looks-offline contract` (Spigot 1.20.1 & Paper 26.3)**:
   - `/vanish` sets `vanished` metadata, `setSleepingIgnored(true)`, `ModNorm.canSee(ModStaff)=false`,
     `ModObs.canSee(ModStaff)=true` (`allium.vanish.see`), silently cancels `ModNorm`'s pending `/tpa`
     without leaking a cancel notice, and drops `ModStaff` from TCP Server List Ping (`3 -> 2`).
   - Allium tab completion (`/msg`, `/tpa`, `/mail send`, `/heal`, `/kit starter`) hides `ModStaff`
     from `ModNorm` while keeping `ModStaff` visible to `ModObs`.
   - `ModNorm` gets offline-identical errors across `/msg`, `/ignore`, `/tpa`, `/tpahere`, `/tpaccept`,
     `/tpdeny`, `/heal`, `/kit`, and `/reply`, and receives no `SocialSpy` leak when `ModObs`
     messages `ModStaff`.
2. **`vanish leaks outside Allium` (Spigot 1.20.1 & Paper 26.3 — observed live behavior per lane)**:
   - **Vanilla `/list` & raw plugin count:** `/minecraft:list` hides `ModStaff` from `ModNorm`
     (`There are 2 of a max of 20 players online: ModNorm, ModObs`) while showing `3` to `ModObs`.
     `Bukkit.getOnlinePlayers().size()` remains `3` on both servers (recorded in manifest
     `observations.counts`).
   - **Vanilla `/minecraft:msg`, `/minecraft:tp`, `@a`/`@p`, and vanilla tab:** literal names and
     `@a`/`@p` targeting vanished `ModStaff` are blocked with vanilla `"§cNo player was found"` on
     both servers. Paper 26.3 hides `ModStaff` in vanilla `/minecraft:msg Mod` & `/minecraft:tp Mod`
     tab suggestions (`['ModNorm', 'ModObs']`), whereas Spigot 1.20.1 still completes
     `['ModNorm', 'ModObs', 'ModStaff']` (documented Spigot Brigadier limitation).
   - **Death & advancement messages:** death broadcasts for vanished players are suppressed on both
     Spigot 1.20.1 and Paper 26.3. Paper 26.3 suppresses advancement announcements (`normMsgs=[]`,
     `obsMsgs=[]`); Spigot 1.20.1 still broadcasts `chat.type.advancement.task` because Spigot 1.20 API
     lacks `PlayerAdvancementDoneEvent#message(null)`.
   - **XP orbs:** `EntityTargetEvent` (`reason=CLOSEST_PLAYER`) cancels XP orb attraction toward
     vanished `ModStaff` on both lanes (`expNow=0`, `orbAlive=true` after homing tick checks).
     Paper 26.3 also cancels collision pickup via `PlayerPickupExperienceEvent`; Spigot 1.20.1 lacks a
     cancellable pickup event if an orb's spawn velocity physically collides with the player.
   - **Public chat while vanished:** blocked by default (`moderation.vanish.allow-public-chat: false`),
     verified allowed once toggled `true` + `/allium reload` (no restart needed).
   - **Containers live opt-in & visibility reload:** default silent `SilentContainerHolder` mirror vs
     explicit `allium.vanish.container.live` (verified live R/W chest access on both servers); mid-session
     `allium.vanish.see` permission change + `/allium reload` recomputes `canSee` pairs without rejoin.
3. **`vanish world effects, relog, and server restart persistence` (Spigot 1.20.1 & Paper 26.3)**:
   - Cancels zombie `EntityTargetEvent`, `EntityPickupItemEvent`, physical `Action.PHYSICAL` pressure
     plate, and silent chest mirror; relog and full server restart preserve vanish state in SQLite
     without broadcasting join/quit or revealing `ModStaff`.
4. **`mute HIGHEST, muted-commands, duration reject, fail-closed retry, expiry, unmute, audit` (Spigot 1.20.1 & Paper 26.3)**:
   - Synthetic external chat plugin un-cancelling at `HIGH` priority is overridden by Allium's `HIGHEST`
     chat listener so muted players still cannot speak.
   - Configured `muted-commands` (`/me`, `/say`, `/tell`, `/minecraft:msg`, `/teammsg`, `/msg`,
     `/reply`, `/r`, `/mail send`, `/mail gift`) are blocked while muted.
   - `/mute ModNorm 10x spam` rejects `10x` with usage instead of treating `10x` as part of a permanent
     mute reason; `/mute ModNorm 8s ...` expires automatically without restart.
   - Fault-injected preload failure transitions to `FAILED` fail-closed (`moderation.mute-unavailable` on
     chat, `/msg`, `/me`), then background `load-retry` recovers to `READY` without relog.
5. **`spigot-1.20.1-mariadb` live lane (`mariadb-fu-r5`, 71/71)**:
   - Verified MariaDB 10.11 (`storage.type: mysql`) migration, vanish persistence, mute/expiry/unmute,
     audit rows (`allium_moderation_audit`), and full server restart persistence end-to-end.

## Gradle unit & integration suite (SQLite + MariaDB 10.11)

- `./gradlew check test shadowJar --rerun-tasks` with `ALLIUM_TEST_MYSQL_URL` pointing at the real
  MariaDB 10.11 container (`allium-gate-mariadb`): **0 failures, 0 skipped** (`build`: 344,
  `allium-core`: 189, `allium-api`: 4 = **537 JUnit XML tests**; `ModerationRepositoryAndDurationTest`
  exercises every SQL query and migration on both SQLite and MariaDB).

## Limits

- **Server lanes tested live:** Spigot 1.20.1 (Java 17) and Paper 26.3 (Java 25); MariaDB live ran on
  Spigot 1.20.1 (`mariadb-fu-r5`) alongside SQLite on both Spigot and Paper (`spigot-fu-r5`,
  `paper-fu-r5`). Intermediate 1.20.6 / 1.21.8 jars were not re-run for S17.
- **Spigot 1.20.1 platform API limits (verified live, not fixable cleanly in pure Bukkit):**
  - Vanilla Brigadier `minecraft:*` command tab completion suggests vanished players on Spigot 1.20.1.
  - `PlayerAdvancementDoneEvent` on Spigot 1.20.1 cannot suppress per-event broadcast text.
  - Spigot 1.20.1 has no cancellable XP orb collision pickup event if an already-spawned orb overlaps
    a vanished player's hitbox (homing via `EntityTargetEvent` is cancelled).
- **World-effect split:** `EntityTargetEvent`, `EntityPickupItemEvent`, `Action.PHYSICAL` pressure
  plate, `ExperienceOrb` target/pickup, `SilentContainerHolder`, and `allium.vanish.container.live`
  ran live on both lanes; `PlayerPickupArrowEvent`, `BlockReceiveGameEvent` (sculk), and `ENDER_CHEST`
  remain unit-only (`ModerationServiceAndCommandsTest`).
- **Paper SLP anonymous entries:** clients connecting with `allowsServerListing=false` appear in
  Paper's SLP sample as `"Anonymous Player"` (`UUID(0,0)`); `players.online` count (`3 -> 2`) is
  accurate, while anonymous sample slots carry no UUID to distinguish.

[Combined manifest](evidence/s17-moderation-manifest.json).
