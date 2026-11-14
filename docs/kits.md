# Kits (S16)

`/kit` and `/kits` list the free kits the sender has permission to claim. `/kit <name>` claims a
kit for yourself; `/kit <name> <player>` claims a kit for another online player (requires
`allium.kit.others`). `/createkit <name> [cooldown] [once]` captures the creator's storage
inventory into `plugins/Allium/kits/<name>.yml`; `/delkit <name>` deletes the kit definition file.

## Definitions and item resolution

Each kit is stored as a standalone YAML file under `plugins/Allium/kits/*.yml`:

```yaml
name: starter
enabled: true
cooldown-seconds: 300
one-time: false
cost: 0.0
items:
  - minecraft:stone_sword 1 unbreaking:1
  - minecraft:stone_pickaxe 1 efficiency:1
  - minecraft:cooked_beef 16
```

- **Item specs:** every entry in `items` is parsed at load time through `ItemSpecParser` and
  built at claim time through `ItemLibrary` and `ItemService`. Provider items (`nexo:<id>`,
  `craftengine:<ns>:<id>`, `oraxen:<id>`) and signed/privileged Allium items (`allium:mob_disarmer`,
  `allium:phantom_obliterator`, `allium:lazy_axe`) resolve through the same item pipeline as `/give`
  and mint fresh per-instance signatures on every claim.
- **Validation:** unknown YAML keys, invalid bounds (`cooldown-seconds` outside `0..31536000`,
  negative `cost`, empty or `> 36` item entries), or invalid item specs reject the whole file with
  path-specific console diagnostics.
- **Default kit:** `kits/starter.yml` is installed once on first run and recorded in
  `kits/.defaults`. Deleting `kits/starter.yml` removes it permanently rather than recreating it on
  restart.
- **Paid kits denied until M3 reservations:** any kit with `cost > 0` is flagged as paid, omitted
  from player kit listings/completions, and explicitly denied on `/kit <name>` until the M3 economy
  reservation engine exists. No economy debiting or console command execution runs from kits.
- **Sign hook:** `[Kit]` sign interaction is deferred to the signs slice.

## Permissions and cooldowns

- `allium.kit` (default `true`): use `/kit [name]`.
- `allium.kits` (default `true`): use `/kits`.
- `allium.kits.<name>` (registered dynamically with `PermissionDefault.FALSE`): per-kit access.
  Operators do not inherit false-default kit nodes automatically unless granted explicitly (or via
  a permission plugin). If another plugin pre-registers `allium.kits.<name>` with a non-false
  default, Allium logs a severe diagnostic and blocks access to that kit until fixed.
- `allium.kit.others` (default `op`): give a kit to another online player (`/kit <name> <player>`).
- `allium.kit.bypass.cooldown` (default `false`): skip per-kit cooldowns (one-time kits remain
  one-time).
- `allium.createkit` / `allium.delkit` (default `op`): create and delete kit definitions.

## Overflow rule and exactly-once delivery

- **All-or-nothing fit (`REFUSE`):** before placing any item, `ItemDeliveryService#planMail`
  computes an exact storage-slot plan and fires the cancellable `ItemGiveEvent`. If any stack in
  the kit does not fit in the target's storage inventory (or if a plugin cancels `ItemGiveEvent`),
  nothing is placed and the claim is released without starting the cooldown or consuming a
  one-time kit.
- **Durable claim and receipt verification (S12 pattern):**
  1. `KitRepository#beginClaim` acquires a per-player lock row (`allium_kit_locks`), checks
     in-flight deliveries, one-time usage, and cooldown in `allium_kit_usages`, and inserts a
     `CLAIMING` row in `allium_kit_deliveries` with a fresh `claim_token`.
  2. On the server thread, `ItemDeliveryService#applyMail` applies the verified inventory plan,
     writes `K/<deliveryId>/<token>/<kitName>` into `allium:kit_receipts` in the player's
     persistent data container, and calls `player.saveData()`.
  3. On the bounded storage pool, `PlayerDataReceiptProof.verify` reads the compressed
     `playerdata/<uuid>.dat` file on disk and confirms the exact `allium:kit_receipts` string
     before `KitRepository#finishClaim` records the cooldown/one-time usage in
     `allium_kit_usages`, writes an immutable acknowledgement to `allium_kit_claim_receipts`, and
     removes the `CLAIMING` row.
  4. If the player disconnects or the server crashes before `player.saveData()` completes, join
     recovery sees no `K/...` receipt in `playerdata/<uuid>.dat` and releases the `CLAIMING` row
     without burning the cooldown or one-time kit status. If the server crashes after
     `player.saveData()` but before SQL finalization, join recovery verifies the on-disk receipt
     and finishes the claim in SQL without delivering the items a second time.
