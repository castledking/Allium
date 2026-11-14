# Vanish and Mute (S17)

S17 replaces the stub `VanishSource` and `MuteSource` hooks across messaging, social spy, mail,
teleport requests, warps, and player-targeting commands with persistent, bounded-storage
implementations in `ModerationService` and `ModerationRepository`.

> **Platform recommendation:** **Paper (26.3 / modern Paper) is recommended for full vanish.**
> Spigot lacks APIs to suppress vanilla `minecraft:*` Brigadier tab suggestions, advancement
> announcements (`PlayerAdvancementDoneEvent#message`), and direct physical XP orb pickup on
> collision (`PlayerPickupExperienceEvent`). When vanish is enabled on a non-Paper server,
> Allium logs a startup warning listing those three known leaks.

## Commands and permissions

### Vanish
- `/vanish [on|off] [player]` (alias `/v`):
  - Without arguments (or `/vanish on` / `/vanish off`), toggles or sets the sender's vanish state.
  - With a target player (`/vanish <player>` or `/vanish <on|off> <player>`), toggles or sets vanish
    for that online player.
- Permissions:
  - `allium.vanish` (default `op`): use `/vanish` on yourself.
  - `allium.vanish.others` (default `op`): use `/vanish` on another online player.
  - `allium.vanish.see` (default `op`): see vanished players in the world, tab list, tab completion,
    private messages, teleport requests, and moderation lists.
  - `allium.vanish.container.live` (default `false`, explicit-only — **not** included under
    `allium.*`): allows a vanished player to open lidded containers live (read-write) instead of
    receiving the silent read-only `SilentContainerHolder` mirror. **Noise trade-off:** live opens
    delegate to vanilla container interaction, which plays the container open/close sound, triggers
    the lid animation, and updates trapped-chest / sculk signals visible/audible to nearby players.

### Mute
- `/mute <player> [duration] [reason]`:
  - Mutes `<player>` permanently (when `[duration]` is omitted) or for a timed duration (`5s`,
    `10m`, `2h`, `1d`, `1w`, or compound tokens like `1h30m`).
  - **Digit-leading invalid duration token check:** if the first token after `<player>` begins with
    a digit (for example `10x`, `0s`, `5foo`, or `99`), it is parsed strictly as a duration and
    **rejected with command usage** if invalid, rather than silently becoming the start of a
    permanent mute reason.
  - `/mute list` (when no online/offline target ambiguity exists in that subcommand form) delegates
    to `/mutes`.
- `/unmute <player> [reason]`:
  - Removes an active mute from `<player>` and notifies the player if online.
- `/mutes` (alias `/mutelist`):
  - Lists all currently active mutes visible to the sender, showing the muted player's name,
    remaining time (`permanent` or formatted duration), staff actor, and reason.
- Permissions:
  - `allium.mute` (default `op`): use `/mute` and `/mutes`.
  - `allium.unmute` (default `op`): use `/unmute`.

## Vanish effects and the "looks offline" contract

When a player is vanished (`ModerationService#isVanished`):
1. **Entity & tab list hiding (`Player#hidePlayer`):**
   - Visibility pairs (`viewer.hidePlayer(plugin, vanishedPlayer)` / `viewer.showPlayer(...)`) are
     recomputed at:
     1. plugin enable / `/vanish` state changes,
     2. any player join (`PlayerJoinEvent` at `LOWEST`), and
     3. `/allium reload` (`ModerationService#reloadSettings`, calling `syncAllOnlineVisibility()`).
   - **Mid-session permission-change limitation:** if a permission plugin grants or revokes
     `allium.vanish.see` for an already-connected viewer without triggering reconnect, Allium-owned
     command/tab checks (`PlayerNameVisibility#canSeeTarget`) update immediately on the new
     permission check, whereas Bukkit's underlying `hidePlayer`/`showPlayer` pair visibility is
     re-synchronized on the viewer's next join, the target's next `/vanish`, or `/allium reload`.
   - Staff with `allium.vanish.see` retain `viewer.showPlayer(plugin, vanishedPlayer)` so they can
     see the vanished player in the world and tab list.
   - The `"vanished"` metadata key (`FixedMetadataValue(plugin, true)`) is maintained on the player
     for third-party compatibility and `VanishSource.METADATA`.
2. **Server list ping & vanilla `/list` vs raw plugin online count:**
   - **Server list ping (`ServerListPingEvent`):** vanished players are removed from
     `ServerListPingEvent#iterator()`, reducing `players.online` and hiding them from the sample
     (see Limits regarding Paper privacy-opt-out `"Anonymous Player"` entries).
   - **Vanilla `/list` (`/minecraft:list`):** `ModerationService` rewrites non-`allium.vanish.see`
     viewers' vanilla `/list` output via `commands.list.players` formatting (and player-name filtering)
     so vanished players are omitted from both count and listed names, while `allium.vanish.see`
     viewers see the full list. (S18 replaces `/list` with an Allium-owned vanish-aware `/list`.)
   - **Raw `Bukkit.getOnlinePlayers().size()`:** Bukkit's raw online player collection always
     includes every connected `Player` entity (`size = 3` in the 3-bot live harness) because hiding
     entities per-viewer via `hidePlayer` does not remove players from `CraftServer.players`.
     External plugins querying raw counts must filter by `viewer.canSee(player)` or check
     `player.hasMetadata("vanished")` / `VanishSource`.
