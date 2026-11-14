# Player Info Utilities and Self-Give Aliases (S18)

S18 adds Allium's player-info utilities (`/list`, `/seen`, `/whois`, `/near`, `/afk`) built against
real vanish (`PlayerNameVisibility`) and bounded SQL storage (`PlayerInfoRepository` + name history
migration in `PlayerNameRepository`), plus `/i` and `/item` self-use aliases for `/give`.

## Commands and permissions

### Player info utilities
- `/list` (alias `/online`):
  - Lists visible online players and count (`{online}/{max}`), optionally grouped by configured
    permission group priority (`info.list.groups`, default: `owner`, `admin`, `mod`, `staff`,
    `vip`, then `info.list.default-group` = `Players`).
  - Checks explicit `allium.list.group.<group>` permission nodes first, then falls back to Vault's
    primary group (optional runtime `Permission` provider via `VaultGroupResolver`), then
    `Players`.
  - Appends `[AFK]` and (only for `allium.vanish.see` viewers / console) `[Vanished]`.
  - Permission: `allium.list` (default `true`).
- `/seen <player>`:
  - Shows online duration + `First joined` for visible online players, or offline `last_seen`
    (relative duration + UTC timestamp) + `First joined` for offline or hidden vanished players.
  - Permission: `allium.seen` (default `true` — public by default).
- `/whois <player>`:
  - Shows header (`online`/`offline`), UUID, `First joined` + `Online for` / `Last seen`,
    staff-only `Location: <world> (<x>, <y>, <z>)` (only when visible-online AND viewer holds
    `allium.whois.location`), and `Known names` history from `allium_player_name_history`.
  - Permissions: `allium.whois` (default `op` — staff only, protecting UUID, name history, and
    location), `allium.whois.location` (default `op`).
- `/near [radius]`:
  - Lists visible online players in the same world within `radius` blocks (default
    `info.near.default-radius` = `200`, max `info.near.max-radius` = `500`), sorted ascending by
    distance with `[AFK]` and viewer-permitted `[Vanished]` tags. Player-only.
  - Permissions: `allium.near` (default `true`), `allium.near.bypass-max` (default `op`).
- `/afk [message]` (alias `/away`):
  - Toggles manual AFK state with optional reason (sanitized via `UntrustedText` with `Allow.NONE`
    — no formatting or click/hover injection).
  - Auto-AFK enters after `info.afk.auto-afk-seconds` (default `300`; `0` disables) of inactivity;
    movement (`> 0.2` blocks or rotation), chat, non-`/afk` commands, interactions, and inventory
    clicks clear AFK.
  - **Optional kick on AFK:** `info.afk.kick-on-afk` is **false by default**. When enabled,
    non-exempt players remaining AFK for `info.afk.kick-after-seconds` (default `600`) are kicked.
  - Permissions: `allium.afk` (default `true`), `allium.afk.kick.exempt` (default `false`, explicit
    only — not included in `allium.*`).

### Self-use give aliases (`/i`, `/item`)
- `/i <item[,item...]> [amount] [key:value...] [-e] [--overflow=drop|refuse|partial]` (alias `/item`):
  - Registered on `CoreCommandSpecs.ITEM` and delegated directly to `GiveCommand#give(player,
    List.of(player), specText)`.
  - Shares `allium.give` (base permission, default `op`), `allium.give.privileged` (default
    `op`), `ItemSpecParser`, `ItemLibrary`, and `ItemDeliveryService` identically with `/give`.
  - Console callers receive `/give <player> ...` usage rather than silent failure.

## Vanish "looks offline" and sleep coordination rules

1. **Identical offline responses for vanished targets:**
   - Every lookup re-evaluates `PlayerNameVisibility#canSeeTarget(viewer, target)` on the server
     thread right before formatting output.
   - A viewer lacking `allium.vanish.see` sees **exact offline responses** for an online vanished
     player across `/list` (excluded from count & list), `/near` (excluded), `/seen` (`offline` +
     `last_seen` timestamp, **never** `"online"`), `/whois` (`offline` + `last_seen`, **never**
     `Location:` even if the viewer holds `allium.whois.location`), `/afk` broadcasts (suppressed
     for viewers without `allium.vanish.see`), and tab completion.
   - `last_seen` is updated to the vanish transition timestamp when a player enters vanish, and
     is **not** overwritten when a vanished player quits, when the server shuts down, or when a
     vanished player joins again (unless it was their very first join ever).
2. **Coordinated sleep ignore (`setSleepingIgnored`):**
   - `ModerationService` and `PlayerInfoService` share a combined check:
     `player.setSleepingIgnored(isVanished(player) || isSleepingIgnoredByAfk(player))`.
   - Entering or leaving AFK while vanished never clears vanish's `sleepingIgnored=true`, and
     unvanishing while AFK keeps `sleepingIgnored=true` until AFK ends.

## Bounded storage and thread safety

- All SQL reads and writes run off the server thread on a dedicated bounded single-worker executor
  (`Allium-PlayerInfo`, queue capacity `512`) for strict causal FIFO ordering across joins, vanish
  transitions, quits, and lookups; there is **no synchronous SQL on the main thread**.
- `allium_player_info`: `(player_uuid PRIMARY KEY, last_name, first_join, last_join, last_seen)`
  (schema component `player_info` v1, SQLite & MySQL/MariaDB).
- `allium_player_name_history` (`player_names` schema v2): tracks distinct `(player_uuid, name_lower)`
  aliases ordered most-recently-seen first (`first_seen`, `last_seen`), automatically backfilling
  existing v1 `allium_player_names` rows during migration.
- Shutdown (`PlayerInfoService#disable`) flushes `last_seen` for visible online players within a
  2-second bounded wait.
