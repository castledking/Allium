# Curios custom enchantments: design note

Design for the enchantment module of the Curios addon (see the
[roadmap](roadmap.md#curios-addon-custom-items-enchants-and-equipment)). No code exists yet.
Enchant effects run on the shared Curios engine (triggers, conditions, actions, cooldowns). This
note covers what is specific to enchantments: how they are stored and shown, how they are
obtained, and the rules that keep them from being stripped or duplicated.

## Decisions

1. **The item-stored state is the source of truth on every lane, Spigot 1.20.1 through Paper
   26.3.**
   - **Storage:** a stack's Curios enchantments are a canonical list (`curios:<id>` → level,
     sorted) in its persistent data container (PDC), under one Curios key.
   - **Signing:** the list is MAC-signed with Allium's keyring, so an edited list is ignored and
     reported rather than obeyed.
   - **Needs addon API 1.1:** a signed state helper, so Curios never holds the secret.
   - **Any item:** the state can sit on vanilla items as well as Allium or provider items. It
     needs no Allium item identity.
2. **Lore is a rendering of that state, never an input.**
   - **The managed block:** Curios writes its lines as one block, at the top of the lore by
     default (`display.position`), and records the block's line count in the PDC. That way it
     can replace exactly its own lines.
   - **Other lore is untouched:** player-set lore and other plugins' lines are never touched.
     Renaming does not touch lore.
   - **Lore is never parsed back,** except by an explicit one-time importer that writes state.
3. **Registry-backed enchantments are an optional later layer** on Paper 1.21+, through Paper's
   registry bootstrap API or a datapack.
   - **A mirror only:** that layer mirrors the item state for native tooltips, glint and
     vanilla anvil handling. It never replaces the state.
   - **No unfreezing:** we will not unfreeze the server registry at runtime (the EcoEnchants
     approach). It depends on server internals that change between versions.
4. **Glint:** 1.20.5+ uses the glint override on the item meta. On 1.20.1–1.20.4 an enchanted
   look needs a real enchantment. The option is a hidden enchantment chosen to have no effect on
   that item type, and it is off by default.

## Definitions

One file per enchantment, `curios/enchants/<id>.yml`, validated at load time like Allium's tool
definitions. A file with any invalid setting is rejected whole, with path-specific
diagnostics.

| Setting | Meaning |
| --- | --- |
| `id` | `curios:<id>`; never changes once items carry it |
| `name`, `description` | Lang keys; display is per level with Roman numerals |
| `rarity` | A tier from `curios/rarities.yml` (colour, table and villager weight, cost factor) |
| `max-level` | Hard cap for every source. Higher levels already on items (admin-given) are kept, never raised |
| `applies-to` | Item groups (`swords`, `axes`, `pickaxes`, `shovels`, `hoes`, `bows`, `crossbows`, `tridents`, `maces`, `helmets`, `chestplates`, `leggings`, `boots`, `elytra`, `shields`, `fishing_rods`, `books`), explicit materials and provider items. Groups resolve per version; `maces` exists only on 1.21+ |
| `conflicts` | Curios and vanilla IDs (`minecraft:sharpness`). Conflicts are symmetric, and either side declaring one is enough |
| `curse` | Survives the grindstone, as vanilla curses do |
| `sources` | Per source: `table`, `villager`, `loot`, `books`, each with on/off, a weight and a level range |
| `limit` | Optional; counts toward `enchants.max-per-item` (an enchantment slot limit) |
| `levels` | Per level: trigger, conditions, actions, cooldown and chance, all on the shared engine |

## Rules: obtaining, combining and removing

Every rule is evaluated on the server when the player takes the result. A preview in an
inventory slot is never trusted.

### Anvil

- **Combinations:**
  - Item with a Curios book, item with the same item type, and book with book.
  - Inapplicable and conflicting enchantments are not transferred. If nothing would change (and
    there is no rename), there is no result.
- **Levels:** equal levels give level + 1, capped at `max-level`; unequal levels give the
  higher. `anvil.merge: max-only` disables the +1.
- **Cost:**
  - Vanilla's cost and prior-work penalty apply, plus Curios' cost per level × rarity factor.
  - `anvil.max-cost` clamps it or refuses ("Too expensive"), as configured.
  - The XP is charged once, at take.
- **Rename and repair:** a rename alone, or a repair with material (such as diamonds), copies
  the Curios state unchanged. A rename never strips it.
- **No duplication:**
  - The result is computed in the prepare event.
  - When the result is taken, by any route (click, shift-click, hotbar swap, drag or double
    click), Curios recomputes from the current inputs and the player's XP. A mismatch cancels
    the take and recomputes, so changing an input after the preview cannot produce an extra or
    stale result.
  - The inputs are consumed by vanilla, exactly once.
- **Version adapter:** the anvil view API changed in 1.21, and an adapter hides that.

### Grindstone

- **`grindstone: remove` (default):**
  - **What goes:** Curios enchantments are removed, except curses, as vanilla removes non-curse
    enchantments.
  - **XP refund:** `grindstone.refund-per-level` × rarity factor, granted once at take.
  - **Lore:** it is re-rendered.
- **`grindstone: keep`:** Curios state passes through unchanged.
- **Books:** a Curios book becomes a plain book. The grindstone never outputs a Curios book, so
  it can never be used to extract an enchantment.
- **Combining two items:** follows the same rule for both inputs.
- **Re-verification:** the same check at take as the anvil.

### Enchanting table

- **When:** Curios enchantments are rolled when the enchantment is committed, never in the
  preview, so there is nothing to reroll by reopening the table.
- **Chance and weight:** the table level picks a level band; `table.chance` and
  `table.reduction` set the chance of each further Curios enchantment; per-enchantment weights
  apply.
- **Respected:** `max-per-item`, `max-level`, conflicts with the vanilla enchantments just
  applied, and `applies-to`.
- **Books:** enchanting a book yields a book carrying the Curios state.
- **Hint:** the client's offer hint shows vanilla enchantments only. Curios additions are
  announced after commit (configurable).

### Villager trades

- **When:** when a librarian acquires a book trade, `villager.chance` replaces it with a Curios
  book (`villager.pass-through-chance` keeps the vanilla book).
- **Price:** `villager.price`, from level and rarity.
- **Existing trades** are never rewritten later.
- **Duplicates:** each purchase gives the trade's fixed result, as vanilla does, and villager
  stock limits apply. That is not a duplication path.
- **Wandering traders:** off by default.

### Loot and other sources

- **Loot:** off by default. When on, chest loot generation can add Curios books or enchantments
  with configured chances. Fishing treasure is a later option.
- **Commands:** `/curios enchant <id> <level>` (with permission) applies state through the same
  checks as the anvil. `max-level` is enforced unless the sender holds
  `curios.enchant.overmax`.

### Other ways an item changes

| Interaction | Rule |
| --- | --- |
| Smithing upgrade (diamond → netherite, trims) | State is carried over. Verified on 1.20 and 1.21+, which copy the item's data to the result. |
| Crafting-grid repair of two tools | Follows vanilla: enchantments other than curses are removed, Curios' included. |
| Hoppers, droppers, item frames, shulker boxes, ender chests | State travels with the stack; nothing to do. |
| Other plugins rewriting lore | Curios re-renders its block on its own events (held, inventory open, after an anvil or grindstone). It never parses foreign lore. |
| Creative copying | Vanilla behavior, out of scope. Creative players are trusted. |

## Coexistence with AdvancedEnchantments on SF

SF runs AdvancedEnchantments with a custom menu configuration. Rules until a migration is
decided:
- **Module off on SF:** Curios' enchantment module stays off there. Curios items and equipment
  slots can still run.
- **No import yet:** nothing is imported from AdvancedEnchantments. Its on-item format must
  first be confirmed from its public documentation and sampled from real items on a staging
  copy.
- **One owner per mechanic:** the two plugins must never both own anvil or grindstone handling
  for the same items.

## Reference plugins

**EcoEnchants** (2026.45, `/mnt/storage/devops/EcoEnchants`): open source under GPL-3.0. The
source was read for design only, and nothing is copied (Allium's licensing is separate).

| Area | What it does |
| --- | --- |
| **Storage and display** | Registers real enchantments by unfreezing the server registry through its library, and renders enchantment lore at display time. Has a converter for older lore-based enchantments. |
| **Effects** | Each enchantment's effects come from its effect library: trigger IDs (melee attack, bow attack, mine block, take damage, alt click, …), effect IDs, conditions and filters. |
| **Definitions** | `display-name`, `description`, `type`, `targets`, `conflicts`, `rarity`, `max-level`, `tradeable`, `discoverable`, `enchantable`, `effects` and `conditions`; 82 shipped enchantments. |
| **Sources** | Table, villager and loot settings, each with a book multiplier and a "reduction" factor for every further enchantment. |
| **Anvil and grindstone** | Anvil: cost exponent, maximum repair cost with clamping, an enchantment limit and the rework penalty. Grindstone support. Drag-and-drop books. |

**AdvancedEnchantments** (9.24.15): closed-source and premium. Only the shipped config files
were read; no classes were decompiled.

| Area | What it does |
| --- | --- |
| **Enchantments** | 182, in six tiers (simple, unique, elite, ultimate, legendary, fabled), with 1 to 10 levels (mostly 3 to 5). |
| **Levels** | Each level has an activation chance, a cooldown, conditions and an effect list. The most common effects are potion, increase damage, harm, particle and break block. Five enchantments use a disarm effect. |
| **Activation types** | Attack, defense, mining, shoot, static effect, right click, kill and others. Items are grouped as all swords, all armor and so on. |
| **Progression items** | Books with success and destroy chances; white scrolls (protect from destruction), black scrolls (extract), transmog and randomization scrolls; magic, secret and mystery dust that raises success; slot increasers, enchantment slots and soul gems with a souls currency; stat-tracker items. |
| **Menus** | Enchanter, tinkerer (recycle for XP or dust), alchemist (combine), gkits, armor sets and custom weapons. |
| **Per-mechanic config** | Per-vanilla-enchantment anvil limits, enchanting-table tier chances, and loot and villager trade chances. |

**Adopt:**
- **Progression:** rarity tiers; per-level chance and cooldown, on the engine; an enchantment
  slot limit per item; table, villager and loot weights with a reduction factor for each further
  enchantment.
- **Books:**
  - Optional apply success chance, off by default. A failed book is consumed and never damages
    the target item, unless `books.destroy-on-fail` is on.
  - Books can also be applied by drag-and-drop click, with the same checks as the anvil.
- **Later items:** extraction (a black-scroll equivalent) and protection (a white-scroll
  equivalent) as typed Curios items.
- **Disarm:** a disarm effect becomes the typed `disarm` action, which calls core's
  `allium:disarm`.

**Reject:**
- **Runtime registry unfreezing.**
- **Lore as the source of truth.**
- **Destroy-on-fail as a default.**
- **A souls currency in the first version.**
- **Free-form effect strings:** effects are typed and validated.
- **Menus before the GUI layer is settled:** the open InvUI 26.x risk.
