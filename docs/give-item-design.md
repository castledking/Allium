# Give and Item Library Design

Status: design specification aligned with [roadmap.md](roadmap.md) contracts.

## 1. Context: Legacy Allium vs. Essentials

### Legacy Allium (`codes.castled.allium.commands.Give`)
- **Brittle custom grammar:** Relied on naive `.split(";")` and `.split(",")` string slicing (`diamond_sword;sharpness:5;name:&6GodSword;1`), which breaks when modern Data Components contain commas or semicolons.
- **Hardcoded shorthands and prefixes:** Used custom prefixes (`ci:`, `custom:`, `ce:`) and hardcoded item aliases (`dhelmet`, `dboots`) mixed directly into command execution.
- **Selector disconnection:** Re-implemented a partial `@a`/`@p`/`@r`/`@s`/`@e` switch inside `Give.java` instead of using `Selectors.java`, dropping support for distance, limits, sorting, and permission checks.
- **Monolithic execution:** Coupled item parsing, metadata application, player resolution, and inventory insertion into one command executor, preventing kits, signs, rewards, and addons from reusing item construction cleanly.

### Essentials (`com.earth2me.essentials.commands.Commandgive` & `MetaItemStack`)
- **Readable space-separated tokens:** `/give <player> <item> [amount] [key:value...]` (`sharpness:5`, `name:&bExcalibur`, `lore:Line_1|Line_2`, `owner:Notch`).
- **Why raw component/NBT input breaks in modern versions:**
  - In Minecraft 1.20.5+, Mojang replaced item NBT tags (`{...}`) with Data Components (`[...]`).
  - Essentials passes raw `{...}` or `[...]` strings directly to `UnsafeValues.modifyItemStack(ItemStack, String)`.
  - Depending on the server version, `modifyItemStack` has shifting expectations around whether the item's `NamespacedKey` is prepended to the component block, causing syntax exceptions (`noMetaJson` / `noMetaNbtKill`).
- **No external provider contract:** Essentials has no built-in resolution or state model for Nexo, CraftEngine, Oraxen, or Allium custom behavior items.

---

## 2. Canonical Namespacing & Resolution Rules

All item references across commands, kits, signs, recipes, loot, and addons resolve through a single canonical syntax:

| Provider | Canonical Format | Example |
| --- | --- | --- |
| Vanilla | `minecraft:<id>` or bare `<id>` | `minecraft:diamond_sword`, `diamond_sword` |
| Allium (core & addons) | `allium:<id>` | `allium:handcuffs`, `allium:mob_disarmer` |
| Nexo | `nexo:<id>` | `nexo:mob_disarmer` |
| Oraxen | `oraxen:<id>` | `oraxen:item_renamer` |
| CraftEngine | `craftengine:<namespace>:<id>` | `craftengine:sf_ores:silver_ingot` |

### Strict Parsing & Legacy Rules
1. **First-Colon Split:** Provider detection splits at the **first colon only**. For `craftengine:sf_ores:silver_ingot`, the provider is `craftengine` and the provider-local identifier is `sf_ores:silver_ingot`, preserving CraftEngine's internal namespace.
2. **Bare Names are Vanilla Only:** An unprefixed identifier (e.g., `stone`, `mob_disarmer`) resolves strictly against the Vanilla material registry. Allium items always require the `allium:` prefix (`allium:mob_disarmer`).
3. **Never Fallback to Carrier or AIR:** A custom or provider item must never silently match or degrade to its vanilla carrier material or `Material.AIR`.
4. **Legacy Prefixes (`ce:`, `vanilla:`, `ci:`):**
   - `ci:` and `custom:` are **removed** from the runtime grammar; the legacy Allium importer converts them to `allium:<id>` with a migration report.
   - `ce:` and `vanilla:` are converted by the config/data importer into `craftengine:<namespace>:<id>` and `minecraft:<id>` (emitting a mapping report for any ambiguous namespace). At runtime, `ce:` may at most be accepted in a deprecated compatibility mode that logs an explicit warning.
5. **Legacy Shorthands (`dhelmet`, `dboots`, etc.):**
   - Hardcoded material shorthands are **not** built into the core `ItemParser`.
   - Instead, the legacy importer converts old config/kit references to canonical `minecraft:diamond_helmet` IDs. Optional command-level item aliases, if enabled by the server operator in config, live in a command-layer alias table that resolves to canonical `ItemRef` strings before invoking the library.

