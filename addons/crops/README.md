# Crops

Install `Crops.jar` in `plugins/Allium/addons/` with Allium API 1.4 or later. Crops contains
Kitchen and runs with Bukkit and Allium. The bundled pack includes the 26 SF crops in the port,
their stages and produce, and all 11 Kitchen pie and pizza types. Crops uses native item displays
and interaction entities above the soil. Jade clients read the display item metadata and show
**Crops** as the mod name.

```sh
./gradlew :addons:crops:build shadowJar
```

The jar is collected at `build/libs/addons/crops/Crops.jar`. The pack is bundled in the jar and
also built at `addons/crops/build/Crops-resource-pack.zip`. In `plugins/Crops/config.yml`, set
`resource-pack.delivery` to `allium` to merge into Allium's pack, or `rspm` to export
`Crops_resource_pack.zip` to ResourcePackManager's mixer. `crop-model-in-jade` defaults to `false`
so Jade shows each planted crop's seed icon (matching vanilla crops); set it to `true` to show the
planted crop's stage model icon instead.

## Commands

- `/crops` (aliases `/crop`, `/hf`, `/harvest`):
  - `/crops list [crops|pies|items]` — lists loaded crops, kitchen pies, or custom items (`crops.use`).
  - `/crops inspect` — inspects the planted crop or kitchen placement you are looking at (`crops.use`).
  - `/crops give [player] <item> [amount]` — gives any Crops or Kitchen item with tab completion (`crops.admin`).
  - `/crops plant <crop> [path]` — plants a crop on the soil you are looking at (`crops.admin`).
  - `/crops grow [stages|max]` — advances the looked-at crop's growth stage (`crops.admin`).
  - `/crops remove`, `/crops nearby [radius]`, `/crops status` — admin inspection and cleanup (`crops.admin`).
  - Legacy `/hf crop <inspect|plant|grow|remove|nearby>` subcommands are also accepted.

## Growing and selling

Give seeds with `/i allium:tomato_seeds` and right-click suitable soil. Stage timing, soil
requirements, weighted paths, loot and regrowth are in `plugins/Crops/crops.yml`. A planted
crop persists with its interaction entity and restores its model when the chunk loads.
Plant and harvest access uses Allium's block protection hooks.

Use `/worth allium:tomato` or `/sell allium:tomato 2`. Prices in `plugins/Crops/prices.yml`
default to 5 for regular produce, 7.50 for silver star, 10 for golden star, 15 for golden
and 25 for gigantic produce. Allium's configured sell multipliers apply. Remove a price entry
and restart to stop selling that item. The same price is used for held and bulk sales.

## Kitchen

Kitchen's crop ingredient conversions and baking recipes work at vanilla stations. Knead flour
in a water cauldron, place a pie crust on a solid support, and add ingredients in order. Use
sneak-right-click or punch to collect a finished cold pie, then bake it in a furnace. Place a
hot pie and right-click to eat a slice. The pizza base accepts spinach, cheese and tomato
sauce. A placed kitchen stove uses a native furnace with a Crops display. The flour bag stores
flour when clicked with a flour stack on the cursor. Right-click the held bag to withdraw one,
or sneak-right-click to withdraw a stack.

The source definitions came from SF's Harvest and Kitchen files and SF's CraftEngine and Nexo
asset definitions. To refresh the snapshot, run `python3 addons/crops/tools/import_sf.py "$SF"`.
The importer excludes the weed crop. Several gigantic produce items and golden cabbage had
missing source art and reuse a related model from the same crop.
