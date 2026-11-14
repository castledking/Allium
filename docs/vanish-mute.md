# Vanish and Mute (S17)

S17 replaces the stub `VanishSource` and `MuteSource` hooks across messaging, social spy, mail,
teleport requests, warps, and player-targeting commands with persistent, bounded-storage
implementations in `ModerationService` and `ModerationRepository`.

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

### Mute
- `/mute <player> [duration] [reason]`:
  - Mutes `<player>` permanently (when `[duration]` is omitted or is part of the reason) or for a
    timed duration (`5s`, `10m`, `2h`, `1d`, `1w`, or compound tokens like `1h30m`).
  - `/mute list` (when the sender has no target named `list` in args) delegates to `/mutes`.
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
   - Every online player without `allium.vanish.see` has `viewer.hidePlayer(plugin, vanishedPlayer)`
     applied immediately on `/vanish`, on viewer join, and on vanished player join.
   - Staff with `allium.vanish.see` retain `viewer.showPlayer(plugin, vanishedPlayer)` so they can
     see the vanished player in the world and tab list.
   - The `"vanished"` metadata key (`FixedMetadataValue(plugin, true)`) is also maintained on the
     player for compatibility with third-party plugins and `VanishSource.METADATA`.
2. **Server list ping count (`ServerListPingEvent`):**
   - Vanished players are removed from `ServerListPingEvent#iterator()`, reducing the reported
     online player count and sample list on server list pings.
3. **Join, quit, relog, and restart persistence:**
   - Vanish state is persisted per UUID in `allium_vanish`.
   - `AsyncPlayerPreLoginEvent` refreshes the joining UUID's vanish state from SQL before the player
     entity joins the main thread.
   - `PlayerJoinEvent` (at `LOWEST` priority) suppresses the join message (`setJoinMessage(null)`)
     and applies `hidePlayer` to all non-privileged viewers on the same tick before the join is
     broadcast, so a relog or server restart never reveals a vanished player.
   - `PlayerQuitEvent` (at `LOWEST` priority) suppresses the quit message (`setQuitMessage(null)`).
4. **World & interaction side-effect suppression:**
   - **No mob targeting:** existing nearby `Mob` targets on the player are cleared when entering
     vanish, and `EntityTargetEvent` targeting a vanished player is cancelled.
   - **No item or arrow pickup:** `EntityPickupItemEvent` and `PlayerPickupArrowEvent` are cancelled
     for vanished players.
   - **No physical block triggers or sculk vibrations:** `PlayerInteractEvent` with
     `Action.PHYSICAL` (pressure plates, tripwires, farmland trampling, turtle eggs, big dripleaf)
     and `BlockReceiveGameEvent` (sculk sensors and shriekers) are cancelled when the actor is
     vanished.
   - **Sleep ignore:** `player.setSleepingIgnored(true)` is set while vanished so a vanished staff
     member never blocks night skip.
   - **Silent container opening:** right-clicking an `ENDER_CHEST` while vanished (and not sneaking)
     cancels the block interaction and opens `player.getEnderChest()` directly without chest lid
     animation or sound. Right-clicking an audible lidded container (`CHEST`, `TRAPPED_CHEST`,
     `BARREL`, `*SHULKER_BOX`) while vanished cancels the block interaction and opens a read-only
     virtual inventory snapshot (`SilentContainerHolder`) so no lid animation, sound, or redstone
     observer/trapped-chest signal is emitted in the world.
5. **Undiscoverable across every command and feature ("looks offline"):**
   - `PlayerNameVisibility#canSeeTarget(viewer, target)` combines `ModerationService#isVanished`,
     `ModerationService#syncPairVisibility`, and `viewer.canSee(target)` with `allium.vanish.see`.
   - Normal players without `allium.vanish.see` never receive a vanished player's name in tab
     completion (`BukkitCommandRenderer`, `PlayerNameService#complete`) and receive the exact same
     `PLAYER_NOT_FOUND` / offline response as for an offline player in:
     - `/msg`, `/reply`, `/ignore`, `/msgtoggle` (`MessagingService`, `MessagePolicy` — `/reply`
       returns `You have nobody to reply to.` when a previous reply target is vanished, matching
       offline behavior)
     - `SocialSpy` (a spy without `allium.vanish.see` never receives spy notifications involving a
       vanished participant)
     - `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny` (`TeleportService`, `TeleportCommand`, and silent
       cancellation of pending non-privileged teleport requests when a player vanishes so normal
       players receive no leak)
     - `/mail send` and `/mail gift` tab completion (`PlayerNameService#complete` hides vanished
       online players from non-privileged completers; sending mail by typed name uses the offline
       known-player mail path without leaking online status)
     - `/heal <player>`, `/feed <player>`, `/fly <player>`, `/god <player>`, `/gamemode <mode> <player>`,
       `/clearinventory <player>`, `/ping <player>`, `/ext <player>`, `/burn <player>`, `/smite <player>`
       (`BukkitCommandContext#resolveSelfOrTarget`)
     - `/kit <name> <player>` (`KitCommand`)

