# S16 kits live results

**PASS**.

- **Spigot 1.20.1 / Java 17 (SQLite, test `ItemProviderAdapter` stub for `nexo`):** 7/7 cases and **65/65** checks passed; 0 failures. Summary: `/mnt/storage/.allium-validation/20261114/s16-kits/runs/spigot-1.20.1-s16-f3/summary.json`.
- **Paper 26.3-141 / Java 25 (SQLite, test `ItemProviderAdapter` stub for `nexo`):** 7/7 cases and **65/65** checks passed; 0 failures. Summary: `/mnt/storage/.allium-validation/20261114/s16-kits/runs/paper-26.3-s16-f3/summary.json`.
- **Two-server Spigot 1.20.1 / Java 17 (`shared-mysql`, MariaDB 11.4):** 1/1 case and **11/11** checks passed; 0 failures. Summary: `/mnt/storage/.allium-validation/20261114/s16-kits/runs/shared-mysql-s16-f3/summary.json`.

Frozen `build/libs/Allium.jar` SHA-256: `6fef182ce7495598c8858dc717e36c668c7ca50852c5e99aff634c55b5d89166`.

All three suites ran against identical frozen production, helper, and client source files on owned loopback ports `25760`–`25762`, with all server, client, and database processes shut down cleanly after execution.

## Provider Adapter Per-Lane Disclosure

- **Spigot 1.20.1:** used `KitGate`'s test `ItemProviderAdapter` stub (`nexo:ruby_sword`), **not** the real Nexo plugin jar (the supplied Nexo runtime requires Paper APIs and cannot load on Spigot 1.20.1).
- **Paper 26.3:** also used `KitGate`'s test `ItemProviderAdapter` stub (`nexo:ruby_sword`) so both single-server kit lanes run an identical fixture; real Nexo, CraftEngine, and Oraxen runtimes remain covered on Paper 1.21.8 in the S04/S05/S06a/S10 suites.

## Verified Cases (15 cases / 141 live checks total)

1. **`claim-cooldown-and-others` (Spigot 1.20.1 & Paper 26.3)**:
   - Unpermitted `/kits` shows no kits and `/kit starter` is refused without delivering items or recording a row in `allium_kit_usages`.
   - Granting parent wildcard `allium.kits.*` exposes all free kits (`starter`, `oncekit`, `slowkit`, `haltbefore`, `haltafter`); revoking `allium.kits.*` removes access again.
   - Granting `allium.kits.starter` reveals `starter` in `/kits` and `/kit`, and `/kit starter` delivers the exact defined items (`STONE_SWORD 1 unbreaking:1`, `STONE_PICKAXE 1 efficiency:1`, `COOKED_BEEF 16`), records `claim_count = 1` in `allium_kit_usages`, deletes the `allium_kit_deliveries` row, and removes the temporary `allium:kit_receipts` tag.
   - An immediate second `/kit starter` is refused on cooldown without delivering items or incrementing `claim_count`.
   - Granting `allium.kit.bypass.cooldown` allows claiming during the active cooldown.
   - `/kit starter KitB` is refused when the sender lacks `allium.kit.others` or lacks `allium.kits.starter`; once the sender holds `allium.kit`, `allium.kit.others`, and `allium.kits.starter`, it delivers `starter` to `KitB` (who does not hold `allium.kits.starter`), bypasses the sender's cooldown, and records `claim_count = 1` on `KitB` (`target.getUniqueId()`) while leaving `KitA`'s `claim_count` at `2`.
2. **`one-time-and-paid-denial` (Spigot 1.20.1 & Paper 26.3)**:
   - Claiming `oncekit` (`one-time: true`) delivers its items and records `one_time_claimed = true` in `allium_kit_usages`.
   - A second `/kit oncekit` is refused even when the player holds `allium.kit.bypass.cooldown`.
   - Claiming `vipkit` (`cost: '50.00'`) is denied with the paid-kits-disabled message (`kit.paid-denied`), delivering zero items and recording zero usage rows.
3. **`full-inventory-and-cancelled-event` (Spigot 1.20.1 & Paper 26.3)**:
   - With only 1 free inventory slot, claiming a 2-slot kit (`slowkit`) is refused all-or-nothing (`REFUSE` policy via `ItemDeliveryService#planMail`): zero items are added or dropped, and neither the cooldown nor `one_time_claimed` is consumed.
   - When a listener cancels `ItemGiveEvent`, the in-flight `CLAIMING` row is released and neither cooldown nor `one_time_claimed` is consumed.
4. **`provider-and-signed-items-createkit-delkit` (Spigot 1.20.1 & Paper 26.3)**:
   - `/createkit ../x` is rejected by `KitNames.validate` (`^[a-z0-9_-]{1,32}$`).
   - With a written book in slot `3`, `/createkit lossykit` is refused naming slot `3` and the unsupported `written book data`, and no `kits/lossykit.yml` is written.
   - With a test-adapter provider item (`nexo:ruby_sword`), a privileged signed Allium item (`allium:phantom_obliterator`), and a vanilla stack (`minecraft:golden_apple 3`), `/createkit customgear 120` is refused when the creator lacks `allium.give.privileged` and succeeds once `allium.give.privileged` is granted, writing `cost: '0.00'` as decimal text.
   - When `KitB` claims `/kit customgear`, `ItemSpecParser` + `ItemLibrary` + `ItemService` mint the `nexo:ruby_sword` test-adapter item and a fresh signed `allium:phantom_obliterator` with a new `instance_uuid` and valid HMAC signature.
   - `/allium reload` preserves the `KitService` instance and reloads `customgear.yml`.
   - `/delkit customgear` removes the definition from memory and deletes `kits/customgear.yml`.
