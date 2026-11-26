# Allium Curios

Curios is an Allium addon that requires Allium (API 1.1) and must be placed inside
`plugins/Allium/addons/`. Put `Allium.jar` in the server's top-level `plugins/` directory, put
`Addon-Curios-1.0.1.jar` in `plugins/Allium/addons/`, then restart the
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
to `build/libs/addons/Curios.jar` in the root project, beside `build/libs/Allium.jar`.

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
- Right-click a held card or relic in the air or on a block to equip it. Both this shortcut and
  equipping through `/relics` confirm the equip in chat.
- In a card's bonus menu, rolling or re-rolling a slot reserves funds from Allium's economy under
  a stable operation ID, on legacy's ladder: `cost x escalation^(that slot's rolls so far) x tier multiplier`,
  rounded to 100. On a Simple card that is 500, 700, 900, 1,200, 1,700, ...; Elite is 1.5x and Fabled 5x.
  Each slot shows its next price, how often it was rolled and what it cost so far. A roll that
  could change nothing is refused before any money is reserved, a card mutation receipt is saved
  with player data before capture so crashes and failed captures/releases reconcile automatically,
  and clearing a slot keeps its roll count, so the price never resets. Configure it under
  `card-menu.bonus-slot-roll`; `cost: 0` makes rolls free.
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
- `/curios trade` exchanges the held curio for that definition's configured reward, and the Trade In
  menu persists a recoverable claim in player data alongside card removal so a hard stop or full
  inventory never loses the payout (`/curios claim` and player join deliver any pending rewards). Crafted
  curios are bound, and merging a bound donor binds the result. A card pays its mob's heads, as
  many as its quality band's `heads`, as legacy did: SpawnerCraft's head when SpawnerCraft is
  installed (through the `CardTradeHeads` Bukkit service), else the vanilla head (zombie,
  skeleton, piglin, ...). A mob with neither pays the card's `trade-item`. Set
  `card-menu.trade-in-pays: item` to always pay the `trade-item`.
- `/frame` shows your active automatic frame and why it was selected (admin override, personal
  choice, permission default, or fallback). `/frame list` lists styles the player can use (marking
  the active one with `*`). `/frame choose <style>` (alias `select`) saves your preferred default
  frame among styles you have permission for (`plugins/Curios/frames-choices.yml`), and
  `/frame reset` clears your personal choice back to the permission default.
- `/frame apply <style|auto|off>` changes the held curio's signed frame. On any other item it
  writes a `[frame:<style>]` marker as the first lore line (`auto` for each viewer's own frame,
  `off` to remove it). `/cards frame [list|choose <style>|reset|<style>]` works as well. A marker
  written by hand, or by another plugin's lore, frames the item too: with PacketEvents installed,
  Curios draws the frame on the copy each player is sent, so the item on the server keeps its own
  lore. Creative players are sent the item unframed, because a creative client sends items back as
  it was shown them. Imported decorative styles retain their legacy permissions.
- Staff with `curios.admin` or `allium.frame.admin` can override a player's frame with
  `/frame set <player> <style>` and clear it with `/frame reset <player>` (persisted in
  `plugins/Curios/frames-overrides.yml`, matching legacy's format). Resolution precedence is
  admin override -> player choice (if still permitted) -> first permitted frame in priority order
  -> `fallback-frame` (default `simple`).
- Custom frames can be authored in optional `plugins/Curios/frames.yml` (supporting `card:` tier
  aliases, `parent:` aliases to any bundled style, or custom `background:`/`frame:` tooltip sprites
  cut from a `pack:` folder via `/frame build` into `assets/curios/font/custom_frames.json` and
  `plugins/Curios/frames-built.json`). Run `/frame reload` to re-read `config.yml`, `frames.yml`,
  `frames-built.json`, and stored player overrides/choices without restarting.
- Right-click a trading card in your inventory, including in creative, or in an equipped slot
  in `/relics` to open its menu. In the creative inventory, picking up a single card with either
  mouse button opens its menu because both clicks report the same slot update. Use `/cards menu`
  to open the held card's menu by command.
- Relic tooltips show their current frame, configured effects and attributes. Each bundled relic
  has a themed name color. Override `name-color` and the `description` list under `items.<id>`.
- `/cards give [player] <entity> [rarity] [level] [quality]` gives a trading card by mob, as
  legacy's `/tradingcards give` did. Rarity defaults to simple, level to 0 and quality to 100.
  `/relics give [player] <relic> [level]` gives a relic. `/curios give` accepts both. Each label
  suggests only its own kind, so `/cards give` never lists relics. These are admin commands
  (`curios.admin`) and also run from the console with a player named.

The bundled catalog has 180 legacy mob cards, the nine enabled Antiquities relics, and
57 frame styles. Card pools roll one tier per successful drop. Relic drop chances can be
set independently for mob deaths and supported loot tables. The Membrane Cowl recipe uses
five phantom membranes. The ring ignition, phantom and piglin truces, Echoing Circlet,
Witch's Brewstone, water retaliation, Evoker's Mantle Vex summon, Strider Hide Girdle lava
walker, Chorus Buckle safe blink, and Iron Golem/Ravager knockback immunity effects — along
with passive attribute modifiers and per-relic effect parameters — can be assigned to any
definition.

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

## Feature status and legacy inventory

Curios separates active equipment, card, and frame support from intentionally omitted legacy
features and remaining follow-up bridges:

| Area | Status | Notes |
| --- | --- | --- |
| Relics equipment (`/relics`), 7 slots, 9 Antiquities relics, attributes & effects | **Implemented** | All 9 Antiquities relics with passive attributes and active effects: Piglin Signet, Echoing Circlet, Idol of Depths, Evoker's Mantle (`summon_vex`), Strider Hide Girdle (`lava_walker`), Membrane Cowl, Chorus Buckle (`chorus_blink`), Blaze Ember Ring, Witch's Brewstone, plus assignable `golem_knockback_immunity` and legacy Relique import. |
| Trading cards, 5 tiers, quality bands, bonus rolls, merge & trade-in | **Implemented** | 180 bundled mob cards, tier-based signature stats, textured GUIs, Jade mod-name metadata, held-card action bar, economy-reserved bonus rolls, durable recovery store for trade-ins/rolls, `CardCrafting` & `CurioExperience` services. |
| Card boost runtime executors & cross-addon service | **Implemented** | Zero-dependency AuraSkills reflection bridge (non-persistent stat modifiers with join load retry) plus vanilla attribute/combat/regen/XP fallback when AuraSkills is absent; vanilla attributes (`armor`, `haste`, `knockback`, `scale`, `flight-speed`); combat disarm, XP drop & loot listeners; BeastTokens reflective drop bridge; and `CurioBoosts` service (API 1.7) consumed directly by `Crops` (`crop-boost`) and `PartyLocator` (`waypoint-range`, `waypoint-multiplier`). |
| Item & chat hover frames, player choice & custom authoring | **Implemented** | 57 bundled styles, `ChatHoverFrame` service (API 1.6), PacketEvents item framing, `/frame` choice/overrides (`frames-choices.yml`, `frames-overrides.yml`), optional `frames.yml` authoring with `/frame build` and `/frame reload`. |
| Legacy FABLED morphs (`LibsDisguises`) | **Intentionally omitted** | No disguise dependency or morph command is registered in Curios. |
| Custom enchantments | **Intentionally omitted** | Handled outside Curios. |
