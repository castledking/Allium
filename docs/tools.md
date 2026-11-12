# Tool definitions (`plugins/Allium/tools/*.yml`)

Allium's staple tools are defined in YAML, one file per tool. A definition binds a signed item
identity to a core behavior and supplies that behavior's settings. The behavior owns how the
tool acts, and no setting can change those steps.

Allium reads every `.yml` file in `tools/` at startup, in file-name order. **Changes need a
restart.**

**Shipped defaults:**
- **First start:** it creates `tools/` with the shipped defaults.
- **Later updates:** each new default is installed once, the first time an Allium that ships it
  starts.
- **Deleting a default:** `tools/.defaults` remembers what was installed, so a default you delete
  stays deleted.

| Behavior | What it does | Shipped definition |
| --- | --- | --- |
| `allium:disarm` | Right-click a mob to take its equipment | `tools/mob_disarmer.yml` (enabled) |
| `allium:obliterate_phantoms` | Right-click air to kill the unnamed phantoms around you | `tools/phantom_obliterator.yml` (enabled) |
| `allium:fell_tree` | Break one log of a natural tree to fell the whole tree | `tools/lazy_axe.yml` (**disabled**: set `enabled: true`) |

## Validation

**A definition with any invalid setting is not loaded at all.** It is never half-loaded with
defaults standing in for bad values. The log names the file and each setting:

```text
[Allium] Tool definition tools/gate_disarmer.yml is invalid and was not loaded:
[Allium]   tools/gate_disarmer.yml: charges.max: must be between 1 and 1000 (got 0)
[Allium]   tools/gate_disarmer.yml: targets.radious: unknown setting; expected one of radius, excluded-mobs, exclude-npcs
```

**What is an error:**
- **Unknown or misspelled settings** are errors, so a typo cannot fall back to a default
  unnoticed.
- **The wrong type** is an error: text where a number belongs, or a decimal where a whole number
  belongs.
- **An unknown reference** is an error: a behavior, entity type, material, lang key or sound ID
  that does not exist.

**Between files:**
- **Unique identities and appearances:** no two definitions may share an `id`, and no two
  enabled definitions may share an appearance. An appearance that another identity already owns
  (a core pack item, an addon item) is refused.
- **The later file is rejected:** in a conflict, the file that sorts later is rejected. The
  others still load.

## Settings every definition has

| Setting | Default | Meaning |
| --- | --- | --- |
| `schema` | `1` | Definition format version. This Allium reads 1. |
| `id` | required | `allium:<name>` (`a-z`, `0-9`, `_`). The **signed identity** stamped on every stack. Never change it once tools exist. |
| `name` | the id | Used in log lines and diagnostics. |
| `behavior` | required | The core behavior, e.g. `allium:disarm`. |
| `enabled` | `true` | `false` keeps the definition valid but unloaded. Its stacks do nothing until it is enabled again. |
| `item` | none | A built-in item for an `id` that is not a core pack item: `material` (a vanilla item, required), `name`, `custom-model-data`. Core pack tools (`allium:mob_disarmer`) bring their own item, and this section is an error there. |
| `appearance.default` | `allium:<name>` | What new tools look like: the definition's own built-in item, or a provider item (`nexo:<id>`, `oraxen:<id>`, `craftengine:<namespace>:<id>`). Vanilla items and other tools' built-in items are refused. |
| `appearance.aliases` | `[]` | Provider items that act as this tool when their provider issues them. |
| `permissions.<slot>` | `allium.tool.<name>.<slot>` | Nodes for the behavior's permission slots. Nodes no `plugin.yml` declares are registered: `use` for everyone, `bypass` for operators. |
| `messages.<slot>` | the Mob Disarmer's entries | Lang keys for the behavior's message slots. Each key must exist in the lang files. |
| `sounds.<slot>` | the behavior's default | A sound ID, `""` for none, or a section with `sound`, `volume` (0–10) and `pitch` (0.5–2). |