5. **`permissions-and-tab-completion` (Spigot 1.20.1 & Paper 26.3)**:
   - `/kit <tab>` offers permitted free kits (`starter`) and hides unpermitted kits (`haltbefore`) and paid kits (`vipkit`).
   - `/delkit <tab>` offers all defined kit names to admins with `allium.delkit` and returns empty suggestions to unpermitted players.
6. **`disconnect-mid-delivery` (Spigot 1.20.1 & Paper 26.3)**:
   - With `kit.claim.slow` armed, `KitA` runs `/kit slowkit` and disconnects while the `CLAIMING` row is in flight on the storage pool.
   - When the slow operation returns on the server thread, the generation/online check sees the player disconnected and calls `releaseClaim`: zero items are delivered, `allium_kit_deliveries` is cleared, and `allium_kit_usages` has no row for `slowkit`.
   - After reconnecting, `KitA` claims `/kit slowkit` cleanly in the new session.
7. **`crash-mid-delivery-recovery` (Spigot 1.20.1 & Paper 26.3)**:
   - **`kit.claim.halt-before-deliver` (`Runtime.halt(77)` after `beginClaim` in SQL, before inventory mutation / `player.saveData()`):**
     - Stopped inspection confirms the `CLAIMING` row in `allium_kit_deliveries`, no row in `allium_kit_usages`, and no `K/...` receipt or kit items in `world/playerdata/<uuid>.dat`.
     - On restart and rejoin, `KitService` join recovery releases the `CLAIMING` row without touching `allium_kit_usages`, and `KitA` subsequently claims `/kit haltbefore` cleanly.
   - **`kit.claim.halt-after-deliver` (`Runtime.halt(77)` after `applyMail` + `allium:kit_receipts` + `player.saveData()`, before `finishClaim` in SQL):**
     - Stopped inspection confirms the `CLAIMING` row in `allium_kit_deliveries`, no row yet in `allium_kit_usages`, and both the 7 emeralds and the matching `K/<deliveryId>/<token>/haltafter` receipt in `world/playerdata/<uuid>.dat`.
     - On restart and rejoin, `KitService` verifies the saved receipt in `playerdata/<uuid>.dat` via `PlayerDataReceiptProof.verify`, calls `finishClaim` to record `one_time_claimed = true` in `allium_kit_usages` and `allium_kit_claim_receipts`, clears `allium:kit_receipts` from the player's PDC, and never delivers the items a second time. A subsequent `/kit haltafter` is refused as already claimed.
8. **`cross-server-ownership-isolation` (`shared-mysql`, two Spigot 1.20.1 servers sharing MariaDB 11.4)**:
   - `server-a` (`25760`) halts at `kit.claim.halt-before-deliver`, leaving a `CLAIMING` row in `allium_kit_deliveries` with `claim_owner = 'server-a'`.
   - `KitA` connects to `server-b` (`25762`) sharing the same MySQL database: `server-b`'s join recovery (`claimingFor(..., 'server-b')`) ignores `server-a`'s `CLAIMING` row, and direct repository calls `releaseClaim(..., 'server-b')` and `finishClaim(..., 'server-b', ...)` return `false`, leaving `server-a`'s row untouched.
   - `server-a` restarts and `KitA` reconnects to `server-a`: `server-a` releases its own `CLAIMING` row on join recovery and subsequently delivers `/kit haltbefore` (`6x DIAMOND`) cleanly once.

## Limits

- **Database backends:** the full 7-case per-lane suite (`spigot` and `paper`) runs against SQLite (`WAL` mode); MySQL/MariaDB live coverage for S16 is scoped to the two-server `shared-mysql` recovery-ownership check (`cross-server-ownership-isolation`) plus repository unit tests.
- **Server lanes:** live checks ran on Spigot 1.20.1 (Java 17) and Paper 26.3-141 (Java 25); intermediate 1.20.6 / 1.21.8 lanes were not re-run in S16.
- **Provider adapters:** both single-server lanes used `KitGate`'s test `ItemProviderAdapter` stub for `nexo:ruby_sword` (since supplied Nexo requires Paper and cannot load on Spigot 1.20.1), not a third-party provider plugin jar.
- **Unit-only coverage:** exhaustive traversal strings (`a/b`, `a\b`, absolute paths, `.`, `..`, empty, >32 chars), non-book lossy item types (shulker box contents, bundle contents, attribute modifiers, custom model data, durability damage, item flags, repair cost, custom PDC, multi-effect potions), and legacy numeric `cost` load warnings are verified in JUnit (`KitServiceTest`, `KitRepositoryTest`).
- **Deferred features:** paid kit economy reservations (`cost > '0.00'`) and `[Kit]` signs remain deferred to their M3/M4 slices.

[Manifest and crash snapshots](evidence/s16-kits-manifest.json).
