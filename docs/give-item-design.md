# Give and Item Library Design

Status: design specification aligned with [roadmap.md](roadmap.md) contracts.

## Implementation status (S04 follow-up, M1 harness)

Built and live-verified on Spigot 1.20.1, Paper 1.21.8 and Paper 26.3
([results](../tests/live/items/RESULTS.md)). Where the build differs from the text below:

- **Grammar:** `ItemSpecParser` (`allium-core`) implements §5. A quote opens a string only at a
  value boundary (start, whitespace, or after `[ { , : = |`), so `name:Bob's_Sword` is plain
  text. Provider names are case-insensitive; provider ids keep their case (Oraxen ids are
  case-sensitive). With `legacy-semicolon-syntax: true` the old form is converted; otherwise a
  top-level `;` is an error. Duplicate tokens are rejected.
- **Raw data (§6):** `ComponentAdapter` uses `ItemFactory#createItemStack` in both eras, since the
  1.20 API already has it: `minecraft:<id>{...}` before 1.20.5 and `minecraft:<id>[...]` from
  1.20.5, with the other syntax rejected by version. `UnsafeValues#modifyItemStack` is not used.
  Raw data applies only to vanilla items, because on Allium or provider items it could rewrite
  identity and signature tags.
- **Signed items:** an item whose behavior is signed or privileged is minted one per stack, so
  every stack has its own instance UUID (at most 36 per request). Minting a privileged behavior
  needs `allium.give.privileged` as well as `allium.give`.
- **Overflow (§7):** `drop`, `refuse` and `partial` (what fits is given, the exact leftover is
  reported and not given). `queue` to claim storage is not built yet. `refuse` is decided from a
  plan before anything changes.
- **Skulls:** an online owner's profile is used at once. Otherwise lookups share a bounded LRU
  cache with expiry, are deduplicated, and run on Allium's own two-thread executor with a
  32-entry queue and a 3 s timeout. A timeout or failure gives a head without its skin plus a
  warning, never an exception.
- **Command ownership:** on Paper 1.20.6+ `/give` is registered through Paper's Brigadier API with
  vanilla's `players()` argument, because Paper runs legacy Bukkit commands under `/execute`
  with the initiating sender and no `as`/`at` context. Spigot keeps the Bukkit command; there
  `execute … run give` resolves to vanilla. `minecraft:give` and datapack functions are always
  vanilla. Selectors need vanilla's `minecraft.command.selector` on both routes, and a target
  that resolves to nobody reports vanilla's "No player was found".
- **Skull fallbacks:** a skin that cannot be resolved makes the delivery `DEGRADED` (head given
  without its skin) or, with `items.give.skull-timeout-fallback: refuse`, `REFUSED_UNRESOLVED`
  (nothing given). Remote lookups are limited per rolling minute
  (`items.give.skull.max-lookups-per-minute`, default 30). On an offline-mode server Paper and
  Spigot resolve names to offline profiles without contacting Mojang, so heads there have no
  skin unless the server runs behind an online-mode proxy.
- **Not yet:** `/i`, operator item aliases, kits/signs/rewards callers, and a stable addon API
  surface. `/give` is labelled the M1 harness.

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
   - `ce:` and `vanilla:` are converted by the config/data importer into `craftengine:<namespace>:<id>` and `minecraft:<id>` (emitting a mapping report for any ambiguous namespace). At runtime, `ce:` is accepted only in deprecated compatibility mode (`ProviderKey.parseAllowingDeprecatedAliases`), firing a deprecation warning **once per distinct reference per startup or reload** (`ProviderKey.resetDeprecationWarnings()`).
5. **Legacy Shorthands (`dhelmet`, `dboots`, etc.):**
   - Hardcoded material shorthands are **not** built into the core `ItemParser`.
   - Instead, the legacy importer converts old config/kit references to canonical `minecraft:diamond_helmet` IDs. Optional command-level item aliases, if enabled by the server operator in config, live in a command-layer alias table that resolves to canonical `ItemRef` strings before invoking the library.

---

## 3. Behavior Identity, Schema Version & HMAC Signing (M1 Gate)

`ItemRef` lives in `allium-core` (zero Bukkit/Spigot/Paper dependencies) and strictly decouples **Allium behavior identity** (`behaviorId`) from the **appearance provider reference** (`provider` + `providerItemId`).

```text
ItemRef (schema-version: 1)
 ├── appearance: ProviderKey (e.g. "nexo" -> "mob_disarmer", or "allium" -> "mob_disarmer")
 ├── behaviorId: Optional<String> (e.g. "allium:mob_disarmer")
 ├── amount: int
 └── state: Map<String, Integer> (e.g. charges)
```