Core pack identities are reserved: `allium:mob_disarmer` binds only `allium:disarm`. The
handcuffs, the Phantom Obliterator and the item renamer cannot be claimed until their behaviors
exist.

## `allium:disarm`

| Setting | Default | Meaning |
| --- | --- | --- |
| `charges.max` | `3` | 1–1000. |
| `charges.recharge-seconds` | `120` | Spending the last charge starts one timer; the tool refills completely when it ends. |
| `charges.break-chance.preset` | `none` | `none` never breaks; `legacy` is 3% per spent charge and 5% on the final one (pre-recode Allium); `custom` uses the two values below. |
| `charges.break-chance.per-charge`, `final-charge` | `0.0` | 0–1, used only with `preset: custom`. A nonzero value with another preset is an error. |
| `appearance.charge-variants` | `{}` | Named sets mapping a charge count (`0`..`max`) or `broken` to a provider item. A tool shown as any item in a set is swapped to that set's entry for its new state, keeping its charges, instance UUID and MAC. An item can be in one set only. |
| `targets.radius` | `6.0` | 0–64 blocks between player and mob; 0 leaves reach to the server. |
| `targets.excluded-mobs` | `[ender_dragon, wither, elder_guardian, warden]` | Entity types (validated against the server). Players and armor stands can never be disarmed. |
| `targets.exclude-npcs` | `true` | Skip entities other plugins mark as NPCs. |
| `disarm.drop-rule` | `slot-chance` | `slot-chance`: each removed piece rolls its slot's own drop chance once (villager gear and an enderman's block always drop). `always`: every removed piece drops. |
| `disarm.pickup-block-seconds` | `120` | How long a disarmed mob cannot pick items up. |
| `timing.click-guard-millis` | `200` | A second charge-spending click from one player within this window is ignored (0–10000). |
| `timing.status-cooldown-millis` | `1000` | Minimum time between charge status messages from right-clicking air. |
| `sounds.disarm` | `minecraft:item.shield.break`, 1.0, 1.2 | Played at the mob. |
| `permissions.use`, `permissions.bypass` | see `mob_disarmer.yml` | `bypass` disarms without spending charges or breaking. |

**Message slots:**
- **Error:** `no-permission`.
- **Disarming:** `disarmed`, `disarmed-bypass`, `last-charge`, `broke`.
- **State:** `broken`, `recharging`, `status`, `status-bypass`.
- **Refusals:** `player-target`, `excluded`, `too-far`, `protected`, `protected-reason`,
  `single-item`, `update-failed`.

### What the behavior owns

A definition cannot change these steps:
- Only the main hand acts.
- The tool, target, reach and charges are checked first. Then the click guard, then protection
  (the claim hooks and `AlliumProtectionCheckEvent`).
- Before committing, the held stack and target are re-read.
- Every removable piece is taken off the mob before anything drops. Each piece's drop is decided
  once, and the slot's drop chance is then zeroed.
- If the tool's state write is rejected, the equipment is put back and nothing drops or is spent.
- A disarm that removes nothing spends nothing.

### Several disarmers

Any number of definitions can bind `allium:disarm`, for example a second tool with 5 charges and
a 10-block radius.
- **Separate state:** each has its own identity, charges, permissions, messages, click guard and
  status timer. Using one never touches another's stacks.
- **Pre-recode stacks** are imported only as `allium:mob_disarmer`. They stay untouched while
  that definition is not loaded.

## `allium:obliterate_phantoms`

The Phantom Obliterator: right-click air with the tool in the main hand.
- **Privileged:** only a stack Allium signed acts. A phantom membrane with the right model data and
  name, or a signed tool whose state was edited, does nothing.

