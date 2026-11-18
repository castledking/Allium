# Creative manager and gamemode permissions

## Per-gamemode permissions

`/gamemode <mode> [player]` (and `/gmc`, `/gms`, `/gma`, `/gmsp`, `/creative`, ...) needs
`allium.gamemode` **and** `allium.gamemode.<survival|creative|adventure|spectator>` for the mode,
as in legacy Allium: `allium.gamemode` alone grants no mode. Changing another player also needs
`allium.gamemode.others`. Completion lists only the modes the sender may use. Every node is op by
default and part of `allium.*`.

## Creative manager (`modules.creative`)

Configured under `creative-manager` in `config.yml`.

### Restrictions

In creative mode each action needs its node (all op by default):

| Node | Allows |
| --- | --- |
| `allium.gamemode.creative.place` / `.break` | placing and breaking blocks (and placing entities such as armor stands) |
| `allium.gamemode.creative.interact` | doors, buttons, beds, containers by click, item frames, armor stands, mobs, food, potions, bows and thrown items, fishing |
| `allium.gamemode.creative.use` | taking items from the creative menu |
| `allium.gamemode.creative.drop` / `.pickup` | dropping and picking up items |
| `allium.gamemode.creative.container` | opening containers: chests, ender chests, furnaces, crafting tables, anvils, villagers... (also through commands such as `/workbench` or `/enderchest`) |
| `allium.gamemode.creative.spawn` | spawn eggs |
| `allium.gamemode.creative.blacklist` | blocks and entities listed under `creative-manager.blacklist` |
| `allium.gamemode.spectator.teleport` | teleporting from the spectator menu |

A refusal shows a red particle puff and `creative.restricted` (or `creative.blacklisted`), at most
once per `message-cooldown-seconds`.

### Separate inventories

Unless the player holds `allium.gamemode.creative.inventory` (op by default, as in legacy Allium),
creative and survival/adventure inventories are kept apart. Spectator keeps whatever inventory the
player held.

- The inventory not in use (storage, armor and offhand) is stored in the player's own data file
  (`allium:creative_stash_survival` / `_creative`, compressed, data-version aware), with
  `allium:creative_group` recording which one the player holds. Both are saved in the same file
  write as the inventory in use, so a crash can never keep one and lose the other.
- A swap is prepared before the gamemode changes: the outgoing inventory is encoded and the
  incoming one decoded. If either fails (for example an inventory saved by a newer Minecraft
  version), the gamemode change is cancelled and nothing moves (`creative.inventory-failed`). The
  swap is applied at `MONITOR`, once no plugin can cancel the change.
- The open inventory is closed first, so items in a crafting grid or on the cursor return to the
  inventory they came from.
- On join, a recorded group that does not match the gamemode (the server forced a gamemode, or the
  mode changed while Allium was off) is reconciled by the same swap.
- `/restore` takes a `GAMEMODE` snapshot before every change.

### Gamemode restore and audits

- `restore-gamemode-on-join`: a player who left in creative or spectator and joins in survival or
  adventure (`force-gamemode`) is put back, if they still hold that mode's node at join.
- `audit`: holders of `allium.alerts` are told when someone switches to, or joins in, creative or
  spectator without that mode's node (`creative.audit`); the console logs it too.

### Not ported

Legacy Allium's Multiverse stack inspection (reverting gamemode changes made by Multiverse) is not
ported; the join reconciliation covers the inventory side. Night-vision-per-gamemode nodes
(`allium.gamemode.<mode>.nv`) are not ported.

## Verification

- **Live (Spigot 1.20.1 and Paper 26.3, [staff gate](../tests/live/staff/RESULTS.md)):** per-mode
  refusal and completion, inventory separation across survival/adventure/creative, a restart with
  `force-gamemode=true` (restored to creative, both inventories intact), the container restriction
  through `/workbench`, and the audit alert.
- **Unit:** the swap policy (`CreativeInventoryPolicyTest`).
- **Not verified live:** the place, break, interact, drop, pickup, creative-menu, spawn-egg,
  blacklist and spectator-teleport restrictions (the gate clients cannot send those packets).
