# Offline teleport

`/tpoffline <player>`, `/otp <player>` and `/offlinetp <player>` move the caller to a
known offline player's last successfully saved logout position. Allium-qualified
aliases work as well. `allium.tp.offline` defaults to operators. A visible online
target delegates to the existing `/tp` service and additionally requires
`allium.tp`, including its recipient/toggle/ignore and bypass rules. Console uses
ordinary `/tp` with two online targets; the offline command is player-only.

Like legacy's LOGOUT lookup, offline destinations come from Allium storage. The
existing `allium_teleport_locations` table now uses the `logout` slot alongside
`back` and server `spawn`. The schema and serialized formats do not change. Normal
quit and clean service shutdown capture immutable locations on the server thread
and save through the bounded teleport worker. This is the last **successfully
saved** position: a failed/full write logs a warning and retains an older position.
A hard crash can likewise leave the previous successful save. Existing users gain
a destination after their next successful quit/shutdown save; legacy positions
are not automatically imported by this feature. An importer can map legacy
`LocationType.LOGOUT` to this slot.

Known-name completion reads only the bounded memory index and filters vanished
online players. Execution resolves offline identities against authoritative shared
storage; it does not query Mojang or enumerate Bukkit offline players. Unknown
names, absent logout locations and hidden online targets receive the same generic
refusal. A vanished online player never falls back to an older logout row.

Locations use their saved world UUID, require that exact world to be loaded, and
pass the normal destination-safety checks. The regular warmup, movement/damage
cancellation, cooldown, history persistence and `/back` pipeline apply. Set
`teleport.cooldowns.tpoffline` to configure the shared cooldown for all aliases.
Only one offline lookup/teleport per caller can be pending. Cancel, disconnect,
reconnect, shutdown, permission loss, module/family disabling and a target rejoin
fence late replies or physical commits. A target joining and leaving again during
the read/warmup invalidates that snapshot rather than reviving it. Successful
physical moves are logged with actor/target UUIDs. The target's saved native
playerdata is never modified.

See `tests/live/teleport/OFFLINE.md` for unit and native evidence.
