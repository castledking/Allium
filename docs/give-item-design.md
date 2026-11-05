# Give and Item Library Design

Status: initial design specification (see subsequent revision for full roadmap contract alignment).

## 1. Legacy Allium vs. Essentials: The Breakdown

### Legacy Allium `/give` (`codes.castled.allium.commands.Give`)
- **Brittle custom grammar:** Relied on nested string splitting:
  - Semicolon-delimited meta and inline quantities: `diamond_sword;sharpness:5;name:&6GodSword;1` or `dirt;16`.
  - Comma-delimited multi-items: `dhelmet,dchest,dlegs,dboots`.
  - Manual substring slicing for components: `iron_sword[minecraft:unbreakable={boolean:['true']}]`.
- **Selector disconnection:** Despite Allium possessing a robust `Selectors.java` parser (supporting `@p`, `@a`, `@r`, `@s`, `@e`, distance, limits, sorting, gamemodes), legacy `Give.java` re-implemented a rudimentary hardcoded switch for `@a`/`@p`/`@r`/`@s`/`@e`, ignoring radius arguments, sorting, and selector limits.
- **Monolithic design:** Item resolution, meta application, player lookups, and inventory drops were tangled into a single 700-line executor, preventing kits, signs, and addons from reusing item building logic.

### Essentials `/give` (`com.earth2me.essentials.commands.Commandgive`)
- **Human-friendly space-separated meta:** Arguments follow `/give <player> <item> [amount] [key:value...]` (e.g., `sharpness:5`, `name:&bExcalibur`, `lore:Line1|Line2`, `owner:Notch`, `color:red`).
- **Component/NBT parsing friction in modern versions:**
  - In Minecraft 1.20.5+, Mojang replaced NBT tags on `ItemStack` with Data Components (`[...]`).
  - Essentials delegates raw component/NBT strings directly to Bukkit's `UnsafeValues.modifyItemStack(ItemStack, String)`.
  - Depending on server version, `modifyItemStack` has varied expectations around item keys and component formatting, and throws `noMetaJson` or `noMetaNbtKill` when encountering syntax mismatches.
  - **No external provider integration:** Essentials has no built-in awareness of Nexo, CraftEngine, Oraxen, or Allium custom items, nor does it support comma-separated item sets or auto-equipping.

## 2. Shared Item Subsystem Architecture

The item subsystem is packaged inside `allium-core` and bundled into `Allium.jar` so that Allium remains the only required plugin while exposing a clean API for kits, signs, rewards, and addons.

```text
allium-core/
└── src/main/java/codes/castled/allium/items/
    ├── ItemRef.java
    ├── ItemProviderRegistry.java
    ├── ItemParser.java
    ├── ItemBuilder.java
    ├── delivery/
    │   ├── ItemDeliveryService.java
    │   ├── DeliveryOutcome.java
    │   └── ItemGiveEvent.java
    └── adapters/
        ├── EnchantmentAdapter.java
        ├── SkullAdapter.java
        ├── PotionAdapter.java
        └── ComponentAdapter.java
```
