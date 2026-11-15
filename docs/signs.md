# S21 service signs

Signs are opt-in. The default `enabledSigns: []` activates none. Set
`sign-use-per-second: 4` (1–100) for the shared per-player budget across all types,
faces and blocks. `/allium reload` replaces validated sign settings. Disabling
`modules.signs`, an individual type, the related command family or its module
prevents actions. Unknown type names refuse sign-module startup/reload; legacy
`colour` normalizes to `color`.

Types, in order: **color, warp, kit, balance, heal, repair, disposal, workbench,
anvil, mail, info**. Buy/sell/trade are excluded. All actions are self-service;
paid kit signs call the same kit handler and receipt-backed reservation service as
`/kit`, with the same payer/target identity and inventory admission rules.

Write `[type]` on line one. Warp and kit require the destination/kit name on line
two. A warp may explicitly say `public` on line three, but its creator must also
have `allium.signs.create.warp.public` (default false). This authorizes access
through that sign without a per-warp permission; ordinary warp signs retain
`allium.warps.<name>`. All warp signs still require the ordinary `allium.warp`
command permission and use the same safe teleport, readiness and destination
checks. Public signs are grants for that warp name; remove them to revoke access.

Each type has independent false-default permissions:

```
allium.signs.create.<type>
allium.signs.use.<type>
allium.signs.break.<type>
```

Editing an existing action face requires its break permission and the new type's
create permission. Users also need the ordinary command permission to invoke its
service. Color only formats the sign text; it has no executable action. Ampersand
colors in a newly admitted sign are converted on creation. Balance displays
**available** funds without opening an account or mutating money. Heal, repair,
disposal, workbench, anvil and info use the exact handlers registered for their
commands. Repair changes only held-item damage and retains its existing metadata;
inventory utilities refuse while mail/kit receipt recovery holds admission.
Mail opens `/mail read`; info displays server name and visible online count. Other lines
never execute a command or supply a target player.

Allium writes a versioned UUID/type/argument identity into the sign tile's PDC,
separately for FRONT and BACK. Colored headers, sign-item names, and visible text
alone cannot activate anything. Waxing does not alter authority. Standing, wall
and hanging signs use the same face identities. Actions re-read the current tile
and identity after protection handlers finish; cancelled interactions and changed
or destroyed tiles do nothing. Only the main hand is processed; a pending action
per player and the shared budget prevent same-tick duplicate admission. The kit
service additionally owns its existing in-flight/receipt locks.

There is no location cache or SQL sign registry. Chunk saves/reloads and restarts
retain tile PDC; explosion/piston/native destruction removes it with the sign.
A replacement block inherits no authority. Break protection and per-face break
permissions apply before native destruction; environmental destruction remains
subject to the server's normal explosion/piston protection events. Sign settings
never delete economy workflow, journal, marker or recovery evidence.