## Bukkit / Paper version capability matrix

| Capability | Spigot 1.20.1 | Paper 26.3 | Notes |
| --- | --- | --- | --- |
| Entity & tab-list hiding (`Player#hidePlayer(Plugin, Player)`) | Supported | Supported | Hides entity tracker + removes player from client tab list |
| Ping player count & sample (`ServerListPingEvent#iterator().remove()`) | Supported | Supported | Catches `UnsupportedOperationException` if a custom ping implementation disallows removal |
| Join/quit message suppression (`setJoinMessage(null)` / `setQuitMessage(null)`) | Supported | Supported | Applied at `EventPriority.LOWEST` |
| Mob targeting suppression (`EntityTargetEvent` + `Mob#setTarget(null)`) | Supported | Supported | Clears existing targets within 64 blocks on `/vanish on` |
| Item & arrow pickup suppression (`EntityPickupItemEvent`, `PlayerPickupArrowEvent`) | Supported | Supported | Cancelled at `EventPriority.LOWEST` |
| Pressure plate / tripwire / physical trigger suppression (`Action.PHYSICAL`) | Supported | Supported | Cancels `PlayerInteractEvent` for `Action.PHYSICAL` |
| Sculk sensor / vibration suppression (`BlockReceiveGameEvent`) | Supported | Supported | Cancels `BlockReceiveGameEvent` when source entity is vanished |
| Silent Ender Chest open (`player.openInventory(player.getEnderChest())`) | Supported (live R/W) | Supported (live R/W) | Opens the player's own Ender Chest inventory without block animation or sound |
| Silent lidded container open (`CHEST`, `TRAPPED_CHEST`, `BARREL`, `SHULKER_BOX`) | Read-only snapshot mirror | Read-only snapshot mirror | Bukkit 1.20.1 lacks a cross-platform silent live-container open API; Allium cancels the block open and shows a cloned read-only `SilentContainerHolder` inventory so zero lid animation, sound, or trapped-chest redstone is emitted |

## Mute enforcement and expiration

1. **Unified `MuteSource` hook:**
   - `ModerationService` implements `MuteSource` and registers itself on `MessagingService` during
     `ModerationModule#onEnable`.
   - `MailService` delegates `muted(UUID)` and `sendMutedNotice(CommandSender)` through
     `MessagingService`, so `/msg`, `/reply`, `/mail send`, and `/mail gift` all enforce the same
     active mute state.
   - Public chat is intercepted by `ModerationService#onAsyncPlayerChat` at `EventPriority.LOWEST`
     and cancelled when `activeMute(sender.getUniqueId())` is present.
2. **Remaining-time feedback:**
   - Whenever a muted player attempts to send public chat, `/msg`, `/reply`, `/mail send`, or
     `/mail gift`, they receive `moderation.mute-blocked`:
     `You are muted (<remaining> remaining). Reason: <reason>`.
3. **Automatic timed mute expiration without restart:**
   - Every timed mute stores `expires_at` in epoch milliseconds.
   - `ModerationService#activeMute` checks `record.isExpired(now)` lazily on every chat/message/mail
     check, and a repeating scheduler task (`tickExpiredMutes` every 500 ms) proactively expires
     elapsed mutes in memory, deletes the expired row from `allium_mutes`, and writes a
     `MUTE_EXPIRED` audit entry on the bounded storage executor.

## Storage and audit trail

All SQL operations run on a dedicated bounded executor (`allium-moderation`, 1 thread, queue capacity
256) in `ModerationModule`. No synchronous SQL ever runs on the main server thread during gameplay:
- `allium_vanish`: `(player_uuid PRIMARY KEY, vanished, updated_at, actor_name)`
- `allium_mutes`: `(player_uuid PRIMARY KEY, player_name, actor_name, reason, created_at, expires_at)`
- `allium_moderation_audit`: `(id PRIMARY KEY AUTO_INCREMENT, created_at, action, actor_name, target_uuid, target_name, detail)`
  recording `VANISH_ON`, `VANISH_OFF`, `MUTE`, `UNMUTE`, and `MUTE_EXPIRED`, alongside structured
  server log lines (`Moderation audit action=...`).
