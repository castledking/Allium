# Kits (S16)

`/kit` and `/kits` list the free kits the sender has permission to claim. `/kit <name>` claims a
kit for yourself; `/kit <name> <player>` gives a kit to another online player. `/createkit <name> [cooldown] [once]`
captures the creator's storage inventory into `plugins/Allium/kits/<name>.yml`; `/delkit <name>`
deletes the kit definition file.

## Definitions, naming, and item resolution

Each kit is stored as a standalone YAML file under `plugins/Allium/kits/*.yml`:

```yaml
name: starter
enabled: true
cooldown-seconds: 300
one-time: false
cost: '0.00'
items:
  - minecraft:stone_sword 1 unbreaking:1
  - minecraft:stone_pickaxe 1 efficiency:1
  - minecraft:cooked_beef 16
```

- **Strict kit name validation (`^[a-z0-9_-]{1,32}$`):** `KitNames.validate` enforces lowercase
  ASCII letters, digits, underscores, and hyphens (`1..32` chars) everywhere a kit name becomes a
  filesystem path or a permission node (`createKit`, `deleteKit`, `KitDefinitionLoader#load`,
  `completeKits`, and `claim`). Traversal attempts (`../x`, `a/b`, `a\b`, absolute paths, `.` / `..`,
  empty names, uppercase, and `> 32` characters) are rejected before any path or permission lookup,
  and `KitDefinitionLoader#safeKitFile` additionally verifies `normalized.getParent().equals(dir)`.
- **Item specs:** every entry in `items` is parsed at load time through `ItemSpecParser` and
  built at claim time through `ItemLibrary` and `ItemService`. Provider items (`nexo:<id>`,
  `craftengine:<ns>:<id>`, `oraxen:<id>`) and signed/privileged Allium items (`allium:mob_disarmer`,
  `allium:phantom_obliterator`, `allium:lazy_axe`) resolve through the same item pipeline as `/give`
  and mint fresh per-instance signatures on every claim.
  - *Live gate provider note:* the supplied Nexo runtime requires Paper and cannot load on Spigot
    1.20.1. In the S16 live gate (`tests/live/kits/`), both the Spigot 1.20.1 and Paper 26.3 lanes
    exercise the `ItemService` provider path through `KitGate`'s registered test
    `ItemProviderAdapter` stub (`nexo:ruby_sword`), while real Nexo/CraftEngine/Oraxen runtimes are
    exercised on Paper 1.21.8 in S04/S05/S06a/S10.
- **Decimal text cost (`cost: '0.00'`):** per the S14 monetary rule, kit `cost` is configured and
  saved as canonical decimal text (`'0.00'`), not a binary `double`. Legacy numeric YAML values
  (`cost: 0.0` or `cost: 50.0`) are accepted on load with a startup/reload warning recommending
  quoted decimal text and normalized to two decimal places.
- **Lossless `/createkit` capture and privileged item gating:**
  - Capturing any item whose `AlliumItemDefinition` has `privileged = true` (such as
    `allium:handcuffs` or `allium:phantom_obliterator`) requires `allium.give.privileged`. Without
    it, `/createkit` refuses and names the slot, item, behavior, and required permission.
  - If any stack in the creator's storage inventory (slots `0..35`) cannot be represented
    losslessly by Allium's item grammar—including non-empty shulker box or container contents,
    bundle contents, written book title/author/pages, attribute modifiers, custom model data on
    non-Allium items, durability damage, item flags, anvil repair cost, custom PDC keys, or
    multi-effect/custom-duration potions—`/createkit` refuses the entire capture, naming the slot
    index and the unsupported data, and never writes a degraded kit file.
- **Validation:** unknown YAML keys, invalid bounds (`cooldown-seconds` outside `0..31536000`,
  negative or >2-decimal `cost`, empty or `> 36` item entries), invalid kit names, or invalid item
  specs reject the whole file with path-specific console diagnostics.
- **Default kit:** `kits/starter.yml` is installed once on first run and recorded in
  `kits/.defaults`. Deleting `kits/starter.yml` removes it permanently rather than recreating it on
  restart.
- **Paid kits denied until M3 reservations:** any kit with `cost > '0.00'` is flagged as paid,
  omitted from player kit listings/completions, and explicitly denied on `/kit <name>` until the M3
  economy reservation engine exists. No economy debiting or console command execution runs from kits.
