# Harvest Addon — Plan

Harvest (crops + soil + sprinklers + fertilizer) becomes
`Addon-Harvest-<version>.jar`. **Kitchen is a separate
`Addon-Kitchen-<version>.jar` depending on Harvest.** Both are ordinary Bukkit
plugins installed in `plugins/`, depending on Allium's versioned API.
The [recode roadmap](roadmap.md) is authoritative for scope and sequencing.

This file preserves the crop-rendering design and reported CraftEngine live-test
observations. These are legacy implementation inputs, not evidence that the recode
already implements these features or passes those runtime tests.

---

## 1. How a crop stage is drawn

Stages use the representations and asset sources below; hybrid stages combine
model and block rendering. Harvest owns the crop — growth clock, path rolls,
drops, regrowth — and asks a *renderer* for the picture.

| Stage kind | Drawn as | Needs |
|---|---|---|
| `model:` | an `ItemDisplay` entity showing an item model | an item that resolves (Nexo, Oraxen, CraftEngine, vanilla) |
| `block:` | a real block standing in the crop's cell | a block provider (CraftEngine today) |
| in-house | an `ItemDisplay` entity showing **Allium's own** model | nothing — the addon ships the model and the texture |

The in-house kind is the point of this plan: a server should be able to run
crops with no other item plugin installed at all.

### The shape of a stage in the crop file

```yaml
stages:
  - model: nexo:tomato_stage_1     # item model stage
    duration: 30m
  - block: craftengine:sf:tomato_crop # provider + pack namespace + block id
    state:
      age: 1
    duration: 45m
```

Stages can be model-only, block-only, or hybrid (a block plus display model).
Legacy `CropDefinitionLoader` and `CropVisualService` support hybrids; preserve
that shape during migration rather than rejecting valid installed definitions.
Each rendering role must be explicit so hybrid stages do not double-drop or
double-handle interactions. `state:` names the block's declared properties. Reject
a stage with neither representation, and diagnose missing resources only after
the relevant provider is ready. A still-loading provider is not a missing block.

---

## 2. The renderer SPI

The rest of the addon must never know which plugin provides the blocks:

```java
public interface CropBlockRenderer {
    boolean isCustomBlock(Block block);
    boolean exists(BlockRef ref);
    boolean matches(Block block, BlockRef ref, Map<String, String> state);
    Map<String, String> stateOf(Block block);   // diagnostics
    boolean apply(Block block, BlockRef ref, Map<String, String> state);
    boolean remove(Block block);
}
```

Providers live in their own package and are only loaded when the plugin is
installed. An unavailable block renderer suspends the affected crop visuals and
mutations with a diagnostic, preserving persisted crop state. It must not silently
create an invisible active crop or delete one. This follows the core provider
service: one adapter per source, explicit readiness, and reload invalidation.

The addon must also be able to *take over* a provider's crop the way it takes
over a Nexo crop: the provider supplies the picture, Allium supplies the
clock, the drops and the regrowth. For CraftEngine that means the block
carries no growth behaviour of its own — Allium writes the block's state
property when the stage changes.

### 2.1 What a block-drawn crop stands on

A CraftEngine custom block has to sit on *some* vanilla block state: that
state is what the client renders, with CraftEngine overriding its model with
yours. There is no "no state at all". The options, from the live test:

| Backing | Shape / behaviour | Looks like |
|---|---|---|
| `auto_state: sugar_cane` | crop: no collision, breaks by hand | the crop in the default pack's reed |
| `state: minecraft:wheat[age=N]` | the canonical crop, eight ages | wheat |
| `auto_state: sapling` | no collision, breaks by hand | a sapling |
| `auto_state: lower_tripwire` / `higher_tripwire` | no collision | CraftEngine's own flower uses these |
| `display_item_block` behaviour | the block's visual is an **item model** drawn by a block entity | CE's equivalent of Nexo's model placement |

The last row is the interesting one: a block that shows an item model instead
of a block model is CE's answer to "I want a model standing in the cell, not
a block". It carries its own state property, so Allium can swap the shown
item per stage exactly like it swaps a display entity's model — the closest
thing to the Nexo path, and the one to try when a block-backed crop has to
look like a model-backed one.


