# S17 vanish and mute live results

**PASS**.

- **Spigot 1.20.1 / Java 17 (SQLite WAL):** 3/3 cases and **75/75** checks passed; 0 failures. Manifest: `/mnt/storage/.allium-validation/20261114/s17-moderation/runs/spigot-r5/manifest.json`.
- **Paper 26.3-141 / Java 25 (SQLite WAL):** 3/3 cases and **75/75** checks passed; 0 failures. Manifest: `/mnt/storage/.allium-validation/20261114/s17-moderation/runs/paper-r2/manifest.json`.

Frozen `build/libs/Allium.jar` SHA-256: `e69179243f7eb56780f8972382ff031135f6eba612e4a08681d6dfe60d23ecc6`.

Both suites ran against identical frozen production, helper, and client source files on owned loopback ports `25770`–`25771` with three real-protocol bots (`ModStaff`, `ModNorm`, `ModObs`) plus server console, and all server/client processes were shut down cleanly after execution.

## Verified Cases (6 cases / 150 live checks total)

1. **`vanish visibility, tab completion, and looks-offline contract` (Spigot 1.20.1 & Paper 26.3)**:
   - Before `/vanish`, `ModStaff` is visible to both `ModNorm` and `ModObs` (`canSee=true`), and a real TCP Server List Ping (SLP) reports `online=3`.
   - `ModNorm` sends `/tpa ModStaff` before vanish; when `ModStaff` runs `/vanish`, `ModStaff` has `vanished` metadata, `setSleepingIgnored(true)`, `ModNorm.canSee(ModStaff)=false`, and `ModObs.canSee(ModStaff)=true` (`allium.vanish.see`).
   - Pending `/tpa` from `ModNorm` to `ModStaff` is silently cleared without broadcasting a cancel notice to `ModNorm`; `ModNorm` running `/tpacancel` receives `You have no pending teleport requests.`.
   - Real TCP SLP after `/vanish` excludes `ModStaff` from both the player count (`3 -> 2`) and the player sample (`{ModNorm, ModObs}`).
   - Tab completion for `/msg Mod`, `/tpa Mod`, `/mail send Mod`, `/heal Mod`, and `/kit starter Mod` hides `ModStaff` from `ModNorm` (`['ModNorm', 'ModObs']`) while keeping `ModStaff` visible to `ModObs` (`['ModNorm', 'ModObs', 'ModStaff']`).
   - `ModNorm` receives offline-identical `Player ModStaff not found.` for `/msg ModStaff`, `/ignore ModStaff`, `/tpa ModStaff`, `/tpahere ModStaff`, `/tpaccept ModStaff`, `/tpdeny ModStaff`, `/heal ModStaff`, and `/kit starter ModStaff`.
   - `ModObs` (holding `allium.vanish.see`) messages vanished `ModStaff`, while `ModNorm` (holding `allium.socialspy` without `allium.vanish.see`) receives zero SocialSpy copies.
   - After vanished `ModStaff` sends `/msg ModNorm`, `ModNorm` running `/reply` receives `You have nobody to reply to.` (matching an offline partner) while `ModObs`'s `/reply` partner (`ModStaff`) remains intact and delivers.
2. **`vanish world effects, relog, and server restart persistence` (Spigot 1.20.1 & Paper 26.3)**:
   - While vanished, `ModStaff` cancels `EntityTargetEvent` (zombie targeting cleared), `EntityPickupItemEvent`, `PlayerInteractEvent` (`Action.PHYSICAL` stone pressure plate), and chest opening (`PlayerInteractEvent` `RIGHT_CLICK_BLOCK` cancelled with `SilentContainerHolder` mirror showing the 3 chest diamonds).
   - Disconnecting and reconnecting while vanished suppresses both quit and join messages and keeps `ModStaff` vanished and hidden from `ModNorm` without ever revealing them on join.
   - A full server shutdown and reboot preserves `ModStaff`'s vanish state in SQLite (`allium_vanish`), suppresses join broadcast, and keeps `ModNorm.canSee(ModStaff)=false` on rejoin.
3. **`mute chat/msg/reply/mail enforcement, timed expiry, unmute, and audit log` (Spigot 1.20.1 & Paper 26.3)**:
   - `/mute ModNorm 8s Spamming global chat` notifies both `ModStaff` and `ModNorm`, appears in `/mutes` and `/mute list`, and blocks `ModNorm`'s public chat, `/msg`, `/reply`, `/mail send`, and `/mail gift` with `You are muted (<remaining> remaining). Reason: Spamming global chat`.
   - Waiting past the 8s duration clears the mute automatically without restart; `/mutes` becomes empty and `ModNorm` can chat, `/msg`, and `/mail send` again.
   - Console `/mute ModNorm Permanent console mute`, staff `/unmute ModNorm Appealed`, and console `/vanish off ModStaff` all apply and restore `ModNorm` visibility; `allium_moderation_audit` and console logs record `VANISH_ON`, `VANISH_OFF`, `MUTE`, `MUTE_EXPIRED`, and `UNMUTE`.

## Limits

- **Database backends:** live checks ran on SQLite WAL only; MySQL SQL syntax (`ON DUPLICATE KEY UPDATE`, `AUTO_INCREMENT`) was not exercised live in S17 (unit-tested against H2/SQLite dialect queries and schema migrations).
- **World-effect coverage split:** `EntityTargetEvent`, `EntityPickupItemEvent`, `Action.PHYSICAL` pressure plate, and `CHEST` silent container mirror ran live on both server lanes; `PlayerPickupArrowEvent`, `BlockReceiveGameEvent` (sculk sensor), `ENDER_CHEST`, and read-only click/drag cancellation inside `SilentContainerHolder` are verified unit-only (`ModerationServiceAndCommandsTest`).
- **Paper SLP anonymous entries:** when a client connects with `allowsServerListing=false` (e.g. privacy-opt-out clients), Paper 26.3's `PaperServerListPingEvent` represents them in its pre-built sample as `"Anonymous Player"` with `UUID(0,0)` so `iterator().remove()` updates `players.online` accurately (`3 -> 2`) while anonymous sample slots remain indistinguishable; normal clients (`allowsListing=true`) are removed from both count and sample.
- **Lanes:** S17 live gates ran on Spigot 1.20.1 (Java 17) and Paper 26.3 (Java 25); intermediate 1.20.6 / 1.21.8 server jars were not run for S17.

[Combined manifest](evidence/s17-moderation-manifest.json).
