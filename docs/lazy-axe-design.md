# Lazy Axe (`allium:fell_tree`): port design

The Lazy Axe fells a whole natural tree when one of its logs is broken. It is the legacy
`tree_axe`. This is the design for the tools slice after S08; no code exists yet. It follows the
[data-driven tool pattern](roadmap.md#data-driven-core-tool-architecture-pluginsalliumtoolsyml):
- **Behavior:** the Java behavior `allium:fell_tree` owns the transaction.
- **Definition:** `tools/lazy_axe.yml` supplies its settings, and is **disabled by default**.

## Legacy behavior (Allium 0.2.20a)

Read from `/mnt/storage/repos/Allium`: `items/impl/TreeAxeItem.java`, `TreeAxeManager.java`,
`listeners/items/TreeAxeListener.java` and `resources/customitems.yml`.

**The item:**
- A netherite axe named "Lazy Axe", with two lore lines.
- Nexo model `nexo:tree_axe` and `custom_model_data` 1001.
- Identified through the custom item registry, by `allium:custom_item_id` `tree_axe`.

**Trigger:** a `BlockBreakEvent` (skipped when already cancelled) with the axe in the main hand.
- **A log:** it is the start block.
- **Mangrove roots:** breaking roots or muddy roots searches up to 5 blocks up and down for a
  mangrove log to start from, and the root block is broken too.
- **Cancellation:** the original event is cancelled only when a fell will happen; otherwise the
  break stays vanilla.

**Families:**
- All overworld logs, including cherry and mangrove, and pale oak where the server knows it.
- Their stripped variants, bamboo blocks, and crimson and warped stems (stripped too).
- Wood and hyphae blocks are not included.
- Unknown materials are skipped by name, so older servers load.
- Stripped and unstripped logs of one species count as one type.

**"Natural tree" detection** (all four must pass):
1. **Leaves:** walk up the trunk from the start, at most 32 blocks. At each level, count leaves
   of any kind within a 5×5×5 cube. It needs 6 leaves, or 2 for a trunk of 2 blocks or less.
   **Leaf persistence is not checked**, so player-placed leaves count.
2. **Connected logs:** a layered search through all 26 neighbours, same species only. It stops
   at 100 logs or 32 layers, and goes **up and down**.
3. **Ground:** walk down through same-species logs, allowing at most 3 air gaps. The block below
   must be valid ground for the species:
   - overworld: dirt family, mycelium, snow;
   - nether: nylium or netherrack;
   - mangrove: roots, mud or dirt.
4. **No structure nearby:** none in a 7×7×7 cube around the start block.
   - **Counts as structure:** a fixed list, plus any material whose name contains STONE, BRICK,
     PLANK, SLAB, STAIR, FENCE, DOOR, GLASS, WOOL, TORCH, CHEST, BED, SIGN and so on.
   - **Exempt:** a long natural-blocks list (stone, ores, dirt, flowers and others).

**Breaking:**
- **Order:** the logs, sorted bottom up, one every 2 ticks.
- **Durability:** each log first adds 1 durability damage to the original axe. Unbreaking is
  ignored. At full damage it sends "Your axe is broken!" and that log is skipped; **the axe never
  actually breaks**.
- **Drops:** each log drops the block's drops for the axe, so Fortune and Silk Touch apply, and
  is then set to air.
- **Leaves** are left to decay.

**Gaps:**
- **Permission:** `allium.treeaxe.use` (default true) is declared but never checked.
- **Protection:** only the original break passes through other plugins. Every other log is set
  to air directly, so claims, WorldGuard and logging plugins never see it.
- **No recheck:** a delayed break does not check what is at the position now, so a block placed
  there in the meantime is broken.
- **Detection is weak:** leaf counting without persistence means a log build with player-placed
  leaves is felled. The structure scan sees only a cube around the start block.
- **No sneak toggle and no cooldown.** A tree over 100 logs is felled partially.

## Design

### Definition

`tools/lazy_axe.yml` ships with `enabled: false`.

```yaml
schema: 1
id: allium:lazy_axe              # legacy tree_axe stacks import to this identity
name: Lazy Axe
behavior: allium:fell_tree
enabled: false

appearance:
  default: allium:lazy_axe       # core pack item on a netherite_axe carrier (new allocation)
  aliases: []                    # e.g. nexo:tree_axe on SF

families: [overworld_logs, mangrove, cherry, pale_oak]   # stems: off by default, see Limits
stripped: true                   # a natural tree partly stripped by a player still fells

search:
  max-blocks: 256                # the cap; over it nothing beyond the first log is felled
  max-radius: 8                  # horizontal blocks from the start block
  max-height: 48                 # blocks above the start block
  min-natural-leaves: 4          # non-persistent leaves touching the found logs

breaking:
  blocks-per-tick: 6
  mode: break-as-player          # or direct; see the decision below
  stop-before-break: true        # stop with 1 durability left instead of breaking the axe

sneak: disables                  # disables | required | ignored
cooldown-millis: 0

permissions:
  use: allium.treeaxe.use        # the legacy node, default everyone

messages: {}
sounds: {}
```

Validation follows the tool pattern:
- **Rejected whole:** an unknown family, a cap or radius out of range, or an unknown key rejects
  the file, with the setting's path.
- **Versions:** a family a server version lacks (`pale_oak` before 1.21.4) is skipped with one
  info line. It is not an error, so one file works on every lane.

### Families, gated by version

| Family | Logs (each with `stripped_` variants when `stripped: true`) | Natural leaves | Valid ground |
| --- | --- | --- | --- |
| `overworld_logs` | oak, spruce, birch, jungle, acacia, dark oak, with `*_wood` | their leaves, azalea and flowering azalea leaves | dirt family, grass, podzol, coarse dirt, rooted dirt, mycelium, moss block, snow |
| `cherry` (1.20+) | cherry log and wood | cherry leaves | as overworld |
| `mangrove` (1.19+) | mangrove log and wood, **roots** (mangrove roots, muddy mangrove roots) | mangrove leaves | mud, muddy roots, roots, dirt |
| `pale_oak` (1.21.4+) | pale oak log and wood | pale oak leaves | as overworld, plus pale moss block |
| `stems` (off by default) | crimson and warped stems and hyphae | none (wart blocks have no persistence) | nylium, netherrack |

Bamboo blocks are crafted, not grown, so they are not a family.

**Never felled and natural attachments:**
- **Creaking hearts** are never broken. A connected trunk that contains one is not felled at
  all.
- **Natural attachments** are never broken and do not count as structure: bee nests, vines,
  cocoa, moss carpet, propagules, pale hanging moss, resin clumps, snow layers and glow lichen.

### Natural-tree detection

Detection runs at `MONITOR` on the original `BlockBreakEvent`, only when it was not cancelled.
At that point the block is still present. Allium never cancels or changes that event: the first
log always breaks exactly as vanilla breaks it.

1. **Start:**
   - **A log:** the broken block is a log of an enabled family.
   - **A mangrove root:** the nearest mangrove log within 5 blocks vertically is the start,
     kept from legacy.
2. **Search:**
   - **What it collects:** a breadth-first search over the 26 neighbours. It collects
     same-family logs (and roots for mangrove), up and down.
   - **Bounds:** `max-blocks`, `max-radius` and `max-height`.
   - **Loaded chunks only:** it reads only blocks in loaded chunks, and never loads one.
3. **Leaves required near the trunk:** at least `min-natural-leaves` leaves of the family must
   touch one of the found logs (6 neighbours). Each must report **not persistent** with distance
   1. Leaves a player places are persistent, so they never count.
4. **Ground:** the lowest found log stands on valid ground for its family.
5. **Structure guard:** no found log may touch a non-natural block: planks, slabs, stairs,
   fences, doors, trapdoors, glass, wool, carpet, concrete, bricks, beds, signs, chests,
   torches, lanterns and the like. Block tags are used where the version has them, with a
   name-based fallback.
   - **Adjacency, not a cube:** a house beside a tree is fine. A log wall built into a tree
     stops the fell.
6. **Over the cap:** a tree over `max-blocks` (or past the radius or height) is **not felled at
   all**; only the first log broke, as in vanilla. The player is told the tree is too large.
   Legacy felled the first 100 logs, which left floating logs.

If any step fails, only the vanilla break happens and no message is sent, except for a tree
over the cap.

### Breaking

The found logs are broken bottom up, `blocks-per-tick` per tick, through Allium's scheduler.
- **One job per player:** a second fell while one runs is refused.
- **What stops a job:** quitting, death, a world change, swapping the held item, the axe being
  dropped or broken, and plugin disable.

**Before each block:**
- **Player:** online and in the same world.
- **Axe:** still holding the same axe, compared by its instance UUID.
- **Block:** its chunk is still loaded, and the block is still the material found. Otherwise it
  is skipped.
- **Protection:** `ProtectionService` allows breaking it: the claim hooks plus
  `AlliumProtectionCheckEvent`. Otherwise it is skipped.

**Decision: synthetic break events.** `mode: break-as-player` (the default) breaks each log with
`Player#breakBlock` (Spigot and Paper, 1.17+). That is the server's own path for a player
breaking a block.

| | `break-as-player` (default) | `direct` |
| --- | --- | --- |
| **Events** | A real `BlockBreakEvent` and `BlockDropItemEvent` per log | None from Bukkit; one Allium event per fell |
| **Plugins that see each log** | Claims, WorldGuard, Towny, logging (CoreProtect), jobs, auto-pickup and telekinesis plugins | Only plugins that hook Allium |
| **Durability** | Vanilla's own damage per log, with Unbreaking | Allium applies 1 damage per log, with Unbreaking's 1/(level+1) chance, through the server's damage API where it exists, else by firing `PlayerItemDamageEvent` |
| **Drops and XP** | Vanilla loot tables with the axe's Fortune or Silk Touch, and block XP | `Block#getDrops(axe, player)`, with Fortune and Silk Touch; no XP |
| **Cancellation** | A cancelled event leaves that log | Only `ProtectionService` decides |

**Why `break-as-player` is the default:**
- **Compatibility:** logging and claims plugins must see every log, and this is the only mode
  where they do.
- **Its side effects are the same as chopping by hand, faster:**
  - **mcMMO** grants woodcutting XP and double drops per log. It skips logs its tracker marks
    as player-placed. If mcMMO's own Tree Feller is active, mcMMO may fell the tree first; Allium
    then finds those logs gone and skips them, so nothing breaks or drops twice.
  - **Jobs plugins** pay per log.
  - **Event-based anticheat checks** may cancel some breaks, and those logs stay.
- **Recursion guard:** Allium's own listener ignores the events it causes, through a guard set
  around each `breakBlock`, so a fell never starts another fell.

A server that wants none of these side effects sets `mode: direct` and accepts that logging and
claims plugins see only the first log.

**Durability details:**
- **`stop-before-break: true`:** the job stops when one more log could break the axe, so a
  netherite axe is never destroyed by felling. Legacy never broke the axe either.
- **`false`:** vanilla breaks the axe, and the job then stops because the held item changed.
- **Exempt:** creative mode and unbreakable axes take no damage.

**Fortune and Silk Touch** are handled by the loot tables in both modes. Logs and mangrove roots
drop themselves regardless; the enchantments matter for the blocks a family adds later (huge
mushroom blocks, for example). Leaves are never broken. Vanilla decays natural leaves once their
logs are gone.

### Sneaking, permissions and import

- **Sneaking** (`sneak`):
  - `disables` (default): sneaking breaks one log, as vanilla does.
  - `required`: only sneaking fells.
  - `ignored`: sneaking makes no difference.
- **Permission:** `allium.treeaxe.use` (the legacy node, default everyone) is now checked. A
  player without it breaks one log as usual.
- **Item:** a new core pack allocation, `lazy_axe` on a `netherite_axe` carrier, after the item
  renamer's range.
  - **Collision audit:** it runs for that carrier, since SF's Nexo `tree_axe` sits on a
    netherite axe.
  - **Art:** a texture of Allium's own is needed.
- **Legacy import:** a netherite axe with `allium:custom_item_id` `tree_axe` is adopted **in
  place**. Allium's stamps are added to the existing stack, so its enchantments (Efficiency,
  Unbreaking, Fortune, Mending), damage, custom name and repair cost are kept.
  - **Unlike the disarmer:** the disarmer's import builds a new stack. An axe's enchantments are
    the player's, so ItemService needs an "adopt this stack" operation.
  - **Not privileged:** the axe is a signed identity, but nothing about it is privileged.