3. **Vanish leaks outside Allium commands:**
   - **Vanilla `/minecraft:msg`, `/minecraft:tell`, `/minecraft:w`, `/minecraft:tp` and selectors
     (`@a`, `@p`, `@r`, etc.):** `ModerationService#onCommandPreprocess` intercepts vanilla aliases
     when run by a player without `allium.vanish.see`. If any argument names a vanished player
     (with or without `minecraft:` prefix) or uses a selector (`@a`/`@p`/`@r`...) resolving solely to
     vanished players or leaking a vanished match, the command is blocked with vanilla's exact
     `"No player was found"` message (`§cNo player was found`).
   - **Vanilla command tab completion:** Paper 26.3 syncs Bukkit visibility into client Brigadier
     suggestions automatically when `hidePlayer` is active, so `/minecraft:msg` and `/minecraft:tp`
     hide vanished players from normal viewers. **Spigot 1.20.1 limitation:** Spigot 1.20.1's
     vanilla Brigadier dispatcher for `minecraft:*` commands suggests all connected players directly
     without firing `TabCompleteEvent` and cannot be filtered without protocol packet interception;
     Allium-owned commands (`/msg`, `/tpa`, `/mail`, `/heal`, `/kit`, etc.) hide vanished players on
     both Spigot and Paper.
   - **Death & advancement messages:** `PlayerDeathEvent#setDeathMessage(null)` suppresses death
     broadcasts for vanished players on both Spigot 1.20.1 and Paper 26.3. On Paper 26.3+,
     `PlayerAdvancementDoneEvent#message(null)` (invoked reflectively) suppresses advancement
     announcements cleanly. **Spigot 1.20.1 limitation:** Spigot 1.20.1 exposes no per-event
     advancement message setter (`setMessage` is absent from Spigot API 1.20), so vanilla's
     `chat.type.advancement.task` broadcast still fires unless `gamerule announceAdvancements false`
     is configured.
   - **Vanished player's public chat (`moderation.vanish.allow-public-chat`):** configurable boolean,
     default `false`. When `false`, public chat sent by a vanished player is blocked with
     `moderation.vanish-chat-blocked`. Setting `moderation.vanish.allow-public-chat: true` allows
     public chat while vanished.
   - **XP orb attraction & pickup:** `EntityTargetEvent` targeting a vanished player (such as
     `ExperienceOrb` `CLOSEST_PLAYER` homing) is cancelled on both Spigot 1.20.1 and Paper 26.3. On
     Paper 26.3+, `PlayerPickupExperienceEvent` is additionally cancelled reflectively so incidental
     body overlap does not consume orbs. **Spigot 1.20.1 limitation:** Spigot 1.20.1 has no
     cancellable `PlayerPickupExperienceEvent` (`PlayerExpChangeEvent` fires post-consumption and
     is not `Cancellable`), so while XP orb homing toward a vanished player is blocked, colliding
     physically with a stationary/drifting orb on Spigot 1.20.1 can still pick it up.
4. **Join, quit, relog, and restart persistence:**
   - Vanish state is persisted per UUID in `allium_vanish`.
   - `AsyncPlayerPreLoginEvent` loads the joining UUID's vanish & mute state off the main thread
     (waiting up to `moderation.preload-wait-millis`, default `2000` ms).
   - `PlayerJoinEvent` (`LOWEST`) suppresses the join message (`setJoinMessage(null)`) and applies
     `hidePlayer` to all non-privileged viewers on the same tick before join messages broadcast, so
     a relog or server restart never reveals a vanished player.
   - `PlayerQuitEvent` (`LOWEST`) suppresses the quit message (`setQuitMessage(null)`).
5. **World & other interaction side-effect suppression:**
   - **No mob targeting:** existing nearby `Mob` targets on the player are cleared when entering
     vanish, and `EntityTargetEvent` targeting a vanished player is cancelled.
   - **No item or arrow pickup:** `EntityPickupItemEvent` and `PlayerPickupArrowEvent` are cancelled
     for vanished players.
   - **No physical block triggers or sculk vibrations:** `PlayerInteractEvent` (`Action.PHYSICAL`)
     and `BlockReceiveGameEvent` are cancelled when triggered by a vanished player.
   - **Sleep ignore:** `player.setSleepingIgnored(true)` is kept active while vanished.
   - **Silent vs opt-in live container opening:** right-clicking an `ENDER_CHEST` while vanished
     opens `player.getEnderChest()` directly (live, silent). Right-clicking `CHEST`,
     `TRAPPED_CHEST`, `BARREL`, or `*SHULKER_BOX` cancels the block open and shows a read-only
     `SilentContainerHolder` mirror by default — unless the player holds explicit
     `allium.vanish.container.live`, which permits normal read/write opening with vanilla sound,
     animation, and redstone side effects.
