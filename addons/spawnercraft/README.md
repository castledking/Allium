# SpawnerCraft

Build with `./gradlew shadowJar :addons:spawnercraft:build :addons:curios:build`.
Install `build/libs/Allium.jar` in `plugins/` and
`build/libs/addons/SpawnerCraft.jar` in
`plugins/Allium/addons/`. The bundled trading-card recipe uses the card crafting service
in the Curios build from the same checkout. Curios is optional for every other recipe.

## Resource pack

Set `SF=/path/to/sf/plugins` when building to include the Spawner Type Changer's
Nexo model and texture. The pack uses the original geometry on 1.21.11 and newer.
Older clients use a version rebased to their single-axis element rotation format.
Builds without `SF` use the vanilla blaze rod appearance. The standalone pack is
`addons/spawnercraft/build/SpawnerCraft-resource-pack.zip` and is also bundled in the jar.

Set `resource-pack.delivery` in `plugins/SpawnerCraft/config.yml`.

- `allium` (default) merges the addon into Allium's pack, preserving the Allium icon and description.
- `rspm` exports `SpawnerCraft_resource_pack.zip` with SpawnerCraft's icon and description to
  ResourcePackManager's mixer directory.
- `none` lets you host the addon pack yourself.

Run `/allium reload` after changing delivery and reload ResourcePackManager when using `rspm`.
Changers issued before the addon added model data keep their existing appearance. Issue a new one
with `/pt give <player> spawner_changer` to get the model.

## Drops and recipes

Player kills roll the probabilities ported from Vanilla Tweaks' More Mob Heads 2.20,
with supplemental heads for mobs supported by legacy Allium. Includes assets from
VanillaTweaks.net, used in accordance with their terms and conditions.
[Vanilla Tweaks terms](https://vanillatweaks.net/terms/)

Set `drops.mobs.<mob>` or `drops.heads.<head-id>` in `plugins/SpawnerCraft/config.yml`.
Each supports `enabled`, `chance` without Looting, `looting-chance` at Looting I, and
`looting-per-level` above Looting I. Variant settings take precedence for the numeric fields.
Setting `enabled: false` disables the whole mob or the specific head. If the datapack is
installed, its regular heads still drop and can be placed as decorations. SpawnerCraft
requires its own signed heads for crafting and for opening the guide from an item.

- Eight heads from one mob around its spawner core produce a spawner of that type.
- Eight heads from one mob around paper produce a bound Curios card of that mob.
  Eight mob heads around a Paper craft a Simple trading card of that mob with a random quality roll (1–100) by default (`result.weights: { SIMPLE: 1 }`). Set `result.weights: {}` to use the Curios drop pool weights or specify custom tier odds.
- Trading a Curios card in (`/curios trade`, or the card menu) pays SpawnerCraft heads of the
  card's mob, in the mob's default look (pale wolf, white sheep).
- Right-click a SpawnerCraft head or core, or run `/spawnercraft guide <mob>`, to see recipes in the
  read-only crafting-table window. Pages rotate every three seconds. Left-click or right-click a
  recipe slot to go forward or back.
- `/spawnercraft give <player> <head|core> <mob or head ID> [amount]` gives ingredients.
  Tab completion lists head IDs for the drop settings. `/i allium:spawner_core;<mob>` also
  gives a signed core and completes the supported mob names.

Add shaped recipes under `recipes` in the config. Use three rows of three characters.
`H` is a signed head of the matching mob, `C` is a matching core, and `.` is an empty
slot. Define other symbols under `ingredients` as vanilla materials, `#wool`, or Allium
item references such as `nexo:example`. Set `mob` to apply the recipe to one mob. The
result can be a provider `item`, `spawner: true`, `card: true`, or a console give command.
For example, the following overrides delivery for the bundled spawner recipe.

```yaml
recipes:
  spawner:
    name: Mob Spawner
    pattern: [HHH, HCH, HHH]
    result:
      preview: SPAWNER
      command: 'spawnermeta give {mob} 1 {player}'
```

Commands support `{player}`, `{uuid}`, `{mob}`, and `{mob_upper}`. SpawnerCraft consumes
ingredients when Bukkit dispatches the configured command. Plugins may report their own
delivery failures separately. Run `/spawnercraft reload` after editing recipe or drop settings.

## Import legacy plushies

Run `/spawnercraft import <path>` from the server console or as a player with
`spawnercraft.admin`. The server can read the SF server root, its `plugins/` directory,
the Nexo mob plushie catalog (`Nexo/items/sf/plushies/mobs.yml`), or a checkout of legacy Allium.
`$SF` works when set in the server process environment. With no path the command uses
`import-directory` from the config or the installed Nexo catalog.

The importer carries over the legacy eight-wool ring and its special plushie IDs.
It writes `plugins/SpawnerCraft/imported-recipes.yml` and skips existing recipe IDs
on subsequent imports. An imported recipe can be overridden or disabled in `config.yml`.
Crafting a plushie uses the corresponding item from Allium's Nexo provider.

## Spawner Type Changer

Use `/pt give <player> spawner_changer`. Right-click a creature spawner, select a spawn egg
from your inventory and confirm. A successful change uses one egg and one changer. Allium
checks block protection when the menu opens and again on confirmation. Legacy restricted
mob types still use `allium.spawnerchanger.use.<mob>` or `allium.spawnerchanger.any`.

Customize `plugins/SpawnerCraft/spawner_changer.yml` and restart to apply changes.
The tool appears in `/pt list` and uses the addon action `spawnercraft:change_spawner`.