---

## 3. Behavior Identity vs. Appearance Provider (M1 Gate)

`ItemRef` strictly decouples **Allium behavior identity** (`behaviorId`) from the **appearance provider reference** (`provider` + `providerItemId`).

```text
ItemRef
 ├── appearance: ProviderKey (e.g. "nexo" -> "mob_disarmer", or "allium" -> "mob_disarmer")
 ├── behaviorId: Optional<NamespacedKey> (e.g. "allium:mob_disarmer")
 ├── amount: int
 └── modifiers: ItemModifiers (enchants, name, lore, skull, potion, rawComponents, state)
```

### How the Registry Binds Behavior and Appearance
1. **Behavior Registration:** Core tools (Handcuffs, Mob Disarmer, Phantom Obliterator, Item Renamer) and addons register their behavior logic under a canonical `behaviorId` (e.g., `allium:mob_disarmer`).
2. **Appearance Binding:** In configuration, the server operator binds `allium:mob_disarmer` to a visual source—by default the built-in Allium resource pack model (`allium:mob_disarmer`), or an external provider item (`nexo:mob_disarmer`, `oraxen:mob_disarmer`, `craftengine:sf_tools:mob_disarmer`).
3. **Unified State & Identification:**
   - When an item is created for `allium:mob_disarmer` or `nexo:mob_disarmer` (when bound), the `ItemService` constructs the visual stack via the configured provider adapter and stamps Allium's authoritative PDC keys (`allium:behavior_id = "allium:mob_disarmer"`, plus stateful data such as `allium:charges`).
   - When a player interacts with an item, listeners query `ItemService.identifyBehavior(ItemStack)`:
     - It checks Allium's server-owned PDC `behavior_id` (and verifies registered provider bindings).
     - Both `allium:mob_disarmer` and `nexo:mob_disarmer` resolve to the exact same `allium:mob_disarmer` behavior handler and charge state.
     - Display names, lore, and `CustomModelData` alone **never** grant privileged behavior.
     - Provider reloads/updates preserve Allium's PDC charge/state metadata while refreshing the visual carrier.

---

## 4. Provider Readiness States

External item plugins (especially Nexo) often load or rebuild their registries asynchronously during startup and reload. `ItemProviderRegistry.resolve(ProviderKey)` returns a sealed/typed `ResolutionResult` rather than a nullable `ItemStack`:

