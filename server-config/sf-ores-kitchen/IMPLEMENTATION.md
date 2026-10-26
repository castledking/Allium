# CraftEngine ores and the deep-dish kitchen

Allium again supports deterministic custom ore veins, automatic generation in
fresh resource-world chunks, and explicit pre-generation. The generation code
is adapted from the implementation removed in `e8d1da7`; placement uses
CraftEngine's block API instead of Oraxen note-block reflection.

## Configuration

`ore-generation.enabled` gates all ore writes. The packaged default is false
until the accompanying CraftEngine pack is installed. `worlds: [Resource]`
limits automatic generation, which listens to chunk population, not ordinary
chunk loads. Loading or reloading Allium never starts a pregen job.

`ore-generation.ores` contains salt, silver and galena. Each entry accepts
`block`, `deepslate-block`, `vein-size` (1..64), inclusive `min-height` and
`max-height`, `chance-per-chunk` (0..1), and `veins-per-chunk` (1..32).
`lowland-max-height` and `single-above` preserve silver's mountainous behavior.
World build limits clamp each height range. Existing legacy ore sections and
`oraxen-id` remain readable as `sf:<id>` references; they do not enable a
note-block fallback.

Every ore gets a persistent per-chunk marker. Existing
`allium_ore_generated` markers are also respected. Missing CraftEngine
definitions or failed placements leave the marker unset and never substitute
generic note blocks. Only natural stone and deepslate are replaced. Veins stay
inside their chunk and use a stable world/chunk/ore seed.

## Commands

Operator permission: `allium.pregen` (or individual action permissions).

- `/pregen start <radius-in-chunks> <world>` uses the player's chunk in that
  world, otherwise the world's spawn chunk.
- `/pregen world <world> <radius-in-chunks> [center-chunk-x center-chunk-z]`
- `/pregen pause`, `/pregen resume`, `/pregen stop`, `/pregen status`
- All forms also work through `/allium pregen`.

The square includes its edges: radius 1 covers nine chunks. Chunk requests are
asynchronous and bounded by `pregen.chunks-per-tick` (1..16); dispatch is paced
by `pregen.delay-ticks`. Block work runs on the chunk's owning scheduler.
Completion waits for outstanding work. Stop, reload and disable invalidate
pending placement callbacks. `pregen.generate-ores: false` runs terrain pregen
without custom ores. SF's existing false value is preserved.

## Blocks and mining

The pack in `server-config/sf-ores-kitchen/CraftEngine/resources/sf_ores`
defines six ore blocks, salt, raw silver/galena, and their ingots. Deepslate
metal models use the recovered separate end textures from `work/`. All ores
use CE-allocated mushroom carrier states so they do not consume Nexo note-block
states. The CE cache owns the allocations; do not delete or reassign it.

Salt accepts wooden pickaxes; silver and galena require iron or stronger.
Their original hardness is 4 (stone) and 5 (deepslate). CE handles correct-tool
checks, Silk Touch, Fortune and drops. Twelve native CE recipes smelt or blast
raw metals and both ore variants into ingots. An Oraxen-specific smelting
listener is unnecessary.

`settings.destroy_stages` uses CE's `internal:destroy_stages` template, whose
ten item models use Minecraft's existing `block/destroy_stage_0..9` textures.
Crack displays exist only while mining; ores are real state-carried blocks.
The old global note-block physics listener is not restored.

## Jade names

The original handoff's proposed `jade:receive_data` name override does not work
for ordinary ore carriers. Jade only requests applicable registered providers;
`ObjectNameProvider.BlockData.shouldRequestData` requires a suitable block
entity. `serversideRep` is assembled on the client by
`DatapackBlockManager.override`, not received as an arbitrary NBT field.

Allium instead uses that supported display-item discovery path. For a player
who announces Jade protocol `9`, one packet-only item display follows the
looked-at configured ore within six blocks. Its CE item supplies the real name
and icon, and `$jade:stack.id` supplies its identity. The display has zero scale
and no shadow; it is never a Bukkit/world entity. Looking away, changing
world, disconnecting or disabling Allium destroys it. There are no saved
entities per ore or per chunk, and no replacement server handshake that would
interfere with another Jade server plugin.

