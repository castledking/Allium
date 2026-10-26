# Allium crop stages for CraftEngine

Allium owns planting, growth, harvests and display entities. CraftEngine owns
plant-stage items/models and the invisible `ce:crop` / `ce:tomato_crop` block
carriers. The carriers use lower tripwire so fluids can replace them.

`configuration/items/crop_stages.yml` contains the plant displays. Every stage
of a crop has the same plain crop name, including Tomato. Existing seed and
produce references stay in their current providers. Giant produce used as a
plant gets a separate display item, so its harvested item keeps its name/model.

## Import existing stage art

The server's art is not committed here. Take a backup of these directories
before migrating:

- `Nexo/items/sf/crops/`
- `Nexo/pack/assets/`
- `Allium/harvest/crops/`
- `CraftEngine/resources/allium_assets/`

Run the importer against that pre-migration plugins snapshot:

```sh
python3 craftengine/allium_assets/import_nexo_crops.py /path/to/backup/plugins \
  --output /tmp/allium-crop-migration
python3 -m unittest discover -s craftengine/allium_assets -p 'test_*.py'
```

The output must be empty. The script stages files without modifying the input:

- CE stage definitions, models, parent models and unchanged texture copies;
- Allium crop configs with only stage models and visual settings changed;
- Nexo crop item files with stage entries and their old planting/evolution
  furniture mechanics removed;
- `migration.json`, listing measured model alignment and Nexo assets to archive.

Nexo seed/produce item definitions remain, including their models, names and
custom model data. Shared art stays in Nexo if a retained model uses it.

Stages which use a vanilla block texture, such as pitaya's spruce support,
copy their custom sprites into `textures/block/` too. Each baked model must
use one atlas, including inherited textures. Physical paths keep a later
Nexo overlay from undoing the repair by replacing CE's atlas filter.

## Model placement

An item display renders model geometry around its center, then applies the
model's selected item-context transform. Different authors use different
translations, scales and rotated element origins. One shared Y offset cannot
correct all of them.

The importer measures inherited geometry after element rotation and the
GROUND context transform. It adjusts that context's Y translation so the
rendered base is zero, while preserving scale and orientation. Allium then
anchors that base to the actual supporting block's top:

```yaml
- model: ce:onion_stage_1
  duration: 30m
  visual:
    transform: GROUND
    scale: 1.0
    y-offset: 0.001
    align-to-soil: true
```

The small offset avoids coplanar faces flickering against the soil. Farmland's
lower surface is respected. Unmigrated configs retain the old baseline unless
`align-to-soil` is enabled. The normalized base stays put when display scale
changes. `sync_art.sh` refreshes retained tomato seed/produce art only; it does
not clear the pack or overwrite normalized plant models.

Stages use a native CraftEngine item display and Allium's interaction entity;
there is no tripwire block carrier. `add_crop_hud.py` removes the legacy
`ce:crop`/`ce:tomato_crop` carrier entries from fresh crop configs while keeping
their seeds, drops and growth settings. The entity anchor stays inside the
crop cell and model translation keeps its base aligned with the soil. Each
stage has a plain crop name and Jade identity; GUI context previews that same
growth stage with a fitted transform while world rendering stays unchanged.
The pack supplies `jade.modName.ce: Crops` for Jade's blue source label.

## Deployment order

1. Merge the staged CE files into `CraftEngine/resources/allium_assets/` and
   reload CE configuration, making the new item IDs available.
2. Install the Allium JAR with soil alignment, replace its staged crop configs,
   and reload Allium. Crop IDs, paths and stage indexes stay the same, so
   existing crops reconcile to their new models.
3. Install the staged Nexo item files. Archive the exact asset paths listed in
   `migration.json` outside Nexo's loaded pack and reload Nexo items/pack.
4. Regenerate the CE pack, then rebuild ResourcePackManager's combined pack.

## One pack sent to clients

ResourcePackManager merges `CraftEngine/generated/resource_pack.zip` using:

```yaml
# ResourcePackManager/compatible_plugins/craftengine.yml
isEnabled: true
pluginName: CraftEngine
zips: true
localPath: CraftEngine/generated/resource_pack.zip
cluster: false
```

CraftEngine has two independent automatic delivery paths. Disable both:

```yaml
# CraftEngine/config.yml
resource-pack:
  delivery:
    send-on-join: false
  supported-version:
    min: 26.1.2
    max: latest
  workflows:
    default:
      trigger: reload_pack
      steps:
        - type: generate
        - type: validate
        - type: zip
          path: ./generated/resource_pack.zip
          store-png: false
        - type: upload
          pack: default
          path: ./generated/resource_pack.zip
```

There is no `send_pack` step. Uploading does not deliver a pack to players.
The login flag also disables CE's separate configuration-phase send. Keep
Nexo's pack server disabled and Allium's resource pack disabled when using
ResourcePackManager as the sender.