| Outcome | Meaning | Caller / Command Behavior |
| --- | --- | --- |
| `RESOLVED(ItemStack)` | Provider is ready and the item exists. | Proceed with modifiers and delivery. |
| `UNKNOWN_ID` | Provider is `READY`, and the ID is definitively absent. | Report unknown item ID to sender/diagnostics. |
| `PROVIDER_ABSENT` | The provider plugin is not installed or enabled. | Report that provider `<name>` is not installed. |
| `PROVIDER_LOADING` | The provider plugin is present but still initializing/reloading its registry. | Do **not** report "unknown item"; report that `<provider>` is still loading (or defer recipe/kit activation until the provider's ready event). |
| `PROVIDER_FAILED` | The provider encountered an initialization/adapter error. | Report provider error with diagnostic guidance. |

---

## 5. Grammar & Tokenizer Specification

### Positional Disambiguation
Command arguments follow a strict positional layout so there is never ambiguity between item IDs, quantities, and metadata tokens:

- **`/give`:** `/give <target> <item-spec> [amount] [tokens | flags...]`
- **`/i` (`/item`):** `/i <item-spec> [amount] [tokens | flags...]`

1. **`<target>`** (`/give` only): Player name or player selector (`@p`, `@a`, `@r`, `@s`, `@e[type=player,...]`).
2. **`<item-spec>`**: Single item reference or comma-separated batch of item references (`diamond_helmet,diamond_chestplate`), each optionally carrying inline vanilla component brackets `[...]` or legacy NBT `{...}` (on pre-1.20.5).
3. **`[amount]`**: Optional positive integer if the next argument is a pure integer. If omitted, defaults to 1 (or configured default stack size).
4. **`[tokens | flags...]`**: Remaining space-separated arguments are parsed as `key:value` human metadata tokens (e.g., `sharpness:5`, `name:&bExcalibur`, `lore:Line_1|Line_2`, `owner:Notch`, `color:#FF5500`, `unbreakable`) or command flags (`-e`, `-equip`).

### Bracket-, Brace-, and Quote-Aware Lexer
Component and NBT strings routinely contain commas, semicolons, colons, and spaces inside brackets or quotes:
- Example: `minecraft:diamond_sword[custom_name='{"text":"Blade, of Testing; Mk II"}',unbreakable={}]`
- The tokenizer tracks nesting depth for `[...]`, `{...}`, `'...'`, and `"..."` (with `\` escape handling):
  - Top-level `,` splits multi-item specs **only** when bracket `[]`, brace `{}`, and quote depths are all zero.
  - Spaces split command tokens **only** outside quoted or bracketed segments when parsing full spec strings.

### Legacy Semicolon Syntax (`item;meta;amount`)
- Semicolon-delimited inline specs (e.g., `stick;sharpness:5;1`) are **not** mixed silently into standard parsing.
- In persistent files (kits, rewards, signs), the legacy Allium importer converts semicolon specs into structured YAML or canonical space-separated definitions.
- For interactive commands or legacy console scripts, semicolon parsing is gated behind an explicit `legacy-semicolon-give-syntax: false` config option (or an explicit legacy parser entrypoint that logs a deprecation notice and splits on `;` only at nesting depth zero).

---

## 6. Version Handling (1.20 – 26.3)

### Separation of Human Tokens vs. Raw Component/NBT Input
- **Human Tokens (`sharpness:5`, `name:...`, `lore:...`, `owner:...`, `potion:...`, `color:...`, `unbreakable`, `cmd:...`, `flag:...`):**
  - Always applied through standard Bukkit `ItemMeta` APIs (`EnchantmentAdapter`, `SkullAdapter`, `PotionAdapter`, `ItemMeta`).
  - Never touches `UnsafeValues.modifyItemStack`. Works cleanly across 1.20–26.3.
- **Raw Bracket `[...]` or NBT `{...}` Input (`ComponentAdapter`):**
  - Used **only** when the caller explicitly supplies raw `[...]` component syntax or legacy `{...}` NBT syntax.
  - **Pre-1.20.5 lanes (1.20 – 1.20.4):** Data Components do not exist. If `[...]` is supplied, `ComponentAdapter` rejects it with a clear message (`"Data components [...] require Minecraft 1.20.5+"`). Raw `{...}` NBT is accepted only on these pre-1.20.5 lanes if supported by the server.
  - **1.20.5 – 26.3 lanes:** Raw `{...}` NBT is rejected with a clear message explaining that Minecraft 1.20.5+ uses `[...]` Data Components.
  - **Primary Parser Investigation:** On server versions exposing `Bukkit.getItemFactory().createItemStack(String)`, `ComponentAdapter` uses `ItemFactory#createItemStack` as the primary supported API for vanilla item + component strings, falling back to `UnsafeValues#modifyItemStack` only where necessary.
  - *Note:* Per-version `UnsafeValues#modifyItemStack` prefix quirks (such as whether `1.20.6–1.21.11` vs `26.1+` prepend the item key automatically) are **unverified until tested on the pinned server matrix** in S01/S04.

---

## 7. Delivery, Async Skulls, Overflow & Events

`ItemDeliveryService` handles all inventory mutations and returns `CompletableFuture<DeliveryOutcome>`.

### Asynchronous Preparation & Main-Thread Mutation
1. **Off-Thread Profile Resolution:** If an item spec includes `owner:<name>` and the profile is not in the local bounded cache, `SkullAdapter` resolves the profile asynchronously on Allium's bounded executor with an explicit timeout (e.g., 3 seconds).On timeout or lookup failure, it returns an explicit partial/warning state in `DeliveryOutcome` rather than blocking the server thread.
2. **Target Revalidation:** When the async stage completes and hops back to the server thread (or entity scheduler), `ItemDeliveryService` revalidates that the target `Player` is still online and valid before mutating inventory. If the player disconnected, the outcome records `TARGET_OFFLINE`.

### Cancellable `ItemGiveEvent`
- Immediately before inserting items on the server thread, `ItemDeliveryService` fires `ItemGiveEvent(CommandSender/Source, Player target, List<ItemStack> items, boolean equipRequested)`.
- `ItemGiveEvent` is `Cancellable`. If cancelled by a listener, no items are added or dropped, and `DeliveryOutcome` records `CANCELLED_BY_EVENT`.

### Equipment Flag (`-e` / `-equip`)
- When `-e` or `-equip` is passed, armor items matching an equipment slot (`HEAD`, `CHEST`, `LEGS`, `FEET`) check the target player's current armor slot:
  - If the slot is **empty (`AIR`)**, the item is equipped directly into that slot.
  - If the slot is **already occupied**, `-e` **never** overwrites or deletes the existing armor; the new item falls back to normal inventory insertion (and overflow policy).

### Configurable Overflow Policy
When a player's inventory cannot hold all remaining items, `ItemDeliveryService` applies the configured `OverflowPolicy`:
- `DROP`: Drops leftover stacks naturally at the player's location.
- `REFUSE`: Aborts the delivery (atomic check before mutation) so nothing is partially given.
- `QUEUE` (or partial): Gives what fits (or queues to claim storage when enabled) and reports the exact leftover count.
- `DeliveryOutcome` always reports `requestedCount`, `givenCount`, `equippedCount`, `droppedCount`, `refusedCount`, and `status`—never a silent drop.

---

## 8. Selectors, Permissions & Command Layer Boundary

### Architectural Boundary (`allium-core` vs. Plugin Command Layer)
- **`allium-core` (`codes.castled.allium.items.*`):** Pure item domain library. Contains `ItemRef`, `ItemProviderRegistry`, `ItemParser`, `ItemBuilder`, adapters, and `ItemDeliveryService`. Has **no** dependency on command senders, selector syntax, or command permissions.
- **Plugin Command Layer (`src/main/java/codes/castled/allium/command/...`):**
  - **`Selectors.java`:** Ported from legacy Allium into the plugin command layer (`codes.castled.allium.command.selector.Selectors`).
  - **Player-Only Targeting for `/give`:** `/give` only delivers to `Player` targets. If a selector like `@e` is used, the executor either restricts/filters to `type=player` or rejects non-player matches with a clear error message.
  - **Permission Gating in Executor:**
    - `allium.give` / `allium.item` (base command)
    - `allium.give.other` (giving to other players)
    - `allium.give.unsafe-enchants` (allowing enchantment levels above vanilla max)
    - `allium.give.raw-components` (allowing raw `[...]` / `{...}` input)
    - `allium.command.selector.all` (`@a`) and `allium.command.selector.entities` (`@e`)
    - Per-item or per-provider spawn restrictions if configured.
  - **Command Family & Collisions:**
    - `/give` and `/i` (`/item`) belong to the `give` / `item` command families and obey `disabled-commands: []`.
    - Tested explicitly alongside vanilla `minecraft:give` and `allium:give` to verify Bukkit command-map ownership and fallback behavior.
    - Position-aware tab completion suggests:
      1. Target position: visible online players + permitted selectors (`@p`, `@a`, `@r`, `@s`, `@e[type=player`).
      2. Item position: `minecraft:`, `allium:`, and active provider namespaces (`nexo:`, `oraxen:`, `craftengine:`) plus vanilla materials.
      3. Amount position: `1`, `16`, `64`.
      4. Modifier position: `name:`, `lore:`, `unbreakable`, `cmd:`, `-e`, enchant keys (`sharpness:`, `unbreaking:`), and context-aware keys (`owner:` for skulls, `effect:`/`potion:` for potions, `color:` for leather/potions).

---

## 9. Sequencing: M1 vs. M4

1. **M1 (`S04`, `S04 follow-up`, `S05`, `S06`):**
   - Implement the core `ItemRef` contract, `ItemProviderRegistry` (with readiness states and behavior-ID binding), `ItemParser`, `EnchantmentAdapter`, `SkullAdapter`, `PotionAdapter`, `ComponentAdapter`, and `ItemDeliveryService`.
   - Include a **minimal `GiveCommand` test harness** in M1 strictly to exercise provider resolution, behavior identity (`allium:mob_disarmer` vs `nexo:mob_disarmer`), and item delivery during M1 verification.
2. **M4 (Full Essentials Utility Completion):**
   - Expand `/give`, `/i`, and `/enchant` with full selector integration (`Selectors.java`), multi-item batching, `-e` equipment handling, rich tab completion, unsafe-enchant/component permission gates, and legacy config/command alias support.
