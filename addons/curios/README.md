# Allium Curios

Curios is an Allium addon that requires Allium (API 1.1) and must be placed inside
`plugins/Allium/addons/`. Put `Allium.jar` in the server's top-level `plugins/` directory, put
`Addon-Curios-1.0.0-SNAPSHOT.jar` (or `Curios.jar`) in `plugins/Allium/addons/`, then restart the
server. Allium discovers, validates, and enables addons from `plugins/Allium/addons/` on startup.
If Curios is placed directly in `plugins/`, Allium refuses it and Curios disables itself before
registering anything; without Allium, Curios cannot load (`depend: [Allium]`). Startup should show
Curios enabling, after which `/curios` and `/relics` are available with `curios.use` (granted by
default). Configuration lives in `plugins/Curios/config.yml`: Bukkit gives the addon that data folder
even though its jar sits in `plugins/Allium/addons/`.

A `config.yml` from a build before `config-version: 2` is upgraded on startup, and the old file is
kept as `config.yml.before-v2`. The upgrade adds the sections the file lacks (Bukkit reads a missing
section as empty, which left card names white) and turns `textured-menu` back on, since those builds
wrote it as `false`. A later `textured-menu: false` is kept.

Curios uses Allium for signed items, inventory admission and scheduling.

```sh
./gradlew shadowJar :addons:curios:build :addons:curios:resourcePack
```

The pack task requires Python and PyYAML. Outputs are under `addons/curios/build/`, and
`Addon-Curios-*.jar` bundles `curios-pack.zip` automatically. Every build of the jar also copies it
to `build/libs/addons/curios/Curios.jar` in the root project, beside `build/libs/Allium.jar`.

## Resource pack

Configure `resource-pack.delivery` in `plugins/Curios/config.yml` (restart or run
`/allium reload` after changing; reload ResourcePackManager when using `rspm` or `rpm-export`):

- `allium` (default): Allium merges Curios's bundled pack into `allium-core-pack.zip` at startup, so
  Allium's standalone self-hosting (`resource-pack.owner: standalone` + `self-host.enabled: true`)
  and `rpm-export` deliver Curios's cards, relics, frames, and menus automatically out of the box.
- `rspm`: Allium writes `Curios_resource_pack.zip` straight into ResourcePackManager's mixer
  directory (`resource-pack.rpm-export.mixer-directory`) with the Curios icon and description.
  This works with any Allium pack owner, including `none` and `standalone`.
- `none`: disables automatic delivery so you can host `Curios-resource-pack.zip` yourself.

`textured-menu` defaults to `true` for the SF card frames, tier badges, and menu backgrounds. The
inventory title falls back to plain text on platforms without the Adventure component API.

Frames are font-glyph panels written into the item's own lore, as legacy trading cards were. They
work on every server version, 1.20.1 included, and need no item tooltip styles. The five tiers use
the ornate card art, which the pack builder cuts into caps, centre ornaments and fill strips
(`curios:card_panel`), so each panel is drawn as wide as its widest line plus a 12px margin:
menu buttons fit their text, and cards keep legacy's 200px unless a line needs more. Lines are
measured with the client's default font widths in `font-widths.json`, generated from a client jar by
`pack/tools/font_widths.py` (only the numbers are stored). The other 52 styles use the fixed-width
strips SF cut from their tooltip sprites (`frame-glyphs.json` maps them to `font/frames.json`).
Nothing is rewritten on the way to the client, so creative players see exactly what survival
players see, never a raw `[frame:...]` marker. Every textured card shows SF's
`hyronic_ae_configuration` tier label (simple, elite, ultimate, legendary, fabled) under its title,
in white so the label keeps its own colours. Card titles use legacy's mob colours, which
`card-menu.mob-colours` can override.

Players running the Jade client mod see "Trading Cards" as a card's mod name, not "Minecraft":
cards carry Jade's `$jade:stack` id (`trading_cards:card`), and the pack's
`jade.modName.trading_cards` lang key names it. Jade decides where that blue line goes: under the
item name in the creative inventory, and at the end of the tooltip elsewhere.

## Equipment and progression

- `/relics` opens the equipment menu. Click a curio in your inventory to equip it in a matching
  slot. Click a filled equipment slot to return it directly to an empty inventory slot. A full
  inventory leaves the curio equipped. The menu cancels cursor, drag, hotbar and shift moves.