### Limits

- **Logs carry no "placed by a player" flag** in vanilla. The guarantees therefore come from
  non-persistent leaves, the bounded search and the structure guard. A bare log build is never
  felled. But player-placed logs that touch a natural tree's logs, with no other building block
  touching them, are felled with the tree.
- **Leaves that look natural:** they are non-persistent but did not grow there. Leaves set by
  WorldEdit, debug sticks or worldgen-pasted schematics count as natural.
- **Trees without leaves:** a tree whose leaves have decayed or were removed breaks one log at a
  time.
- **Nether fungi:** wart blocks and shroomlights have no persistence flag, so `stems` detection
  can only check for touching wart blocks and a nylium base. A player could build that, which is
  why the family is off by default.
- **Unloaded chunks:** a tree reaching into an unloaded chunk is felled only within loaded
  chunks.
- **Folia:** not supported; the breaking job runs on the global server thread.

## Live gate (tools slice)

On Spigot 1.20.1 and Paper 26.3, the latter with GriefPrevention3D, with a real protocol bot,
like the disarmer gate:
- **Natural trees:** a grown oak, birch and spruce, plus cherry, and pale oak on 26.3.
  - Every log is gone and the leaves remain to decay.
  - The drops equal the logs, and the damage equals the logs with Unbreaking 0.
  - A fixture listener sees one `BlockBreakEvent` per log, and no fell is started recursively.
- **Must not fell:**
  - A **player-built log structure**, with and without player-placed (persistent) leaves.
  - A log wall built into a natural tree, which the structure guard stops.
  - A pale oak containing a creaking heart.
- **A tree crossing a claim border:** an admin claim over half the canopy. The logs inside are
  left, and the logs outside are felled.
- **The largest vanilla tree at the cap:** a mega jungle tree grown from four saplings. Its log
  count L is measured. With `max-blocks: L` it is felled completely; with `L - 1` only the first
  log breaks.
- **Settings:**
  - Sneaking with `disables` breaks only the first log.
  - `stop-before-break` with an almost broken axe stops early.
- **Interruptions:** swapping the item or quitting mid-job stops it, and nothing is broken
  afterwards.
- **Modes and import:**
  - `mode: direct` fires no break events after the first log.
  - A legacy `tree_axe` keeps its enchantments after import.
