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
never reconstructs or writes inventory NBT. Timed-flight balances are outside
this port; the legacy TFly manager is a separate feature.
