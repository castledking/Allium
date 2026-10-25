# Allium Assets — CraftEngine pack

The CraftEngine pack that lets Allium Harvest draw a crop stage as a real
block instead of a display entity.

## What is in here

```
pack.yml                          the pack (namespace: ce)
configuration/blocks/tomato.yml   the crop block: one id, four looks by age
configuration/items/tomato.yml    the seeds and the fruit
sync_art.sh                       copies the tomato art out of the Nexo pack
```

## The crop block

`ce:tomato_crop` has an `age` property (0–3). Each age value points at an
appearance, and each appearance has its own model — the four growth stages.
Allium Harvest owns the growth and writes the age when the stage changes, so
the block itself has no growth behaviour.

A crop stage in Allium looks like this:

```yaml
- block: ce:tomato_crop
  state:
    age: 2
  duration: 60m
```

`state:` names the block's own properties, so the block has to declare them
(see `configuration/blocks/tomato.yml`).

## The art

The models and textures are the ones the server's **Nexo pack** already ships
(`crops/tomato`), copied into this pack so the CE crop looks exactly like the
Nexo one. That art belongs to the server's resource pack, so it is not
committed here — `sync_art.sh` copies it in:

```bash
./sync_art.sh /path/to/plugins
```

### The item/ and block/ split

A sprite's path decides which atlas it lands in, and an item model can only
use sprites from the item atlas. A texture under `textures/crops/tomato/...`
is a block-atlas sprite, so an item model that names it renders **untextured**
while the block model next to it looks perfect. Hence the split:

```
textures/item/crops/tomato/...    the seeds and the fruit
textures/block/crops/tomato/...   the four growth stages
```

The same picture is needed in both atlases when a block model and an item
model both use it, so `sync_art.sh` copies every texture a model names into
the atlas that model is drawn in.

## Deploying

1. Copy this folder to `plugins/CraftEngine/resources/allium_assets/`.
2. Run `sync_art.sh` against the server's plugins folder.
3. `/ce reload all` — CraftEngine only regenerates its resource pack when the
   packs reload.

## One pack for clients

CraftEngine writes its own resource pack to
`plugins/CraftEngine/generated/resource_pack.zip`. ResourcePackManager mixes
that zip into the single pack the server sends, so clients download one pack
instead of one plus CraftEngine's:

```yaml
# plugins/ResourcePackManager/compatible_plugins/craftengine.yml
isEnabled: true
pluginName: CraftEngine
zips: true
localPath: CraftEngine/generated/resource_pack.zip
cluster: false
```

Because of that, CraftEngine's own `send_pack` step is disabled in
`plugins/CraftEngine/config.yml` — the merged pack already contains
everything, including CraftEngine's vanilla blockstate overrides.