6. **Undiscoverable across every Allium command and feature ("looks offline"):**
   - `PlayerNameVisibility#canSeeTarget(viewer, target)` enforces the `"looks offline"` contract
     across tab completion (`BukkitCommandRenderer`, `PlayerNameService#complete`), `/msg`,
     `/reply`, `/ignore`, `/msgtoggle`, `SocialSpy`, `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`,
     `/mail send`, `/mail gift`, `/heal`, `/feed`, `/fly`, `/god`, `/gamemode`, `/clearinventory`,
     `/ping`, `/ext`, `/burn`, `/smite`, and `/kit`.

## Mute enforcement, hardening, and failure modes

1. **Dual-priority chat enforcement (`LOWEST` + `HIGHEST`):**
   - `ModerationService` listens to `AsyncPlayerChatEvent` at **both** `EventPriority.LOWEST`
     (cancel immediately and notify the player) and `EventPriority.HIGHEST` (re-cancel silently if
     another plugin's chat listener un-cancelled the event).
2. **Canonical `CommandMap` muted-command resolution:**
   - `moderation.mute.muted-commands` defaults to canonical commands only:
     `/me`, `/say`, `/tell`, `/minecraft:msg`, `/teammsg`, `/msg`, `/reply`, `/mail send`, and
     `/mail gift`.
   - At runtime (`PlayerCommandPreprocessEvent` at `LOWEST`), `ModerationService` resolves both
     the typed command label and each configured rule's root label through Bukkit's `CommandMap`
     and compares **canonical command identity** via `CommandMapBridge#canonicalCommandIdentity`
     (comparing `Command` instances directly for plugin commands, and unwrapping
     `VanillaCommandWrapper#vanillaCommand` + `CommandNode#getRedirect()` for Brigadier aliases).
   - This intercepts aliases and namespaced forms automatically — including `/w`, `/whisper`, `/m`,
     `/t`, `/r`, `/allium:msg`, `/tm`, `/minecraft:tm`, `/minecraft:tell`, `/minecraft:w`,
     `/minecraft:me`, `/minecraft:say`, `/minecraft:teammsg`, and `/allium:mail send` — without
     enumerating aliases in `config.yml`.
3. **Configurable fail-closed mute contract (`moderation.mute.fail-closed`):**
   - Controlled by `moderation.mute.fail-closed` (default `true`, live-reloadable via
     `/allium reload`).
   - When `true` (default): if a joining player's moderation preload has not finished (`LOADING`) or
     failed (`FAILED`), mute checks fail closed across public chat (`LOWEST` & `HIGHEST`),
     `muted-commands`, `/msg`, `/reply`, `/mail send`, and `/mail gift`, returning
     `moderation.mute-unavailable`.
   - Background retries (`moderation.load-retry`: `retries: 4`, `first-delay-millis: 1000`,
     `max-delay-millis: 15000`) automatically re-query the repository with capped exponential
     backoff so transient database interruptions recover without relog.
   - **Outage cost (`fail-closed: true` vs `false`):** with `fail-closed: true` (matching S03's
     preferences contract), a muted player can never slip through during a slow or errored database
     load, at the **outage cost that unmuted players who join during a database outage or stall
     cannot chat, `/msg`, `/mail`, or run `muted-commands` until a background retry reaches `READY`**.
     Setting `moderation.mute.fail-closed: false` and running `/allium reload` lifts that outage
     block for unmuted players, at the trade-off that a muted player joining during a DB outage
     can speak until a background retry loads their mute record.
4. **Automatic timed mute expiration without restart:**
   - Every timed mute stores `expires_at` (epoch ms). `ModerationService#activeMute` checks
     `record.isExpired(now)` lazily, and `tickExpiredMutes` (500 ms) clears expired mutes, deletes
     the SQL row, and records `MUTE_EXPIRED`.
5. **Known mute limits:**
   - **Sign, book, and anvil-rename text:** mute enforcement covers chat, private messages, mail,
     and `CommandMap`-resolved `muted-commands`. Player-written text on signs (`SignChangeEvent`),
     in writable/written books (`PlayerEditBookEvent`), and on item renames in anvils
     (`PrepareAnvilEvent`) is not intercepted by mute enforcement.

## Storage and audit trail

All SQL operations run on a dedicated bounded executor (`Allium-Moderation`, 2 workers on MySQL /
1 worker on SQLite, queue capacity 512) with schema migrations verified on both **SQLite** and
**MariaDB 10.11 / MySQL**:
- `allium_vanish`: `(player_uuid PRIMARY KEY, vanished, updated_at, actor_name)`
- `allium_mutes`: `(player_uuid PRIMARY KEY, player_name, actor_name, reason, created_at, expires_at)`
- `allium_moderation_audit`: `(id PRIMARY KEY AUTO_INCREMENT, created_at, action, actor_name, target_uuid, target_name, detail)`
  recording `VANISH_ON`, `VANISH_OFF`, `MUTE`, `UNMUTE`, and `MUTE_EXPIRED`, alongside structured
  server log lines (`Moderation audit action=...`).