### How the Registry Binds Behavior, Appearance, and Signatures
1. **Behavior Registration & Privilege Flag:** Core tools and addons register their definitions (`AlliumItemDefinition`) under a canonical `behaviorId` (e.g., `allium:mob_disarmer`, `allium:handcuffs`, `allium:phantom_obliterator`) and declare `privileged = true|false`. `handcuffs` and `phantom_obliterator` declare `privileged = true`.
2. **Appearance Binding & Authoritative Native ID Resolution:**
   - In configuration, the server operator binds a behavior ID to a visual source—by default the built-in Allium resource pack model (`allium:<id>`), or an external provider item (`nexo:<id>`, `oraxen:<id>`, `craftengine:<ns>:<id>`).
   - When `ItemService.identify(ItemStack)` inspects a stack whose external provider (`nexo`, `oraxen`, `craftengine`) is `READY`, it queries the provider adapter's own native item-ID API (`ItemProviderAdapter#identify`). If the provider's native ID and Allium's PDC stamp (`allium:provider` / `allium:item_id`) disagree, `ItemService` logs an explicit diagnostic and resolves appearance using one deterministic rule: **the ready provider's native API is authoritative for visual appearance** (and if the provider's native API reports the stack is no longer a valid provider item, it degrades to `minecraft:<carrier>` with no privileged behavior).
3. **Schema Version, Canonical `allium:state_*` Encoding & Server-Secret HMAC Signing:**
   - Stamped PDC carries `allium:schema_version = 1` (`INTEGER`), `allium:provider`, `allium:item_id`, and `allium:behavior_id`.
   - `ItemRef.serialize()` writes `"schema-version": 1` and `ItemRef.deserialize()` validates it.
   - Because PDC tags are forgeable by creative clients and `/give` component syntax, `ItemService` signs privileged items (`privileged = true`) using `ItemHmacSigner` (`HmacSHA256` over `(provider, itemId, behaviorId, instanceUuid, canonicalState)`), stamping `allium:instance_uuid` and `allium:signature = v2:<keyId>:<64-char-hex>`.
   - `canonicalState` (`ItemHmacSigner#encodeCanonicalState`) encodes all `allium:state_*` entries in lexicographical key order, with a count and length-prefixed keys/values (embedded delimiters cannot create fields). Every signed/privileged state mutation through `ItemService#setState(ItemStack, key, value)` first verifies the original signature; rejected items remain unchanged. Valid mutations re-compute and updates `allium:signature` over the new state map while preserving `allium:instance_uuid`. Directly editing `allium:state_*` (such as `allium:state_charges`) in PDC invalidates the MAC and causes verification to fail.
   - Signatures survive `cloneItem`, `setState`, and `swapOrRefreshAppearance` (which preserves `instance_uuid` and `allium:state_*` and re-signs with the updated appearance key).
   - Unprivileged items (`privileged = false`, such as `mob_disarmer`) skip signing.
4. **Versioned Key ID (`v2:<kid>:<hex>`), Keyring Rotation, Network Shared Secret & Backup Policy:**
   - The keyring is stored outside `config.yml` at `plugins/Allium/.item-hmac-secret` (with `0600` POSIX permissions where supported) so secrets are never leaked when operators share configs:
     ```ini
     active=k2:<64-char-hex-secret>
     retired=k1:<64-char-hex-secret>
     ```
   - **Key Rotation:** To rotate keys without invalidating existing items in player inventories, move the current `active=<oldKid>:<hex>` line to `retired=<oldKid>:<hex>` and add a new `active=<newKid>:<new-hex>`. All new items and `setState` mutations sign with `active`, while items carrying a `retired` key ID remain valid and transparently re-sign under the `active` key the next time their state or appearance is updated.
   - **Shared Secret for Multi-Server Networks:** Backend servers that transfer player inventories or serialized `ItemRef`/ItemStack data across proxies must share the same `.item-hmac-secret` keyring file so signatures with `<keyId>` verify on every node.
   - **Loud Unknown-Key Diagnostic:** If `ItemService.identify(ItemStack)` encounters a privileged item whose signature carries an unknown `keyId` (`VerificationStatus.UNKNOWN_KEY_ID`, e.g., after restoring a world/playerdata backup without `.item-hmac-secret` or misconfiguring a network node), it logs a loud `CRITICAL HMAC KEY MISMATCH` warning identifying the unknown `keyId`, configured keyring IDs, and remediation steps instead of failing silently.
   - **Backups & Migration:** `BackupManager` automatically includes `.item-hmac-secret` (preserving `0600` permissions) in `plugins/Allium/backups/<timestamp>/`. Staging cutover and rollback snapshots must preserve `.item-hmac-secret` alongside world and database state.
   - Existing empty/malformed keyring files fail startup without being overwritten. Restore the original file from backup; do not delete it to suppress an error.
   - Signature format `v2:<keyId>:<hex>` binds both version and key ID in the MAC and uses unambiguous length-prefixed fields. Earlier unversioned experimental signatures are explicitly rejected, never silently upgraded by `setState`; retain the item and original keyring for a deliberate, trusted importer. Verification cannot safely infer legitimacy from the old state-ambiguous format.
   - Startup reports configured key IDs (never secrets); already-online inventories are audited one player per tick, and joining players' carried/ender-chest inventories are checked read-only for unknown key IDs. Offline player files, unloaded chunks and future addon storage are audited when their owners load them; startup does not scan or load the entire world.
5. **Security Scope (Minting & Editing vs. Creative Duplication):**
   - Server-secret HMAC signing prevents creative clients and unauthorized `/give` component syntax from **minting** new privileged items or **editing** `allium:*` identity and `allium:state_*` tags (such as charges).
   - Signing does **not** prevent creative-mode stack duplication (cloning an already-signed item in creative mode duplicates its `instance_uuid` and valid signature). Granting privileged items to creative-mode players is an operator permission concern.
6. **Read-Only Legacy Tags for Importer:**
   - `ItemService#readLegacyMetadata(ItemStack)` reads legacy `allium:custom_item_id` (`STRING`) and `alliumcards` (`STRING` or `TAG_CONTAINER`) PDC keys into `LegacyItemMetadata` for the migration importer. Legacy tags alone never grant runtime privileged behavior prior to migration.

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
1. **Off-Thread Profile Resolution:** If an item spec includes `owner:<name>` and the profile is not in the local bounded cache, `SkullAdapter` resolves the profile asynchronously on Allium's bounded executor with an explicit timeout (e.g., 3 seconds). On timeout or lookup failure, it returns an explicit partial/warning state in `DeliveryOutcome` rather than blocking the server thread.
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
