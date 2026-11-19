# Parity utilities (S25)

The S25 utilities complete M4 Essentials/Allium operator and quality-of-life commands under
`EssentialsModule`, built on `CommandSpec` (`CoreCommandSpecs.UTILITY_SPECS` and
`CoreCommandSpecs.PARITY_UTILITY_SPECS`), `CommandFamilyPolicy`, `TaskScheduler`,
`PlayerNameVisibility`, and S04's `SkullProfiles` and `ItemService` contracts.

## Commands & permissions

All command families obey `disabled-commands: []` (`CommandFamilyPolicy`), so disabling any
canonical name or alias (e.g. `ci`, `uptime`) yields the entire family's `CommandMap` labels at
startup without registering listeners or intercepting dispatch.

| Command | Aliases | Base / child permissions | Default (`allium.*`?) | Summary |
| --- | --- | --- | --- | --- |
| `/repair [hand\|all]` | — | `allium.repair`, `allium.repair.all` | `op` (in `allium.*`) | Repairs held item in place preserving metadata/identity, or all damaged inventory slots (`all`) |
| `/disposal` | `/trash`, `/dispose` | `allium.disposal` | `op` (in `allium.*`) | Opens a 27-slot virtual container whose contents are cleared on close |
| `/workbench` | `/craft` | `allium.workbench` | `op` (in `allium.*`) | Opens a virtual crafting workbench |
| `/anvil` | — | `allium.anvil` | `op` (in `allium.*`) | Opens a virtual anvil GUI |
| `/info` | — | `allium.info` | `op` (in `allium.*`) | Shows server name and vanish-aware online player count |
| `/speed [walk\|fly] <0..10\|reset> [player]` | — | `allium.speed`, `allium.speed.others` | `op` (in `allium.*`) | Adjusts walk or fly speed (`0..10` mapped to `0.0..1.0`) or resets defaults (`0.2` walk, `0.1` fly) |
| `/ping [player]` | — | `allium.ping`, `allium.ping.others` | `op` (in `allium.*`) | Reports self or visible target ping in milliseconds |
| `/ext [player]` | `/extinguish` | `allium.ext`, `allium.ext.others` | `op` (in `allium.*`) | Extinguishes fire ticks (`setFireTicks(0)`) on self or target |
| `/burn <player> [seconds]` | — | `allium.burn` | `op` (in `allium.*`) | Sets target fire ticks (`1..300` seconds, default `10s`) |
| `/smite <player>` | `/lightning`, `/strike` | `allium.smite` | `op` (in `allium.*`) | Strikes visual-only lightning (`strikeLightningEffect`) at target location |
| `/nv [on\|off] [player]` | `/nightvision` | `allium.nv`, `allium.nv.others` | `op` (in `allium.*`) | Toggles or sets infinite-duration Night Vision effect |
| `/clearinventory [player\|*\|**] [item] [maxCount]` | `/clear`, `/ci` | `allium.clearinventory`, `.others`, `.all` | `op` (in `allium.*`) | Clears an inventory or filters by item up to `maxCount` per target |
| `/more [amount] [player]` | — | `allium.more`, `allium.more.others` | `op` (in `allium.*`) | Refills held item to `maxStackSize` or explicit amount (`<= maxStackSize`) |
| `/hat` | — | `allium.hat` | `op` (in `allium.*`) | Atomically swaps main-hand item into helmet slot and restores prior helmet to main hand |
| `/skull [owner] [player]` | `/head`, `/playerhead` | `allium.skull`, `allium.skull.others` | `op` (in `allium.*`) | Resolves player head asynchronously via `SkullProfiles` and delivers `PLAYER_HEAD` |
| `/itemdb [player]` | `/iteminfo` | `allium.itemdb`, `allium.itemdb.others` | `op` (in `allium.*`) | Read-only display of material key, stack size, durability, enchants, and Allium ID |
| `/gc` | `/uptime`, `/lag`, `/tps` | `allium.gc` | `op` (in `allium.*`) | Reports JVM uptime, TPS (`Bukkit.getTPS()` on Paper, `N/A (Spigot)` on Spigot), memory, disk free, visible player count, chunks/entities, and `[TIME PAUSED]` |
| `/time [set\|add\|pause [on\|off]] [value] [world\|*]` | — | `allium.time`, `allium.time.set`, `allium.time.add`, `allium.time.pause` | `op` (in `allium.*`) | Queries, sets (`day`/`noon`/`sunset`/`night`/`midnight`/`sunrise` or ticks), adds, or pauses daylight cycle |
| `/day [world\|*]` / `/night [world\|*]` | — | `allium.time.set` | `op` (in `allium.*`) | Fixed shortcuts setting world time to `1000` (`/day`) or `13000` (`/night`) |
| `/weather <clear\|sun\|rain\|storm\|thunder> [duration] [world\|*]` | — | `allium.weather` | `op` (in `allium.*`) | Sets weather state and optional duration in seconds (`1..1,000,000`) |
| `/rename <name\|clear>` | `/itemname` | `allium.rename` (includes `allium.rename.color`) | `op` (in `allium.*`) | Sets or clears the held item's custom name, up to 64 visible characters. Colour codes, hex and gradients need `allium.rename.color`; without it the text stays literal |
| `/lore <add\|set\|insert\|remove\|clear\|copy\|paste> [line] [text]` | `/itemlore` | `allium.lore` (includes `allium.lore.color`) | `op` (in `allium.*`) | Edits the held item's lore by 1-based line number: at most 32 lines of 100 visible characters. `copy` keeps the held item's lore for the player until they leave; `paste` replaces the held item's lore with it, without formatting unless the player has `allium.lore.color` |
| `/xp [player]`, `/xp <give\|take\|set> <player> <amount>[L]`, `/xp reset <player>` | `/exp` | `allium.xp` (own, default true), `allium.xp.others`, `allium.xp.give`, `allium.xp.take`, `allium.xp.set` | others `op` (in `allium.*`) | Shows or changes experience. `L` (or a trailing `levels` word) means levels, which keep the bar's fill as vanilla does. Vanilla's `add`, `remove` and `query` words work, and selectors need `minecraft.command.selector`. Totals come from the level and bar, not `getTotalExperience()`, and stop at level 21863 |
| `/customtext [section] [page]` | — | `allium.customtext` | `true` (in `allium.*`) | Pages of `customtext.txt`, with click and hover events; see [customtext.md](customtext.md) |
| `/broadcast <message>`, `/broadcasts [send <name>\|next]` | `/bc`, `/bcast` | `allium.broadcast`, `allium.broadcasts.admin`, `allium.broadcasts.receive` (true) | `op` (in `allium.*`) | Staff broadcasts and the timed broadcasts of `broadcasts.txt` (off by default); see [broadcasts.md](broadcasts.md) |
| `/motd [section] [page]`, `/rules [chapter] [page]` | — | `allium.motd`, `allium.rules` | `true` (in `allium.*`) | Pages of `motd.txt` and `rules.txt` in the same layout; the MOTD is also shown on join (`motd.show-on-join`, `motd.join-delay-ticks`); see [customtext.md](customtext.md) |
| `/enchant <enchantment> <level> [player]` | — | `allium.enchant`, `allium.enchant.others`, **`allium.enchant.unsafe`** | `allium.enchant.unsafe` is **`false` and NOT in `allium.*`** | Applies (`level >= 1`) or removes (`level == 0`) enchantments; accepts legacy `<player> <enchant> [level]` order |

