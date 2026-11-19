# Flight restoration and slow fall

`/toggleslowfall [on|off|status]` and `/tsf` require `allium.fly`. Legacy aliases
`slowfalltoggle` and `toggleslowfalling` remain available. The preference is saved
in native playerdata and survives reconnects and service reloads. Command feedback
uses `player.slowfall-toggle` in the language file.

Flight allow/flying flags are restored in survival/adventure only with current
`allium.fly` permission. World transitions preserve an authorized pre-teleport
flight state. Creative/spectator retain their native flight rules. Losing enabled
flight, or leaving creative/spectator in midair, applies short, refreshed slow
fall protection unless disabled. Landing, flight, quit or shutdown clears owned
protection; stronger or longer existing effects are not removed.

`allium.fly.safelogin` defaults false. It resets fall distance and starts flight
on an airborne login outside water when the three-block downward check finds no
support. This follows the supplied Essentials listener/LocationUtil behavior;
it is independent of a saved `/fly` toggle. The delayed join callback is bound to
the current Player instance, so an older connection cannot alter a new one.

Native Bukkit PDC byte keys (version 1): `allium:slowfall_disabled_v1`,
`allium:flight_allowed_v1`, `allium:flight_flying_v1`. Preserve their names and
meaning for future importers. Save/load uses the server's playerdata APIs and
never reconstructs or writes inventory NBT. Timed-flight banks are kept in
storage instead (below).

## Timed flight

Legacy Allium's tfly. A player with `allium.tfly` (default true) but not `allium.fly` who has
time banked turns flight on and off with `/fly`. Turning it on allows flight without lifting the
player. When the time runs is set in `config.yml`:

```yaml
timed-flight:
  count-only-while-flying: false
```

By default (`false`) the time runs from `/fly` until the player turns it off or it runs out, on the
ground or in the air. With `true` it runs only while they are in the air, so standing or walking
costs nothing, as in legacy Allium; `/fly` then says so. Either way creative and spectator never
spend time. `/allium reload` applies a change, also to timed flight that is already on.

Warnings come at 60, 30 and 10 seconds left and at each of the last 5. At zero flight ends (with
the slow fall above if they are in the air) and `/fly` says there is no time left. Timed flight is never switched back on at join, as in legacy Allium;
if something else turns flight off (a gamemode change, another plugin), the player turns it on
again.

Staff change anyone's bank, online or offline (by the name index):

| Command | Permission (default `op`) |
| --- | --- |
| `/fly <player> add <time>` (also `give`) | `allium.tfly.add` |
| `/fly <player> take <time>` (also `remove`) | `allium.tfly.take` |
| `/fly <player> set <time>` | `allium.tfly.set` |
| `/fly <player> reset` | `allium.tfly.reset` |

A time is bare seconds (`90`) or a duration (`30m`, `2h`, `1d`, `1h30m`). The sender sees the new
total, an online target is told, and each change is audited. `allium.fly` includes `allium.tfly`,
and `/fly [player]` with `allium.fly` is the ordinary unlimited toggle.

The bank lives in `allium_flight_time` (seconds per UUID, at most ten years). Spent time is written
as a relative change every 30 seconds and on quit or shutdown, so time given from another server
sharing the database is not overwritten. If storage fails, the spent seconds are kept and written
on the next attempt, and a staff change that cannot be saved says so.

PlaceholderAPI: `%allium_fly%` is `temporary` while timed flight's time is running and
`temporary-paused` while it is on but not spending time (on the ground with
`count-only-while-flying`, or in creative), and `%allium_fly_time%` is the time left
([placeholders.md](placeholders.md)).

Legacy banks (`player_data.tfly_time`) are not imported yet.