---

## 3. In-house crops (the new thing)

The addon ships its own crop models and textures, so a crop needs no Nexo,
no Oraxen and no CraftEngine.

### 3.1 Assets

```
assets/allium/models/crops/<crop>/stage_<n>.json       shared model geometry
assets/allium/textures/item/crops/<crop>/<texture>.png  item/display texture
assets/allium/textures/block/crops/<crop>/<texture>.png block texture when needed
```

Models use crossed-plane geometry, like a vanilla crop; bindings must point to
the correct item or block texture atlas. Textures are 16×16. Generate legacy
carrier-item custom-model-data overrides for older clients and version-appropriate
item definitions under `assets/allium/items/` for modern clients. Shared geometry
does not remove the need for these separate bindings and atlas-aware textures.

### 3.2 Item ids

In-house crops register stable item IDs in Allium's namespace:

```yaml
seed:
  item: allium:tomato_seeds
```

`allium:tomato_seeds` is registered by Harvest with the core item service. A plain
`ItemStack` carries identity and model metadata (legacy custom model data or a
supported modern item-model component); textures reach clients through the pack.
A crop file can mix sources, such as `allium:tomato_seeds` with a `nexo:tomato`
drop. Harvest consumes the shared Nexo/Oraxen/CraftEngine adapters rather than
shipping another resolver chain.

### 3.3 Getting the assets to the client

Harvest contributes a pack fragment to Allium's shared pack coordination service.
Kitchen contributes its own fragment. The default delivery path on SF is a merged
ResourcePackManager pack; provider/native delivery is disabled by the operator
when RPM owns delivery. Do not rewrite another plugin's settings silently.

Without RPM, support an explicitly configured standalone URL/hash or a provider
merge pipeline. Select one delivery owner, detect model/blockstate collisions,
and generate assets for the supported client pack formats. Do not assume two
independent pack pushes or a required client mod will solve delivery. If required
assets or a renderer are unavailable, report and suspend the affected capability
without losing crop data.

The addon must also ship the *vanilla* overrides it needs. A crop drawn as a
display entity needs nothing; a crop drawn as a block needs the blockstate
overrides for the states it reserves.

---

## 4. The addon boundary

- Core keeps config/messages/sounds, permissions, scheduler/storage contracts,
  command registration, versioned APIs, shared provider adapters and pack coordination.
- Harvest keeps crops, soil, sprinklers, fertilizer, growth, crop storage, renderer
  logic, its asset fragment and crop commands.
- Kitchen keeps cooking/preparation/stations/bags, recipes, its asset fragment and
  its own storage/migrations. Kitchen depends on Harvest; Harvest runs without it.
- Bukkit owns plugin classloading/lifecycle. Addons register through the narrow
  Allium API and clean up on disable.

Provider-specific references belong only in optional adapters. General crop and
Kitchen logic uses shared item and block contracts. Crop-specific renderer logic
stays in Harvest; it need not make core own a crop engine.

---

## 5. What the CraftEngine live test taught us

Live test: a tomato crop converted from `nexo:tomato_stage_*` models to a
CraftEngine block with an `age` property, on a Paper 26.3 server with
CraftEngine 26.9.2 and ResourcePackManager 2.4.6.

1. **The client's pack decides what a block looks like.** When the provider's
   assets are not in the pack the server sends, the client falls back to the
   vanilla model of the state the provider reserved — silently. Our crop
   rendered as a tripwire because the vanilla tripwire state was all the
   client had. Nothing in the server log said so.
2. **One pack, not two.** RPM can mix a provider's generated zip
   (`compatible_plugins/craftengine.yml` → `localPath`), so the client
   downloads one pack. The provider's own "send pack" step then has to be
   switched off, or the client gets the merged pack *and* the provider's.
3. **A provider only regenerates its pack when its packs reload.** The
   provider's zip is regenerated on `/ce reload`, not on server start. A
   server restart alone leaves the zip stale.