For `/clear`, a named online player requires `allium.clearinventory.others`.
Both `*` (visible online players) and `**` (saved offline inventories) require
`allium.clearinventory.all`. Item filters accept aliases and share completions
with `/i`. Repeat the same command within 30 seconds to confirm.
`/clearinventoryconfirmtoggle` changes your personal confirmation setting and
requires `allium.clearinventoryconfirmtoggle`.

Offline clears require a working moderation inspection module. They skip
inventories in use, pending transactions, and files the installed codec cannot
safely edit.

## Guarantees & safety rules

1. **Zero main-thread stalls & clean lifecycle shutdown:**
   - `/skull` uses `ItemsModule#skulls()` (or a bounded `Allium-EssentialsSkull` pool on `TaskScheduler` if `items` is disabled) and completes inventory delivery back on the server thread after checking `target.isOnline()` and `UtilitySupport#guardInventoryNotBusy`.
   - `TimePauseCoordinator` tracks paused worlds by UUID alongside their pre-pause `GameRule.DO_DAYLIGHT_CYCLE` value, reasserts pinned ticks on a scheduled server task via `TaskScheduler#repeatOnServer`, and restores prior `DO_DAYLIGHT_CYCLE` values on `/time pause off` and module shutdown (`EssentialsModule#onDisable`).
2. **Inventory safety & metadata preservation:**
   - `/repair` and `/repair all` edit `Damageable#setDamage(0)` in place via `ItemStack#setItemMeta`, preserving display name, lore, enchantments, PersistentDataContainer markers, and S04 Allium signatures without minting replacement stacks.
   - `/disposal` attaches a `DisposalHolder` marker and clears the top 27 slots unconditionally on `InventoryCloseEvent` (`UtilityCommand.DisposalListener`).
   - `/repair`, `/disposal`, `/workbench`, `/anvil`, `/clearinventory`, `/more`, `/hat`, `/skull`, and `/enchant` all check `UtilitySupport#isInventoryBusy` so receipt recovery / busy locks in `kits` and `mail` are never raced or corrupted.
3. **Vanish blindness & operator safety:**
   - All `.others` target lookups and tab completions go through `PlayerNameVisibility#canSeeTarget` / `PlayerNameVisibility#canSee`, returning the identical `player-not-found` response for vanished and absent targets unless the actor holds `allium.vanish.see`.
   - `/enchant` requires an explicit grant of `allium.enchant.unsafe` (`default: false`, excluded from `allium.*`) before allowing levels above `Enchantment#getMaxLevel()` or incompatible target materials.
4. **Structured auditing:**
   - World/player mutations (`repair_all`, `speed`, `burn`, `ext`, `smite`, `nv`, `clearinventory`, `more`, `skull`, `time_set`, `time_add`, `time_pause`, `weather`, `enchant`) log structured `[UtilitiesAudit] actor=... action=...` lines.

## Vanilla command labels

Allium owns the bare and `allium:` forms of `time`, `weather`, `enchant`, `clear` and `speed`
while their command families are enabled. Its player-target arguments take player
names and use the command's visibility and permission policy. Use
`minecraft:time`, `minecraft:weather`, `minecraft:enchant` and `minecraft:clear` for
the vanilla grammar in automation. Vanilla has no `minecraft:speed`; that utility
uses Allium's walk or fly speed and named-player syntax. The permanent utilities gate
checks Bukkit ownership, command blocks, console `/execute` and selector effects.

On Spigot 1.20.1, the nested commands after `execute … run` use bare
`time`, `weather`, `enchant` and `clear`; the dispatcher rejects their
`minecraft:` forms there. Paper 26.3 accepts the native namespaced forms.
Both lanes can run bare `speed` from a command block with an explicit player
name. Paper also accepts it after `execute … run`; Spigot rejects that syntax.
