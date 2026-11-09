# Fun Items design reference: ExecutableItems

Research input for the item part of the **Curios** addon (the former Fun Items; see the
[roadmap](roadmap.md#curios-addon-custom-items-enchants-and-equipment)). It compares
ExecutableItems Premium 7.26.11.2 with Allium's contract and recommends what to adopt as typed
actions and what to reject.

**Sources and method.** ExecutableItems is closed-source and paid. Only two kinds of source
were used:
- **Public documentation:** splugins.net, ExecutableItems' item features, and the shared SCore
  custom-commands pages.
- **Shipped example configs:** the 264 example item files and the `config.yml` inside the jar,
  read as data and tallied by key.

Nothing was decompiled, and no code or config text is copied here. Key names appear only where
a concept needs naming. Counts below are from the shipped examples.

## How ExecutableItems models an item

| Concept | What it is |
| --- | --- |
| **Item** | A YAML file: material, name, lore, model, enchantments, attributes, flags, `variables`, `usage`, `restrictions`, owner settings and a map of `activators`. |
| **Activator** | One trigger (`option`), its conditions, its commands, its cooldowns and its usage change. An item has any number. |
| **Triggers** | Over 100 event kinds. The examples mostly use a 1-tick loop (145), right click (93), block break (70), a custom launch (58), left click (40), any click (30), projectile launch and hit, equip/unequip (24 each), inventory click, entity click, walk, jump, sneak, sprint, death, fishing, consume and world change. |
| **Conditions** | Grouped by subject: player, world, item, entity, block, target and placeholder conditions, plus custom ones (for example a confirmation prompt). Each condition carries its own failure message. Placeholder conditions compare PlaceholderAPI values. |
| **Actions** | Command lists: player, entity, block and target commands. A line is either an SCore keyword (damage, effect, launch, around, mob_around, delay, loop, if, random, set_block, give_money, sudo and so on) or a plain server command. |
| **Who runs a command** | Plain commands run from the **console** by default. The `SUDO` prefix makes the player run one, and `SUDO_OP` makes the player OP for that one command. The examples lean heavily on console `execute` chains: about 3,700 `execute` lines and 1,200 `delaytick` lines across the 264 files. |
| **Cooldowns** | Per activator, a player cooldown and a global cooldown, in seconds or ticks, with a message. Options: pause while offline, pause while placeholder conditions fail, cancel the event while cooling down, and a vanilla hotbar overlay. Separately, an item-level vanilla `useCooldown` with a shared `cooldownGroup`. A permission bypasses cooldowns, and a premium option re-applies them to operators. |
| **Usage** | An integer on the stack: a use counter that acts as custom durability. Activators change it by `usageModification`; at 0 or below the item is deleted, and -1 means unlimited. Optional `usageLimit` and `usePerDay`. |
| **Variables** | Per-item values (text, number or list) that activators change and lore displays. |
| **Restrictions** | About 30 toggles that cancel vanilla interactions: anvil, crafting, enchanting, smithing, placing, dropping, hoppers, item frames, composters, lightning and cactus destruction, and others. |
| **Ownership** | An optional stored owner. Owner-only use, with an allowlist of activators anyone may use. |
| **Recognition** | Ordinary items can count as an ExecutableItems item by matching name, material or lore. |

## Compared with Allium's contract

| Topic | ExecutableItems | Allium (roadmap contract) | Recommendation |
| --- | --- | --- | --- |
| **Triggers** | 100+, including a per-tick loop | Right-click air/block/entity, left-click, sneak variants and consumption first | Adopt a typed, growing trigger set with explicit hands. Add equip/unequip, block break and hit triggers when a preset needs them. A per-tick loop becomes a bounded `while-held`/`while-equipped` interval trigger with a minimum period, never every tick by default. |
| **Conditions** | Large per-subject lists with per-condition messages; placeholder comparisons | Validated at load time | Adopt typed conditions (sneaking, in world/biome, has permission, health below, target type, block type, gamemode, held item), each with an optional message key. PlaceholderAPI comparisons stay optional and read-only. |
| **Actions** | Console command strings, keyword commands, `execute` chains | Typed player/target/location/hand context; allowlisted player/console commands | Adopt typed actions for the common keywords (message, sound, particle, potion effect, damage, heal/feed, launch projectile, velocity/dash, give item, set block with protection, drop item, cooldown change). **Reject command chains as the main model**: commands are an allowlisted escape hatch, off by default per definition. |
| **Core tool behaviors** | None (a disarm would be a command chain) | Specialized tools own their domain services | Expose `disarm`, `fell_tree` and `obliterate_phantoms` as typed actions that call the core behaviors. Never reimplement them as scripts. |
| **Privilege** | `SUDO_OP` grants OP for one command | Never temporarily OP a player | **Reject** anything that grants OP or permissions, even briefly. A console command runs only from a load-time allowlist with typed arguments. |
| **Cooldowns** | Player and global per activator, seconds or ticks, pause offline, hotbar overlay, bypass permission | Shared-group, player and item-instance scopes; overlay separate from the authoritative state | Adopt all three scopes plus pause-offline. The overlay is visual only; cooldown state lives server-side, keyed by scope. Bypass is an explicit permission per definition. |
| **Usage** | Stack counter; the item is deleted at 0 | Charge/use consumption follows a documented success outcome | Adopt usage as signed item state (like the disarmer's charges), spent only on the documented success. Deletion at 0 is a per-definition choice (`on-depleted: delete \| keep \| break`). |
| **Variables** | Free per-item values changed by commands | Not in the contract | Adopt later, narrowly: typed counters on the signed state, shown in lore (e.g. a kill counter). No free-form scripting variables. |
| **Restrictions** | ~30 cancel toggles | Not in the contract | Adopt as one validated `restrictions` list: anvil, craft, enchant, smithing, place, drop, consume, item frame, hopper, grindstone. These are plain cancellations of vanilla interactions. |
| **Ownership** | Stored owner, owner-only use | Not in the contract | Adopt as an optional signed `owner` state with owner-only use. It must never block a staff restore or an admin take. |
| **Recognition** | Name, material or lore matching | No vanilla-carrier false matches; identity is the signed Allium stamp or a provider ID | **Reject.** Name and lore are player-editable, so a renamed vanilla item would gain the behavior. Identity stays Allium's signed stamp or a bound provider item. |
| **Delays and loops** | `delay`, `delaytick`, `loop` in command lists | Bounded delays; delayed actions revalidate and are cancelled on lifecycle changes | Adopt a bounded `delay` step with a total cap per activation. Each delayed step rechecks the player, the held item and the target; quit, death, world change and reload cancel it. No unbounded loops. |
| **Cancelled events** | Optional "do not run if cancelled" per activator | Respect cancelled and protected interactions | The default is to respect cancellation; it is not an option. Air clicks read the item-use result, as the disarmer already does. |
| **Area effects** | `around` / `mob_around` keyword commands | Bounded searches | Adopt typed area targets with a radius cap, an entity-count cap, loaded chunks only, and protection checked per target. |

## Adopt

**Typed actions for the Curios engine:**
- **Feedback:** message (lang key), action bar, title, sound, particle.
- **Effects:** potion effect, heal, feed, absorption, damage, ignite or extinguish.
- **Movement:** velocity/dash/jump, launch projectile, bounded teleport through Allium's safe
  teleport service once M3 has it.
- **Items:** give item, drop item, damage or repair the held item.
- **World:** set or break a block (through protection), area targets with caps.
- **Cooldowns:** change a cooldown or the usage count.
- **Money:** give money through Vault, only when an economy is present.
- **Core behaviors:** `disarm`, `fell_tree` and `obliterate_phantoms`, as typed actions over the
  core behaviors.

**Concepts:**
- Activators with a trigger, conditions and an ordered action list.
- Cooldowns in three scopes, with pause-offline.
- Signed usage counts.
- An explicit restrictions list.
- An optional owner.
- One message key per condition.
- An item-level vanilla cooldown overlay.

## Reject

- **Command chains as the main action model:** console `execute`, `delaytick` and `loop` chains.
  They cannot be validated at load time or rolled back, and they run with console privilege.
- **Anything that grants permissions or OP:** `SUDO_OP`-style elevation, and permission-plugin
  commands from item actions.
- **Recognition by name, material or lore.**
- **Every-tick loops by default.**
- **Executable content from item metadata:** actions always come from server-owned definitions.
- **An in-game GUI editor in the first version:** YAML plus validation and `/curios reload`
  diagnostics come first.

## Open questions

- **PlaceholderAPI:** whether Curios conditions may read it at all, or only through an
  allowlist of placeholders.
- **Import:** whether to import SF's existing ExecutableItems items. No SF ExecutableItems data
  was inspected for this note.