4. **The merged pack is rebuilt by RPM's watcher**, not by the server start:
   when the provider's zip changes, RPM notices the new checksum and re-mixes
   and re-sends. There is a stability window before it pushes.
5. **CraftEngine 26.9.x config shape** (the 26.10+ shape is different, and
   the newer source tree is misleading):
   - outer `behavior.type` must be an *item* behaviour (`block_item`), not a
     block behaviour;
   - block states live under `states:` with `properties:`, `appearances:`
     and `variants:` (`age=0` → appearance);
   - `auto_state` picks the vanilla state an appearance is drawn with, and
     each appearance needs its **own** state, or every stage renders the
     same;
   - the client's blockstate override for that vanilla state carries the
     model.
6. **A provider id is namespaced by the *pack* namespace**, not by a free
   label. Item and block registries are separate: the same key may exist in both
   (for example `sf:silver_ore` in the legacy metal pack). Resolve with typed
   `ItemRef` or `BlockRef`, not an exclusive item-versus-block assumption. A pack
   declares `namespace:` in its `pack.yml`.
7. **Third-party art does not belong in a public repository.** The tomato
   models and textures came from the server's Nexo pack; the addon repo
   carries the pack *configuration* and a script that copies the art in.
8. **A sprite's path decides its atlas, and an item model can only use the
   item atlas.** A texture at `textures/crops/tomato/tomato.png` is a
   block-atlas sprite; an item model naming it renders *untextured* while the
   block model beside it looks perfect. Item art has to live under
   `textures/item/...` and block art under `textures/block/...`, and a picture
   used by both needs a copy in each. The in-house crop pipeline has to get
   this right from the start, or half the crops render blank.
9. **Cancelling a break event makes the client put the block back.** The
   client undoes a break the server cancelled, so a crop drawn as a block
   that is "protected" from breaking simply cannot be broken — in survival
   the plant snaps back on every swing, while in creative the client had
   already predicted the break and never restores it, which is why the bug
   looks like "it only breaks in creative". A block-drawn crop has to be
   allowed to break for real; the addon keeps ownership of the crop's state
   and its drops, and the block provider is told not to drop the block on top.
10. **A block provider needs a client-side translation story too.** Nexo
    puts item names in the resource pack, so the client's language decides
    them; CraftEngine has the same thing
    (`configuration/langs/<locale>.yml`, items name their label with
    `<lang:item.<namespace>.<id>>`). Whichever provider an addon supports,
    the crop's names have to be translatable the same way.

---

## 6. Work items

- [ ] Extract crop code into Harvest; remove Kitchen initialization from Harvest.
- [ ] Extract Kitchen as a separate plugin depending on Harvest, including world
      station/item/entity/chunk PDC and in-progress cooking migration.
- [ ] Define plugin dependency/API metadata and the narrow Harvest extension API.
- [ ] Port `CropBlockRenderer` with explicit unavailable/loading behavior.
- [ ] Use the core item service for built-in, vanilla, Nexo, Oraxen and CraftEngine.
- [ ] Generate Harvest/Kitchen pack fragments with stable models/textures.
- [ ] Use core pack coordination for RPM registration or explicit standalone delivery;
      document the single delivery owner and provider pack rebuild/reload sequence.
- [ ] Crop file format: `model:` / `block:` / in-house, and the `state:` map.
- [ ] Diagnostics: `/hf crop inspect` shows the wanted and the actual block
      state, so a crop that renders the wrong stage says why.
- [ ] Decide the first in-house crop set (tomato first — it is the one the
      live test used).
- [ ] Storage: the crop tables move to the addon's own namespace.

## 7. Open questions

- Does each in-house crop require actual block physics, or is a display plus
  an `Interaction` hitbox sufficient? Legacy `CropVisualService`/`CropListeners`
  already support punching display-backed crops using interaction entities;
  preserve that provider-free path rather than assuming displays are unbreakable.
- Should the in-house art be a separate download (a "crop pack") so a
  server can pick which crops it ships?
The server pack carries crop assets. A client companion is outside the current
roadmap and is not a prerequisite for Harvest.
