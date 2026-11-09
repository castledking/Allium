# Phantom Obliterator (`allium:obliterate_phantoms`): port design

The Phantom Obliterator kills unnamed phantoms around its user. This is the design for the
tools slice after S08; no code exists yet. It follows the
[data-driven tool pattern](roadmap.md#data-driven-core-tool-architecture-pluginsalliumtoolsyml):
- **Behavior:** the Java behavior `allium:obliterate_phantoms` owns the transaction.
- **Definition:** `tools/phantom_obliterator.yml` supplies its settings. It is enabled by
  default, like the Mob Disarmer.

## Legacy behavior (Allium 0.2.20a)

Read from `/mnt/storage/repos/Allium`: `items/impl/PhantomObliteratorItem.java` and
`listeners/items/PhantomObliteratorListener.java`.

**The item:**
- A netherite sword named "Phantom Obliterator" (dark purple, bold), with lore showing its
  charges, refill time and uses.
- **Charge models:** four Nexo variant IDs, `phantom_obliterator` (3/3), `_2-3`, `_1-3` and
  `_0-3`. Each one sets `item_model` `nexo:<variant>` and `custom_model_data` 1008 (full) to 1011
  (empty).
- **State** is kept in four PDC keys: `allium:phantom_obliterator_charges` (int, 0..3),
  `_recharge_at` (epoch ms), `_broken` (boolean) and `_uses` (long). Each state write also
  rewrites `allium:custom_item_id` **and** `nexo:id` to the variant ID.
- **Identity:** either ID tag matching a variant, **or** a netherite sword with one of the four
  model data values.

**Trigger:** right-clicking air, in either hand.
- **Air only:** block clicks are excluded, so opening a chest never fires it.
- **Cancellation ignored:** the listener deliberately ignores cancellation from other plugins.

**On use:**
- **Broken:** a broken tool says so, rate-limited to one status message per second.
- **Bypass:** players with `allium.admin` get the full effect, and the tool is reset to 3/3
  without spending anything.
- **Recharge:** 3 charges. Spending the last starts a 120 s timer, after which the tool refills
  completely. A finished recharge is settled at use, at join, and by a scheduled refresh task
  per player.
- **Recharging:** says how long is left and plays a bass note (0.6, 0.6).
- **The scan:**
  - **Targets:** all entities within a 64-block cube from `getNearbyEntities`, filtered to a
    64-block sphere, that are phantoms with **no custom name**.
  - **Effects:** each target gets soul-fire-flame and reverse-portal particles, then
    `setHealth(0)`.
  - **If any died:** enchant particles at the player and the evoker cast-spell sound (1.0,
    0.65).
- **Empty scan:** reports "no unnamed phantoms within 64 blocks" with the charges, and spends
  nothing.
- **After a kill:**
  - **Spending:** uses go up by 1, then the break roll: 3% per charge, 5% on the last charge.
  - **On a break:** the tool is marked broken permanently, shown as the `_0-3` model with "This
    item is broken", and it never recharges.
  - **Otherwise:** one charge is spent, with a message for the count killed and the charges left.

**Permissions:** there is no use permission; any holder can use it.

**Gaps:**
- **Spoofable identity:** a netherite sword with model data 1008–1011 is accepted. The ID tags
  are plain PDC strings, and the tool writes `nexo:id` itself, impersonating Nexo items.
- **No protection or events:** `setHealth(0)` kills without a damage event. Protection, mob
  arenas and other plugins never see it, and no XP drops because the kill is not attributed to
  the player.
- **No recheck before committing,** and either hand fires it.
- **Ordering:** the state write comes after the kills, so a failed write gives a free use.
- **Bypass is tied to `allium.admin`** rather than its own node.

## Roadmap contract

From the [roadmap](roadmap.md#staple-tools-and-renaming):
- **Targets:** by default, unnamed phantoms within 64 blocks.
- **Empty scans** consume no charge.
- **Bounds:** searches are bounded to loaded entities and chunks, and protections and events are
  respected.
- **Config:** charges, recharge, radius and break chance are settings, not scattered constants.
- **Legacy import:** the three-charge state and depleted timestamps survive import.
- **Break presets:** the old break chances are available as a preset.
- **Commit:** the held stack is rechecked before committing, the tool is consumed once, and
  state survives model swaps.

Already in place:
- **Core pack:** `phantom_obliterator` is a **privileged**, signed core pack item, with default
  state `charges: 3`.
- **Carrier:** `blaze_rod`, with `custom_model_data` 2577030..2577039 planned.
- **Collision:** the S06c mixer audit reports the `blaze_rod` collision with SF's Nexo pack.

## Design

### Definition

```yaml
schema: 1
id: allium:phantom_obliterator
name: Phantom Obliterator
behavior: allium:obliterate_phantoms
enabled: true

appearance:
  default: allium:phantom_obliterator      # built-in blaze rod with charge-state models
  aliases: []
  charge-variants: {}                      # SF: nexo:phantom_obliterator, _2-3, _1-3, _0-3

charges:                                   # the same block, and the same rules, as allium:disarm
  max: 3
  recharge-seconds: 120
  break-chance: {preset: none}             # legacy: 3% per charge, 5% on the last (the old tool)

targets:
  radius: 64.0                             # sphere around the player; loaded entities only
  entity-types: [phantom]
  require-unnamed: true                    # named phantoms (name tags, plugins) are spared
  exclude-npcs: true
  max-targets: 64                          # per use; the nearest are taken first

kill: attributed                           # attributed | silent; see below

timing:
  click-guard-millis: 200
  status-cooldown-millis: 1000

sounds:
  obliterate: {sound: minecraft:entity.evoker.cast_spell, volume: 1.0, pitch: 0.65}
  recharging: {sound: minecraft:block.note_block.bass, volume: 0.6, pitch: 0.6}

particles:                                 # new slot type; names validated per server version
  target: [soul_fire_flame, reverse_portal]
  user: enchant

permissions:
  use: allium.phantomobliterator.use       # default everyone
  bypass: allium.phantomobliterator.bypass # default operators; replaces allium.admin

messages: {}
```

The charge state is the disarmer's model: `charges`, `recharge_at`, `broken` and `uses` on the
signed Allium state.
- **Shared code:** the `charges` block parser, `DisarmerCharges` and the visual-state mapping
  move into a shared tool-charges package, used by both behaviors.
- **New for tool definitions:** a `particles` slot type. Particle names differ between versions
  (`enchant` was `enchantment_table` before 1.20.5), so they are validated against the running
  server's registry.

### The transaction

Air clicks arrive pre-cancelled, so the listener reads the item-use result, as the disarmer's
listener does. Block clicks never fire. **Only the main hand acts**; legacy accepted either hand.

1. **Identify the tool:** the held stack's signed identity names this definition. The tool is
   privileged, so an unsigned or invalid MAC is ignored, and model data alone never identifies
   it.
2. **Use permission;** then the stack must be single, not broken, and either charged or bypassed.
3. **Click guard** (one committing click per player inside the window).
4. **Scan:**
   - **What it reads:** loaded entities only, within the radius sphere, of the configured types.
   - **Spared:** named targets (`require-unnamed`), NPC-marked ones, and dead or invalid ones.
   - **Cap:** the nearest `max-targets`.
   - **No targets:** the status message, and **nothing is spent**.
5. **Protection:** `AlliumProtectionCheckEvent` once for the use. With `kill: attributed`, the
   damage event per target is the per-target check.
6. **Recheck** that the same stack (by instance UUID and MAC) is still in hand, and the player is
   alive and online.
7. **Spend first:**
   - **The write:** consume one charge with the break roll, and write the state. If the write is
     rejected, nothing is killed, and the player is told.
   - **Why the order:** legacy killed first and wrote after, so a failed write was a free use.
     Here the kills cannot be undone, so the charge is taken before them.
8. **Kill:**
   - **`attributed` (default):** each target is damaged by the player for its full health, so
     `EntityDamageByEntityEvent` fires. Protection, arenas and other plugins can cancel a target,
     and kills drop XP and loot as player kills do.
   - **`silent`:** `setHealth(0)`, as legacy did, with no damage event, no XP and no
     attribution.
   - Kills are counted only for targets that actually died.
9. **If nothing died** (every target cancelled), the pre-use state is written back: a refund. If
   that write fails, it is logged once; the player then lost a charge, never gained one.
10. **Feedback:** the effects, sounds and messages: the count killed and charges left, the last
    charge with the refill time, or "broke while in use".

Bypass players skip spending and the break roll and record the use, exactly as the disarmer's
bypass does. They are not reset to full charges.

### Visuals and appearance

- **Built-in:** the blaze rod's charge state is a model selection from the core pack manifest
  (full, two thirds, one third, empty, broken), the same way the disarmer shows its state.
- **SF:** SF keeps its Nexo netherite-sword models through `charge-variants`, with the four
  variant IDs. Swapping keeps the charges, instance UUID and MAC.
- **No `nexo:id` writes:** Allium never writes `nexo:id` itself. Each variant is created by
  Nexo.
- **Lore is not stored:** charges are shown by model and status messages, as with the disarmer.
  A built-in lore line can be added later from state, never stored.

### Legacy import

The tool is privileged, so an import must not let a forged tag mint one.
- **What qualifies:** a stack is imported only when it is a netherite sword whose
  `allium:custom_item_id` **and** `nexo:id` both name the same variant, and it has the four
  legacy state keys or none.
- **Never imported:** stacks matched by model data alone. They are listed in the importer's
  report.
- **Values:** charges, recharge time, broken flag and uses are copied exactly as the disarmer's
  import does. Charges are clamped to the configured maximum, a depleted tool keeps its
  timestamp, and a broken tool stays broken.
- **In place:** the stack is adopted in place (the "adopt this stack" operation the Lazy Axe also
  needs), so its enchantments and name survive. It keeps its Nexo appearance through
  `charge-variants`.
- **Forgery:** survival players cannot write PDC. Creative players and operators can forge any
  item already, which is accepted. A server can set `import-legacy: false` to disable runtime
  import and use the offline importer only.

## Live gate (tools slice)

On Spigot 1.20.1 and Paper 26.3 (with GriefPrevention3D), with a real protocol bot. Fixture-spawned
phantoms are inside, on and beyond the radius. Some are named, and some are behind a cancelling
damage listener.
- **Kills:**
  - Exactly the unnamed phantoms inside the radius die, attributed to the bot.
  - Named phantoms, phantoms beyond the radius and cancelled targets survive.
  - One charge is spent, and the MAC is re-signed.
- **Nothing spent:**
  - **Empty scan:** nothing is spent, and the status message is sent.
  - **All targets cancelled:** nothing is spent.
  - **Rejected write:** with a fault armed on the state write, nothing dies and nothing is
    spent.
  - **Off hand and block click:** no effect.
- **Recharge and breaking:**
  - **Last charge:** spending it starts the timer, and using the tool while it recharges is
    refused with the bass note.
  - **After the timer:** the tool refills.
  - **Break preset:** a break with a forced roll leaves the tool permanently broken.
- **Bypass:** records the use without spending.
- **Persistence:** the state survives a restart.
- **Legacy import:** a legacy stack with both tags is imported with its exact state and its
  enchantments. A stack with model data alone is not imported.
