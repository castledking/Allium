# PlaceholderAPI placeholders

With PlaceholderAPI installed, Allium registers `%allium_<name>%`. The names and formats are
legacy Allium's, so existing tab, scoreboard and chat configurations keep working. Names are
case-insensitive; an unknown name is left as written.

A request on the server thread is answered live. One from another thread, such as a tab or
scoreboard plugin refreshing asynchronously or async chat, gets the value the server thread
computed within the last second. Balances, the top accounts and mail counts come from snapshots
their own workers refresh every 10 seconds, so no placeholder reads storage. Offline players get
empty values (the `baltop_` entries work for anyone).

## Player

| Placeholder | Value |
| --- | --- |
| `nickname`, `gradientdisplayname` | The chat nickname (with its formatting), or the name |
| `nickname_raw` | The nickname without formatting |
| `displayname` | Bukkit's display name |
| `ping` | Ping in ms |
| `fly` | `yes` or `no`; with timed flight on, `temporary` while its time runs and `temporary-paused` while it does not (on the ground with `timed-flight.count-only-while-flying`, or in creative) |
| `god`, `vanished`, `afk`, `muted`, `jailed`, `frozen`, `spy` | `yes` or `no` (`spy` is social spy) |
| `fly_time` | Timed flight left, `1d 2h 30m 45s` (`0s` without any) |
| `fly_time_short` | The same, its two largest parts: `1d 2h` |
| `gamemode` | `survival`, `creative`, `adventure` or `spectator`; `restricted-creative` in creative without `allium.gamemode.creative.bypass` (the creative manager's restrictions apply) |
| `playtime`, `playtime_short` | Time played (Minecraft's play-time statistic), `12d 6h 30m 5s` and `12d 6h` |
| `firstjoindate` | When the player first joined, in `playtime.date-format` (default `Nov 19, 2026`) |
| `reply` | Who `/reply` would answer, or empty |
| `mail_unread`, `mail_gifts` | Unread mail and unclaimed gifts |
| `teleport_toggled` | `true` while teleport requests to the player are blocked (`/tptoggle`), else `false` |
| `teleport_requests` | Teleport requests the player sent or received that are still open |
| `teleport_cooldown` | Seconds until the player's longest teleport cooldown ends |
| `homes_set` (`home_set`), `homes_max` (`home_max`) | Homes set, and the home limit |
| `world` | The world's name |
| `world_time`, `world_time_24` (`world_time_24h`) | The world clock, `6:00 AM` and `06:00` at time 0 |
| `world_date` | The world's calendar date from its full time, `Jan 1, 0` (365-day years) |

## Money

| Placeholder | Value for 12345.678 |
| --- | --- |
| `balance` | `12345.68` |
| `balance_fixed` | `12345` |
| `balance_commas` | `12,345.68` |
| `balance_formatted` | `12.3k` (`1.5M`, `2.3B`; below a thousand, whole units) |
| `balance_display` | As `/balance` shows it, e.g. `$12,345.68` |
| `baltop_rank`, `baltop_rank_formatted` | The player's place among the top 100 (`2`, `2nd`), or `0` / `Unranked` |
| `baltop_player_<n>`, `baltop_player_stripped_<n>` | The name in place `n` (1-100), with or without formatting |
| `baltop_balance_<n>`, `baltop_balance_fixed_<n>`, `baltop_balance_commas_<n>`, `baltop_balance_formatted_<n>`, `baltop_balance_display_<n>` | That account's balance, in the formats above |

Player balances are the available amount `/balance` shows; the top list ranks book balances as
`/baltop` does. Places past the end of the list are empty.

Legacy Allium's `balance` printed a raw double (`1.0E7` for ten million); Allium prints two
decimals instead. Legacy's `team_*`, `violation_*`, `command_canuse_*` and location placeholders
are not provided.
