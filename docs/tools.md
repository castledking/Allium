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
| `allium:fell_tree` | Planned for S10, off by default; see the [design](lazy-axe-design.md) | — |

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

A pre-recode Phantom Obliterator is adopted in place the first time it is used: a netherite sword
whose `allium:custom_item_id` **and** `nexo:id` name the same variant.
- **What it keeps:** its charges, recharge time, broken flag and uses, plus its enchantments and
  name.
- **When Nexo is installed:** a stack Nexo recognizes keeps its Nexo appearance.
- **Never imported:** a stack matched only by model data, or with just one of the two IDs. It does
  nothing, and the log says why.

### Carrier

The built-in item is a **phantom membrane** (`custom_model_data` 2577030–2577039, art not shipped
yet). It was a blaze rod until S10, but SF's Nexo pack overrides `items/blaze_rod.json`. No
readable SF pack overrides the phantom membrane, and it has no right-click use.

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