- **Sign hook:** `[Kit]` sign interaction is deferred to the signs slice.

## Permissions, `/kit <name> <player>`, and cooldowns

- `allium.kit` (default `true`): use `/kit [name]`.
- `allium.kits` (default `true`): use `/kits`.
- `allium.kits.*` (default `false`): parent wildcard permission whose children are all registered
  `allium.kits.<name>` nodes. Granting `allium.kits.*` grants access to every kit with one node
  while keeping `PermissionDefault.FALSE` so operators do not inherit kits implicitly.
- `allium.kits.<name>` (registered dynamically with `PermissionDefault.FALSE`, child of
  `allium.kits.*`): per-kit access. If another plugin pre-registers `allium.kits.<name>` with a
  non-false default, Allium logs a severe diagnostic and blocks access to that kit until fixed.
- `allium.kit.others` (default `op`): give a kit to another online player (`/kit <name> <player>`).
  - **Contract for `/kit <name> <player>`:**
    1. **Permissions:** a player sender must hold `allium.kit`, `allium.kit.others`, **and**
       `allium.kits.<name>` (or `allium.kits.*`). Console senders do not need `allium.kits.<name>`
       (unless the kit's permission node is blocked by a non-false default). The target player does
       not need `allium.kits.<name>`.
    2. **Cooldown and usage ownership:** giving a kit to another player bypasses cooldown checks,
       while `claim_count`, `last_claimed_at`, and `one_time_claimed` in `allium_kit_usages` apply
       to the **target player** (`target.getUniqueId()`). If the target has already claimed a
       one-time kit, `/kit <name> <target>` is refused with `kit.one-time-claimed`.
- `allium.kit.bypass.cooldown` (default `false`): skip per-kit cooldowns on self-claims (one-time
  kits remain one-time).
- `allium.createkit` / `allium.delkit` (default `op`): create and delete kit definitions.

## Overflow rule, recovery ownership, and exactly-once delivery

- **All-or-nothing fit (`REFUSE`):** before placing any item, `ItemDeliveryService#planMail`
  computes an exact storage-slot plan and fires the cancellable `ItemGiveEvent`. If any stack in
  the kit does not fit in the target's storage inventory (or if a plugin cancels `ItemGiveEvent`),
  nothing is placed and the claim is released without starting the cooldown or consuming a
  one-time kit.
- **Durable claim, server ownership (`claim_owner`), and receipt verification (S12 pattern):**
  1. `KitRepository#beginClaim` acquires a per-player lock row (`allium_kit_locks`), checks
     in-flight deliveries, one-time usage, and cooldown in `allium_kit_usages`, and inserts a
     `CLAIMING` row in `allium_kit_deliveries` with a fresh `claim_token` and
     `claim_owner = serverId` (`plugins/Allium/.server-id`).
  2. On the server thread, `ItemDeliveryService#applyMail` applies the verified inventory plan,
     writes `K/<deliveryId>/<token>/<kitName>` into `allium:kit_receipts` in the player's
     persistent data container, and calls `player.saveData()`.
  3. On the bounded storage pool, `PlayerDataReceiptProof.verify` reads the compressed
     `playerdata/<uuid>.dat` file on disk and confirms the exact `allium:kit_receipts` string
     before `KitRepository#finishClaim` (scoped by `id`, `claim_token`, and `claim_owner = serverId`)
     records the cooldown/one-time usage in `allium_kit_usages`, writes an immutable
     acknowledgement to `allium_kit_claim_receipts`, and removes the `CLAIMING` row.
  4. **Cross-server recovery ownership:** `claimingFor(playerUuid, serverId)`,
     `finishClaim(deliveryId, token, serverId, ...)`, and `releaseClaim(deliveryId, token, serverId)`
     all require `claim_owner = serverId`. A `CLAIMING` row owned by another server in a shared
     MySQL database is never released or finalized by this server, while the owning server still
     prevents concurrent duplicate claims for that player across the network.
  5. If the player disconnects or the owning server crashes before `player.saveData()` completes,
     join recovery on the owning server sees no `K/...` receipt in `playerdata/<uuid>.dat` and
     releases the `CLAIMING` row without burning the cooldown or one-time kit status. If the owning
     server crashes after `player.saveData()` but before SQL finalization, join recovery verifies
     the on-disk receipt and finishes the claim in SQL without delivering the items a second time.
