# S16 kits live results

**PASS**.

- **Spigot 1.20.1 / Java 17:** 7/7 cases and **57/57** checks passed; 0 failures. Original summary: `/mnt/storage/.allium-validation/20261114/s16-kits/runs/spigot-1.20.1-s16/summary.json`.
- **Paper 26.3-141 / Java 25:** 7/7 cases and **57/57** checks passed; 0 failures. Original summary: `/mnt/storage/.allium-validation/20261114/s16-kits/runs/paper-26.3-s16/summary.json`.

Frozen `build/libs/Allium.jar` SHA-256: `c400c60e32c1fc740ce7715f90115d62261e0d6cf5d2a16877d6ac0d6a305f62`.

Both lanes ran against identical frozen production, helper, and client source files on owned loopback ports `25760` (Spigot 1.20.1) and `25761` (Paper 26.3), with all server and client processes shut down cleanly after execution.

## Verified Cases (7 cases / 57 checks per lane = 114 live checks total)

1. **`claim-cooldown-and-others`**:
   - Unpermitted `/kits` shows no kits and `/kit starter` is refused without delivering items or recording a row in `allium_kit_usages`.
   - Granting `allium.kits.starter` reveals `starter` in `/kits` and `/kit`, and `/kit starter` delivers the exact defined items (`STONE_SWORD 1 unbreaking:1`, `STONE_PICKAXE 1 efficiency:1`, `COOKED_BEEF 16`), records `claim_count = 1` in `allium_kit_usages`, deletes the `allium_kit_deliveries` row, and removes the temporary `allium:kit_receipts` tag.
   - An immediate second `/kit starter` is refused on cooldown without delivering items or incrementing `claim_count`.
   - Granting `allium.kit.bypass.cooldown` allows claiming during the active cooldown.
   - `/kit starter KitB` requires `allium.kit.bypass.cooldown` / `allium.kit.others` (`allium.kit.others` checked), delivers the kit to `KitB`, and records `KitB`'s usage.
2. **`one-time-and-paid-denial`**:
   - Claiming `oncekit` (`one-time: true`) delivers its items and records `one_time_claimed = true` in `allium_kit_usages`.
   - A second `/kit oncekit` is refused even when the player holds `allium.kit.bypass.cooldown`.
   - Claiming `vipkit` (`cost: 50.0`) is denied with the paid-kits-disabled message (`kit.paid-denied`), delivering zero items and recording zero usage rows.
3. **`full-inventory-and-cancelled-event`**:
   - With only 1 free inventory slot, claiming a 2-slot kit (`slowkit`) is refused all-or-nothing (`REFUSE` policy via `ItemDeliveryService#planMail`): zero items are added or dropped, and neither the cooldown nor `one_time_claimed` is consumed.
   - When a listener cancels `ItemGiveEvent`, the in-flight `CLAIMING` row is released and neither cooldown nor `one_time_claimed` is consumed.
4. **`provider-and-signed-items-createkit-delkit`**:
   - `/createkit customgear 120` captures a provider item (`nexo:ruby_sword`), a signed privileged Allium item (`allium:mob_disarmer`), and a vanilla stack (`minecraft:golden_apple 3`) from the admin's storage inventory into `plugins/Allium/kits/customgear.yml`.
   - When `KitB` claims `/kit customgear`, `ItemSpecParser` + `ItemLibrary` + `ItemService` mint the `nexo:ruby_sword` provider item and a fresh signed `allium:mob_disarmer` with a new `instance_uuid` and valid HMAC signature.
   - `/allium reload` preserves the `KitService` instance and reloads `customgear.yml`.
   - `/delkit customgear` removes the definition from memory and deletes `kits/customgear.yml`.
5. **`permissions-and-tab-completion`**:
   - `/kit <tab>` offers permitted free kits (`starter`) and hides unpermitted kits (`haltbefore`) and paid kits (`vipkit`).
   - `/delkit <tab>` offers all defined kit names to admins with `allium.delkit` and returns empty suggestions to unpermitted players.
6. **`disconnect-mid-delivery`**:
   - With `kit.claim.slow` armed, `KitA` runs `/kit slowkit` and disconnects while the `CLAIMING` row is in flight on the storage pool.
   - When the slow operation returns on the server thread, the generation/online check sees the player disconnected and calls `releaseClaim`: zero items are delivered, `allium_kit_deliveries` is cleared, and `allium_kit_usages` has no row for `slowkit`.
   - After reconnecting, `KitA` claims `/kit slowkit` cleanly in the new session.
7. **`crash-mid-delivery-recovery`**:
   - **`kit.claim.halt-before-deliver` (`Runtime.halt(77)` after `beginClaim` in SQL, before inventory mutation / `player.saveData()`):**
     - Stopped inspection confirms the `CLAIMING` row in `allium_kit_deliveries`, no row in `allium_kit_usages`, and no `K/...` receipt or kit items in `world/playerdata/<uuid>.dat`.
     - On restart and rejoin, `KitService` join recovery releases the `CLAIMING` row without touching `allium_kit_usages`, and `KitA` subsequently claims `/kit haltbefore` cleanly.
   - **`kit.claim.halt-after-deliver` (`Runtime.halt(77)` after `applyMail` + `allium:kit_receipts` + `player.saveData()`, before `finishClaim` in SQL):**
     - Stopped inspection confirms the `CLAIMING` row in `allium_kit_deliveries`, no row yet in `allium_kit_usages`, and both the 7 emeralds and the matching `K/<deliveryId>/<token>/haltafter` receipt in `world/playerdata/<uuid>.dat`.
     - On restart and rejoin, `KitService` verifies the saved receipt in `playerdata/<uuid>.dat` via `PlayerDataReceiptProof.verify`, calls `finishClaim` to record `one_time_claimed = true` in `allium_kit_usages` and `allium_kit_claim_receipts`, clears `allium:kit_receipts` from the player's PDC, and never delivers the items a second time. A subsequent `/kit haltafter` is refused as already claimed.

[Manifest and crash snapshots](evidence/s16-kits-manifest.json).