This is based on [Jade 26.3's display discovery](https://github.com/Snownee/Jade/blob/26.3-fabric/src/main/java/snownee/jade/overlay/DatapackBlockManager.java)
and [object-name provider](https://github.com/Snownee/Jade/blob/26.3-fabric/src/main/java/snownee/jade/addon/core/ObjectNameProvider.java).
PacketEvents is optional and required only for this overlay. Older Jade
protocols are not advertised as verified. Clients already connected during a
plugin reload are detected from their registered Jade channels; if a client
does not expose those channels it must reconnect to repeat its handshake.
An actual client-side HUD/texture inspection remains a gameplay check.

## Kitchen

`harvest/kitchen.yml` now accepts `crafting` and `smelting` recipe sections.
Ingredients and results use the existing item resolver, including `nexo:`, CE
pack namespaces such as `sf:`, and `minecraft:`. An ingredient can be a list
of alternatives. Recipes use exact item choices, so ordinary base-material
items cannot stand in for custom ingredients. Cooking supports furnace,
smoker and blast-furnace types. Reload/disable removes this module's recipes
before adding the current definitions.

A pie type can specify its own `crust-item`, `crust-model` and assembly `steps`.
It may also set `opens-on-standard-crust: true`; then its first ingredient
appears beside egg in the starting hologram and selects that recipe. Optional
`progress-models` map to the incomplete states after each ingredient, before
the finished cold model. Unfinished dishes refund the correct base and added
ingredients. Existing pie IDs, PDC state, cooling, baking, slice eating and
permissions remain in use.

SF's additions are:

- Two tomatoes of any normal/silver/golden quality plus a bowl make tomato sauce.
- A milk bucket plus `sf:salt` makes cheese; vanilla crafting returns the bucket.
- Three dough make a deep-dish base.
- Place a pie crust and choose spinach from the cycling hologram. Add cheese,
  then tomato sauce to finish; no dough cap is added. Spinach is a shallow
  green layer inside the crust, cheese is a higher white layer, and the final
  sauce sits just below the rim. The dark red topping comes from the open
  cranberry-filled pie texture and stays on cold, baked and sliced pizzas.
- The baked item is named `Deep Dish Pizza (Sauce on Top)` and shares the
  four-slice eating and ten-minute cooling behavior.

Pizza models reuse the hollow pie crust and cranberry pie bite shapes.
Ingredient sprites are copied from local Croptopia (cheese/salt) and
Farmer's Delight (sauce) sources; the dark cranberry topping texture is reused
unchanged. Recovered metal textures come from `work/`.

## Validation and rollback

Run `python3 server-config/sf-ores-kitchen/test_pizza_models.py` after regenerating
the pizza assets. These checks cover inset layer bounds, cranberry sauce on
cold/baked/sliced models, shrinking bite states, and the three-ingredient recipe.

The complete Maven suite passed 711 tests. New regressions cover deterministic
veins, chunk/height bounds, small veins, zero probability, unavailable blocks,
failed placements, legacy markers, stop/disable guards, pregen traversal and
limits, Jade handshake decoding, and separate savory/sweet pie assembly.

An isolated Paper 26.3 server verified all six block placements, their tool
requirements, ten-stage crack overlays, five ingredient/ingot items, and twelve
native metal recipes. SF was restarted with the user-supplied CraftEngine
26.9.2 JAR, matching the tested JAR's SHA-256. Live checks passed for mixed
tomato qualities, rejecting plain-paper substitutes, all pizza bite models,
separate pizza/sweet-pie assembly, milk plus CE salt crafting Nexo cheese, and
all three ore definitions ready with pregen idle. Temporary probe plugins were
unloaded and removed.

The combined Java pack contains the CE ore assets and Nexo pizza models; the
obfuscated pizza model's texture references all resolve to bundled PNGs. The
Bedrock conversion completed with 22,054 mappings and no new-ore stitching
warning after bundling the vanilla texture copies. In-game HUD appearance and
the final models still need a client-side visual check.

The custom models bundle five unchanged Minecraft textures in the custom
namespace because the Bedrock stitcher cannot resolve unshipped vanilla
texture references. Vanilla blocks themselves are not overridden. The inset
salt crystals also use positive-depth geometry.

SF originals are backed up under `/tmp/allium-ce-ores-20261025/backup/`.
The staged deployment is beside it in `stage/`. No pregen job is started by the
deployment; automatic ore generation affects newly populated `Resource`
chunks. Generated terrain should be backed up before manually running pregen.
