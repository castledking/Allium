# Teleport landing policy

Players with enabled flight, creative mode or spectator mode may teleport into
clear air. Other players need the configured safe landing. Flight does not bypass
suffocation or hazard checks. This also applies to a midair /back origin.

`allium.teleport.unsafe` is default false and is not included in `allium.*`.
An issuing player or console explicitly granted this permission bypasses landing,
collision and hazard checks for their teleport. Finite coordinates, world height,
world border and configured destination-chunk loading rules still apply. The
service rechecks the issuing permission and the mover's flight capability at
physical commit, so a warmup does not retain a revoked bypass.

Essentials' checked source uses flight-aware destination safety and its safe-login
permission checks unsafe blocks below the player; no identically named
`essentials.teleport.unsafe` node was found in the supplied checkout.