| Setting | Default | Meaning |
| --- | --- | --- |
| `charges.*` | 3 charges, 120 s, `preset: none` | As for `allium:disarm`; `legacy` is the old 3% / 5% break chance. |
| `appearance.charge-variants` | `{}` | As for `allium:disarm`, e.g. SF's four Nexo models. |
| `targets.radius` | `64.0` | A sphere around the player, 1–128 blocks. Only entities in loaded chunks are seen; a use never loads a chunk. |
| `targets.entity-types` | `[phantom]` | What counts as a target (players never). |
| `targets.require-unnamed` | `true` | Spare named mobs (name tags, plugins). |
| `targets.exclude-npcs` | `true` | Spare entities other plugins mark as NPCs. |
| `targets.max-targets` | `64` | At most this many per use, the nearest first. |
| `kill` | `attributed` | `attributed`: each target is damaged by the player. Protection, arenas and other plugins can spare a target, and loot and experience drop as from a player kill. `silent`: health is set to 0, as before the recode; no damage event fires. |
| `timing.click-guard-millis`, `timing.status-cooldown-millis` | `200`, `1000` | As for `allium:disarm`. |
| `legacy.import` | `true` | Adopt pre-recode Phantom Obliterators in place at first use (see below). |
| `sounds.obliterate`, `sounds.recharging` | evoker cast spell (1.0, 0.65); note block bass (0.6, 0.6) | Played to the player. |
| `particles.target`, `particles.user` | `[soul_fire_flame, reverse_portal]`, `[enchant]` | Vanilla particle names, checked against the server. `[]` for none. Particles that need extra data (colour, block) are refused. |
| `permissions.use`, `permissions.bypass` | `allium.phantomobliterator.use`, `.bypass` | `bypass` obliterates without spending charges or breaking. |

**Message slots:**
- **Errors:** `no-permission`, `single-item`, `update-failed`.
- **Results:** `obliterated`, `obliterated-bypass`, `nothing-in-range`, `protected`.
- **Charges:** `last-charge`, `broke`, `broken`, `recharging`.

### What the behavior owns

1. Only the main hand acts, and only on air. Clicking a block does nothing.
2. **Checks:** the permission, a single stack, broken or recharging, and the click guard.
3. **The scan:** loaded entities inside the sphere, of the configured types, minus named ones and
   NPCs. **Protection is asked for every target**: the claim hooks, then
   `AlliumProtectionCheckEvent`.
4. No target left: it says so and spends nothing.
5. The held stack is read again, since protection listeners run arbitrary code. If it changed,
   nothing happens.
6. **Spend first:** the consumed charge is written before anything dies. If the write is
   rejected, nothing is killed.
7. **Kill:** if every target was spared (for example by a damage listener), the old state is
   written back, so no charge is lost for nothing.

### Pre-recode tools

A pre-recode Phantom Obliterator is adopted in place the first time it is used **while the
adoption window is open**: a netherite sword whose `allium:custom_item_id` **and** `nexo:id` name
the same variant.
- **The adoption window:** the Obliterator is privileged, so a tag alone must not mint one (anyone
  in creative mode can write those tags). Adoption at first use happens only while an admin has
  opened the window with `/allium adopt-window <duration>` (up to `1h`, e.g. `10m`; `off` closes
  it, and no argument shows what is left).
  - **Closed by default:** it is closed at every start.
  - **Who can open it:** its permission, `allium.adoptwindow`, is granted to no one by default
    (not even ops), so only the console can open it unless an admin grants it.
  - **Logging:** opening and closing it are logged, and so is every adoption, with the player.
  - **Open it briefly, only during the migration**, while players bring out their old tools.
    While it is open, a forged tag on a netherite sword is adopted like a real one.
  - **Outside the window,** a legacy Obliterator does nothing and stays as it is; Allium logs once
    that it was refused.
  - **No offline importer yet:** Allium has no offline importer for item data yet, so the window
    is the only way in.
- **What it keeps:** its charges, recharge time, broken flag and uses, plus its enchantments and
  name.
- **When Nexo is installed:** a stack Nexo recognizes keeps its Nexo appearance.
- **Never imported:** a stack matched only by model data, or with just one of the two IDs. It does
  nothing, and the log says why.

### Carrier

