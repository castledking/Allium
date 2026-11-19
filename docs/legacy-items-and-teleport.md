# Legacy items, enchantments and teleport utilities

The bundled `itemdb.yml.gz` retains 43,698 legacy Allium aliases. Its 846 numeric
ID/data mappings come from Essentials' `Essentials/src/main/resources/items.csv`
and Bukkit's legacy material conversion on Spigot 1.20.1, with Essentials' entity
IDs for spawners/eggs and native potion types/extended/upgraded flags. They are
never inferred from partial modern names: `1:2` is `POLISHED_GRANITE`, and
`383:54` is `ZOMBIE_SPAWN_EGG`. Bare IDs imply data zero. Block-only IDs without a
giveable item remain unavailable; the grass item follows the runtime's rename.

The items module extracts the database to `plugins/Allium/itemdb.yml` when absent.
On existing files it adds missing numeric defaults and corrects values matching
the original bundled numeric table. Custom aliases and custom numeric values are
retained. Aliases are read from the bundled database first and then from the server's file, so
aliases added to Allium later (such as `npick`, `nhelm` or `nspade` for netherite, like `dpick`
for diamond) work on servers whose `itemdb.yml` predates them; where both name an alias, the
server's file wins. To drop a bundled alias, map it to another item in the file. The first upgrade saves the exact original as
`itemdb.yml.before-legacy-id-fix` and replaces the file atomically. A customized
value identical to an old default is indistinguishable from that default and is
corrected. `/allium reload` prepares an immutable replacement index; a malformed
replacement keeps the previous one. Numeric IDs still execute when typed, but
are excluded from `/i` and `/give` completion; aliases (including names such as
`10cd`), canonical names and Allium items continue to complete.

`tests/live/parity/plugin/legacy-ids.csv` records the unique Essentials ID/data
pairs and source SHA256. The native Spigot fixture emits
`essentials-converted-ids.json`; `tools/rebuild-legacy-item-ids <export.json>`
rebuilds the deterministic compressed defaults while retaining the alias section.
The original 493 defaults in `itemdb-legacy-ids-v1.yml` are used only to identify
unchanged values during upgrade. Do not regenerate that migration baseline.
Canonical material names take precedence over aliases, and explicit provider
namespaces are never silently remapped. Unsupported materials/effects on older
servers fail clearly rather than producing a different item.

Examples:

```text
/i dsword
/i acid2pot 2
/i potion;speed;60;2
/i stick,gshovel 1
/i stick:1,diamond:2
/i zombie_spawner
/i spawner;zombie
/i 1:1
/i DIAMOND_SWORD;sharpness:5
/i DIAMOND_SWORD;plugin:keen:3
```

Potion fields are `material;effect;seconds;level[;particles;icon]`, not item counts.
A separate trailing amount applies to the resulting stacks. Legacy splash and
lingering carrier aliases, neutral base potions, Turtle Master's two effects,
legacy effect names, and arrow/lingering duration compensation are handled.
When the effect, level and duration match a vanilla potion, the item is that potion:
`/i potion;poison` is a Potion of Poison, `/i splash_potion;speed;0;2` a Splash Potion of
Swiftness (strong), `/i potion;speed;480` a long Potion of Swiftness, and tipped arrows and
lingering potions likewise. Other values keep the custom effect on a water base and are
named after the vanilla potion (Potion of Poison, Arrow of Leaping), in the reader's
language: through the potion name field on 1.21.2+ and a translated, non-italic item name
before that. Effects no potion carries (haste, wither, ...) keep the Water Bottle name.
Spawner aliases retain their entity type. The numeric semicolon amount form
remains governed by `items.give.legacy-semicolon-syntax`; prefer a separate amount.

`/i`, `/item` and `/give` complete aliases, giveable vanilla materials and registered
Allium definitions. After `DIAMOND_SWORD;`, completion reads Bukkit's enchantment
registry and filters by the actual carrier. Namespaces from plugin-registered
enchantments are retained, for example `plugin:keen:`. `/enchant` uses the same
registry and carrier checks, including enchanted books, and avoids already-present
or conflicting suggestions. Private plugin enchantments that are not exposed in
Bukkit's registry are not claimed as supported registry entries.

`allow-unsafe-enchants: false` is a root config setting. Both give/self-give and
`/enchant` require that switch **and** `allium.enchant.unsafe` for unsafe levels,
incompatible carriers or conflicts. Otherwise above-maximum levels clamp, while
incompatible/conflicting applications are refused. Feedback uses legacy language
keys `give.unsafe-level`, `give.unsafe-enchant` and `give.conflicting-enchants`.
Raw vanilla NBT/component enchantments are validated by the same policy. Provider-supplied items are not rewritten by this raw-input check. Level zero on `/enchant` still removes an enchantment. Other-player actions retain
the existing permission, visibility and inventory-busy checks.

Raw vanilla data uses the server's `ItemFactory#createItemStack` parser, preserving
all data/components that the running Minecraft version supports. This is not a
small hardcoded component allowlist. Spigot 1.20.1 accepts `material{NBT}`; 1.20.5+
uses `material[components]`. Invalid/wrong-version input is refused. Raw data is
still forbidden on signed Allium/provider identities. Resulting stacks respect
their native maximum sizes, including when a component changes that limit.

## Teleport targets and companions

`/tphere`, `/here` and `/s` share one family and permission `allium.tphere`.
`/there` (alias `/tpthere`, permission `allium.tpthere`) uses a block-face ray trace
within 128 blocks. Without targets, here pulls selected companions to the caller
(or performs the legacy self-refresh); there moves selected companions to the
looked-at spot, otherwise the caller. Player moves use the existing safety,
warmup/cooldown, request-preference, history and `/back` service.

`/tp` accepts player selectors for its source and a single visible player as its
destination. Here/there accept multiple names/selectors, deduplicate targets and
exclude the issuer. Selectors support legacy `@p`, `@r`, `@a`, `@s`, `@e` and
`type`, `name`, `tag`, `gamemode`/`gm`, `world`, `distance`, `limit`, `sort`,
`x/y/z`, `dx/dy/dz`. Type/name/tag/gamemode filters accept `!`. As in legacy,
nearest/random-player selectors exclude the issuer and default to that world's
players. Invisible players are removed before sorting/limiting. Targets are
loaded only; no offline identity/network lookup occurs. A command is bounded to
128 targets. General selector access requires `allium.selectors` or
`allium.admin`; all-player/entity selectors additionally require
`allium.command.selector.all`/`allium.command.selector.entities` (or admin).
Moving other players with `/tp` retains `allium.tp.others` and existing recipient
policy/bypass rules.

`/tppet [player]` toggles owned tameable-pet selection. `/tpent [player]` toggles
non-player entity selection; aliases are `/tpentity`, `/tpmob`, `/teleportmob`,
`/tpm`, `/tpe`. Entity-mode permissions retain legacy `allium.tpmob` naming.
Others require the corresponding `.others` permission. Selection limits use
`.1` through `.10`, defaulting to one. Main-hand right clicks select/deselect;
protection cancellations are checked after the event finishes. Glowing state is
reference-counted and restored after selection cleanup. Successful player
teleports carry selected companions, consuming the selection once. Empty armed
modes remain armed; quit, toggle-off, module disable, or non-admin movement more
than 30 blocks from the selection start clears them. Ownership, validity and
permissions are rechecked before movement.

See `tests/live/parity/RESULTS.md` for unit and native evidence, including the
boundary between real server checks and SDK-shaped plugin enchantment coverage.