- In a card's bonus menu, rolling or re-rolling a slot costs money from Allium's economy, on
  legacy's ladder: `cost x escalation^(that slot's rolls so far) x tier multiplier`, rounded to
  100. On a Simple card that is 500, 700, 900, 1,200, 1,700, ...; Elite is 1.5x and Fabled 5x.
  Each slot shows its next price, how often it was rolled and what it cost so far. A roll that
  could change nothing is refused before any money moves, a card that cannot be saved is
  refunded, and clearing a slot keeps its roll count, so the price never resets. Configure it
  under `card-menu.bonus-slot-roll`; `cost: 0` makes rolls free.
- Card messages (equipping, level-ups, drops, the card menus, and results about a card) use
  legacy's gradient `[Cards]` prefix, and so does every reply to `/cards` and `/tradingcards`.
  Relic and import messages keep `[Curios]`.
- When a card comes into your main hand, its title shows above the hotbar in its mob's colour for
  two seconds, as in legacy. It is not resent while you keep holding it, and switching away clears
  it. `card-menu.held-card-action-bar: false` turns it off.
- `/curios inspect` shows the held curio's progress. Kills award configured XP to equipped curios.
  Other plugins can use the `CurioExperience` Bukkit service on the server thread.
- `/curios merge` combines separate copies of one definition. The main-hand curio keeps its
  identity, the offhand donor contributes progress, and the level cap applies.
- `/curios trade` exchanges the held curio for that definition's configured reward. Crafted
  curios are bound, and merging a bound donor binds the result.
- `/frame list` lists styles the player can use. `/frame apply <style>` changes the held
  curio's signed frame. `/cards frame [style]` does the same, as legacy's command did. Imported
  decorative styles retain their legacy permissions.
- Right-click a held card, in the air or at a block, to open its menu. Right-clicking a card in
  your inventory works too, as does right-clicking an equipped card or any card in your inventory
  while `/relics` is open. The one exception is the creative inventory screen: the creative client
  handles those clicks itself and only reports where items ended up, so a right click there never
  reaches the server. Creative players right-click the held card, right-click the card under
  `/relics`, or use `/cards menu`. Menus opened from a click open a tick later, after the cancelled
  click has been corrected, so no ghost card is left on the inventory's cursor.
- `/cards give [player] <entity> [rarity] [level] [quality]` gives a trading card by mob, as
  legacy's `/tradingcards give` did. Rarity defaults to simple, level to 0 and quality to 100.
  `/relics give [player] <relic> [level]` gives a relic. `/curios give` accepts both. Each label
  suggests only its own kind, so `/cards give` never lists relics. These are admin commands
  (`curios.admin`) and also run from the console with a player named.

The bundled catalog has 180 legacy mob cards, the nine enabled Antiquities relics, and
57 frame styles. Card pools roll one tier per successful drop. Relic drop chances can be
set independently for mob deaths and supported loot tables. The Membrane Cowl recipe uses
five phantom membranes. The ring ignition, phantom and piglin truces, Echoing Circlet,
Witch's Brewstone and water retaliation effects can be assigned to any definition.

Art comes from the legacy Relique menu and icons, Antiquities relic sprites, and SF's Nexo
card, frame, label and menu assets. The card home, bonus boost, merge and trade-in screens draw SF's
backgrounds from the `curios:card_menus` font, with invisible buttons over the art, and `/relics`
shows SF's card icon in its card slots. The pack builder checks model, font bitmap, tooltip
sprite and frame panel glyph references.

## Legacy import

Disable the old equipment plugin before migrating. Keep a backup of the source files and point
`migration-directory` to the copy of Relique's `config/relique/entities` directory.
`/curios import [player]` is an admin command for an online player. It reads only the matching
UUID file, stages every slot and writes the loadout and one-time marker together in player
data. Occupied or unsupported slots refuse the whole import. Source files stay available
for inspection.

`/curios import held` replaces a recognized legacy item in the administrator's main hand.
Equipped and held card conversion supports the original typed `alliumcards` tags and keeps
the card family, tier, level, XP, bound flag, quality, rerolls and legacy boost metadata.
Antiquities items are recognized by their known legacy IDs. Arbitrary third-party item components
and slot extensions require an explicit mapping.

The legacy card boost executors, quest and economy bridges, pending payout recovery, chat-hover frame injection, and the other Antiquities effects remain follow-up work.
The addon does not implement custom enchantments.