The built-in item is a **phantom membrane** (`custom_model_data` 2577030–2577039, art not shipped
yet). It was a blaze rod until S10, but SF's Nexo pack overrides `items/blaze_rod.json`. No
readable SF pack overrides the phantom membrane, and it has no right-click use.

## `allium:fell_tree`

The Lazy Axe: with the axe in your main hand, break one log of a natural tree and Allium fells the
rest of it, bottom up, over the next ticks. The shipped `tools/lazy_axe.yml` is **disabled**; set
`enabled: true` and restart. The design and its reasoning are in
[lazy-axe-design.md](lazy-axe-design.md).

| Setting | Default | Meaning |
| --- | --- | --- |
| `families` | `overworld_logs, mangrove, cherry, pale_oak` | Which trees it fells. `stems` (nether fungi) is off by default: a player could build one. |
| `stripped` | `true` | Stripped logs and wood count as part of the tree. |
| `search.max-blocks` | `256` (1–2048) | A tree with more logs than this is not felled at all; only the log you broke breaks. The largest vanilla tree is a mega jungle; the gate's had 55–105 logs. |
| `search.max-radius` | `8` | How far from the broken log, horizontally, a log may be. |
| `search.max-height` | `48` | How far above or below it. |
| `search.min-natural-leaves` | `4` | Natural (non-persistent) leaves that must touch the logs. Leaves a player places are persistent, so a log build never fells. |
| `breaking.blocks-per-tick` | `6` (1–64) | The per-tick budget: at most this many logs break in one server tick. |
| `breaking.mode` | `break-as-player` | Or `direct`; see below. |
| `breaking.stop-before-break` | `true` | Stop with 1 durability left instead of breaking the axe. With `false` the axe can break on a log, and the fell stops there. |
| `sneak` | `disables` | `disables`: sneaking breaks one log, as vanilla does. `required`: only sneaking fells. `ignored`: no difference. |
| `cooldown-millis` | `0` | Time between fells per player. |
| `permissions.use` | `allium.treeaxe.use` | The pre-recode node, default everyone. Without it, one log breaks as usual. |
| `legacy.import` | `true` | Adopt pre-recode `tree_axe` axes in place (see below). |
| `messages.too-large`, `busy`, `stopped-axe` | `tool.lazy-axe.*` | Lang keys. |

### What the behavior owns

1. **The first log** is broken by vanilla, as always. Allium watches that break at `MONITOR` and
   never cancels it.
2. **Detection:** the log must belong to a natural tree:
   - logs of the same species, joined through their 26 neighbours, within the search bounds, in
     loaded chunks only;
   - natural leaves touching them;
   - the lowest log on the family's ground;
   - no creaking heart in the trunk;
   - no log touching a building block (planks, slabs, stairs, fences, doors, glass, ...).
3. **One fell per player at a time.** Breaking a log while your fell runs says `busy`.
4. **`LazyAxeFellEvent`** fires once per fell, before any other log breaks. It is cancellable, and
   cancelling it leaves the tree standing.
5. **Paced breaking:** bottom up, at most `blocks-per-tick` logs per tick. Before each log, Allium
   rechecks:
   - the chunk is still loaded, and the block is still a log of the tree's species;
   - `ProtectionService` (claims and `AlliumProtectionCheckEvent`) lets the player break it.
     A log it refuses is left; the others still fall. A tree across a claim border is felled up
     to the border.
6. **The fell stops** when:
   - the player no longer holds the same axe (it changed slot, moved or broke);
   - the player quits, dies or changes world;
   - any chunk the tree touches unloads;
   - `stop-before-break` keeps the last durability point.

### The re-entry guard

In `break-as-player` mode each log is broken with `Player#breakBlock`, which fires a real
`BlockBreakEvent`, into Allium's own listener too. While Allium breaks a log for a player, that
player is marked as felling, and a break event for them is never the start of another fell. A
fell therefore never starts a second fell, however many logs it breaks.

### `break-as-player` and `direct`

| | `break-as-player` (default) | `direct` |
| --- | --- | --- |
| **Events per log** | A real `BlockBreakEvent` (and drop event) for every log | None: only the first log's own event, plus one `LazyAxeFellEvent` |
| **Logging and claims plugins** | See every log (CoreProtect, claims, jobs, mcMMO) | **See only the first log.** CoreProtect and other block loggers do not record the other logs, so a fell in `direct` mode cannot be looked up or rolled back with them. |
| **Durability** | Vanilla's own, per log, with Unbreaking | 1 per log, with Unbreaking's chance |
| **Drops** | Vanilla's, with the axe's enchantments | `Block#breakNaturally(axe)`, with the axe's enchantments |

Use `direct` only on a server with no block logging you rely on. Protection is still asked per
log in both modes.

### Pre-recode tools

A netherite axe whose `allium:custom_item_id` is `tree_axe` is adopted **in place** the first time
it breaks a block. It keeps everything it carries: its enchantments (Efficiency, Unbreaking,
Fortune, Mending), damage, custom name and repair cost. The axe is not privileged, so no adoption
window is needed. The log records each import.

### Limits

- **No "placed by a player" flag on logs:** player-placed logs that touch a natural tree's logs,
  with nothing else touching them, fell with the tree.
- **False negatives from the structure guard:** a tree with any log touching a building block
  (planted flush against a wall, or used as a post) is never felled. Chop it by hand.
- **Leaves that look natural:** leaves set by WorldEdit or pasted schematics are non-persistent,
  so they count as natural.
- **Folia** is not supported: the fell runs on the server thread.

### Carrier

`lazy_axe` is a **netherite axe** (`custom_model_data` 2577050–2577059, art not shipped yet). SF's
Nexo pack overrides the netherite axe model, so this carrier is audited before the art ships.

## Carriers are not materials

An Allium tool is never used up as a material:
- **Blocked:**
  - crafting, including the repair-combine recipe, at a crafting table or a crafter;
  - furnace, blast furnace and smoker smelting and fuel;
  - brewing;
  - the anvil's second (material) slot;
  - smithing;
  - the grindstone;
  - villager trades;
  - hoppers and other automated transfers into those stations.
- **Still allowed:**
  - repairing, renaming or applying a book to a tool in the anvil's **first** slot;
  - enchanting it on an enchanting table.
- **What counts as a tool:** only a stack carrying an Allium behavior, or a pre-recode tool ID
  (`tree_axe`, `phantom_obliterator*`, `mob_disarmer*`). Plain items, and other plugins' custom
  items on the same materials, are unaffected.

## Upgrading from an older Allium

Before tool definitions existed, the Mob Disarmer was configured in `config.yml` under
`tools.mob-disarmer`.
- **The first start:** on the first start without a `tools/` folder, Allium writes those values
  into `tools/mob_disarmer.yml` and logs how many settings it moved.
- **Unmoved values:** old values are moved as they are. A value that was invalid then makes the
  definition invalid now, and the log says which.
- **The old section** is no longer read. Allium warns while it is still present.

| Old key | New setting |
| --- | --- |
| `appearance`, `aliases`, `charge-variants` | `appearance.default`, `appearance.aliases`, `appearance.charge-variants` |
| `max-charges`, `recharge-seconds`, `break-chance.*` | `charges.max`, `charges.recharge-seconds`, `charges.break-chance.*` |
| `radius`, `excluded-mobs`, `exclude-npcs` | `targets.*` |
| `drop-rule`, `pickup-block-seconds` | `disarm.*` |
| `click-guard-millis`, `status-cooldown-millis` | `timing.*` |
| `sound` | `sounds.disarm.sound` |

**Existing stacks:** they keep working unchanged: the same identity, state and MACs.

**Deleting a definition:** deleting `tools/mob_disarmer.yml` removes the tool. Its stacks stay
in inventories, inert, until a definition with that `id` is loaded again. A shipped default is
installed only once (see `tools/.defaults`), so a deleted one does not come back.
