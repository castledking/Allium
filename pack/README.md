# Core resource pack

This directory is the source of Allium's core resource pack. The build generates the pack and
bundles it in `Allium.jar`, under `pack/allium-core-pack.zip` with `.sha1` and `.json` info files.
Delivery is configured by `resource-pack.owner` (default `none`); see
[pack delivery](../docs/pack-delivery.md). There, `resource-pack.pack-file` delivers a local build
with other art, such as SF's from `tools/sf_export.py`, instead of the bundled one.

| Path | What it is |
| --- | --- |
| `manifest.json` | Asset manifest: stable item IDs, carrier items, `custom_model_data` allocations, model and texture paths, and texture provenance and license |
| `assets/` | Distributable textures, all original work for Allium |
| `tools/draw_textures.py` | Source of the original pixel art; `--check` verifies the committed PNGs |
| `tools/sf_inventory.py` | Read-only inventory of SF's Nexo items, Nexo pack and RPM mixer; writes [`docs/s06b-sf-inventory.md`](../docs/s06b-sf-inventory.md) |
| `tools/sf_export.py` | Local recipe that builds the pack with SF's server-only art into the git-ignored `build/sf-export/`; never commit its output |

## Allocation

Allium reserves `custom_model_data` 2577000–2577999 on its carrier items, and every core tool owns
ten values. Floats hold every integer below 2^24 exactly, so 1.21.4+ dispatch matches these
values exactly. Planned tools reserve their values but generate nothing until their art exists.

| Tool | Carrier | Allocation | States |
| --- | --- | --- | --- |
| `allium:handcuffs` | iron shovel | 2577010–2577019 | planned |
| `allium:mob_disarmer` | golden hoe | 2577020–2577029 | full 2577020, two_thirds 2577021, one_third 2577022, empty 2577023, broken 2577024 |
| `allium:phantom_obliterator` | blaze rod | 2577030–2577039 | planned |
| `allium:item_renamer` | name tag | 2577040–2577049 | planned |

Allium's built-in item definitions read the carrier, default model data and fallback name from
the bundled manifest, so the pack and the items cannot drift apart. The disarmer's charge state is
a model selection on the same stack: each state write sets the stack's `custom_model_data`, and
the item never changes.

## What Allium stamps

On every version Allium makes the same Bukkit calls: the carrier material, `setCustomModelData`
and the gold **Mob Disarmer** name. The wire format differs by version:

| Server | Model data | Name |
| --- | --- | --- |
| 1.20.x | NBT `CustomModelData: 2577020` | `display.Name` |
| 1.20.5 to 1.21.3 | `minecraft:custom_model_data` int | `minecraft:custom_name` |
| 1.21.4+ (incl. 26.3) | `minecraft:custom_model_data={floats:[2577020.0f]}` | `minecraft:custom_name` |

Allium does not stamp `item_model`. A client without the pack renders an unknown `item_model` as
the purple-and-black missing model. Without one, a player who declines the pack sees a golden hoe
with its name, and it still works. The pack still provides `item_model` targets
(`allium:mob_disarmer`) for any server that opts into that component.

## Layout

| Files | Read by | Purpose |
| --- | --- | --- |
| `assets/minecraft/models/item/golden_hoe.json` | 1.20 to 1.21.3 | vanilla model plus `custom_model_data` overrides. The value after Allium's run closes back to a vanilla copy (`allium:item/vanilla/golden_hoe`), so other plugins' higher values are not swallowed. 1.21.4+ ignores `overrides`. |
| `assets/allium/models/item/mob_disarmer/<state>.json` + textures | all | one handheld model per state |
| `assets/allium/models/item/mob_disarmer.json` | 1.21.2, 1.21.3 | `item_model allium:mob_disarmer` target (`models/item/<id>`) with the same overrides |
| `allium_item_definitions/assets/minecraft/items/golden_hoe.json` | 1.21.4+ | vanilla item definition with a `range_dispatch` on `custom_model_data`, closing back to `minecraft:item/golden_hoe`; its fallback is the exact vanilla definition |
| `allium_item_definitions/assets/allium/items/mob_disarmer.json` | 1.21.4+ | `item_model allium:mob_disarmer` target |

`pack.mcmeta` declares `pack_format 15`, `supported_formats [15, 97]` and
`min_format 15` / `max_format 97` (any 97.x). It has one overlay with `formats [46, 97]` and
`min_format 46` / `max_format 97`. The rules come from each version's own code and Mojang's
changelogs, not from guesses:

| Client | Resource format | How it reads this `pack.mcmeta` |
| --- | --- | --- |
| 1.20, 1.20.1 | 15 | only `pack_format` (1.20.1 client knows no other key). Overlays are ignored. |
| 1.20.2 to 1.21.8 | 18 to 64 | `pack_format` plus lenient `supported_formats` (which must contain it), and overlays with `formats` and `directory` (1.21.8 `PackMetadataSection`, `OverlayMetadataSection`, `Pack#getDeclaredPackVersions`; 23w31a notes) |
| 1.21.9+ (26.3) | 69.0+ (26.3 is 97.1) | `min_format`/`max_format`. Because the pack also reaches format 64 or lower, `pack_format` and `supported_formats` are still required: `supported_formats` must start at `min_format` and end at `max_format` or 64. Every overlay needs `formats` while any overlay reaches 64 or lower. A single-integer `max_format` covers every minor. (26.3 `PackFormat.IntermediaryFormat#validate`; 25w31a notes: minor bumps are backwards-compatible.) |

Format numbers come from the `version.json` in Mojang's client jars: 1.20.1 = 15, 1.20.2 = 18,
1.21.2 = 42, 1.21.4 = 46, 1.21.8 = 64, 1.21.9 = 69.0 and 26.3 = 97.1. `item_model` resolving
to `models/item/<id>` is from 24w36a. `items/` definitions, `range_dispatch` and float
`custom_model_data` are from 24w45a, and the 26.3 and 1.21.4/1.21.8 client model readers
confirm `overrides` is no longer read.

## Determinism and validation

The generator (`codes.castled.allium.pack.CorePackBuilder`, run by `./gradlew corePack` as part
of `build`) writes files in sorted order, uncompressed, with one fixed timestamp and no extra
fields. Identical inputs therefore give a byte-identical zip on any JDK and machine.
The build fails if any texture's bytes differ from the sha256 recorded in the manifest.

`PackValidator` checks that every JSON file parses and every PNG is a square multiple of 16. It
checks that allocations (used and reserved) are disjoint and inside the block, that every file
is reachable from an entry point, and that dispatch tables hold only manifest values and their
closers. It then checks each client version against that version's real client jar:

- `pack.mcmeta` is read under that version's rules.
- Every model and texture reference resolves in the pack or in that version's vanilla assets.
- Every replaced vanilla file equals vanilla apart from the dispatch.
- Each allocation renders the right model through the carrier and through `item_model`, and nearby or absent values stay vanilla.

```sh
java -cp build/libs/Allium.jar codes.castled.allium.pack.CorePackMain validate build/pack/allium-core-pack.zip \
  pack/manifest.json --lane 26.3 97.1 /path/to/26.3-client.jar
```

## Coexisting with other packs

The pack replaces the vanilla `golden_hoe` model (1.20 to 1.21.3) and item definition (1.21.4+).
Another pack that replaces the same files must be merged with this one, or one of them loses its
golden-hoe models. `docs/s06b-sf-inventory.md` lists such files in SF's mixer. None touch the
golden hoe today. SF's Nexo pack replaces `items/blaze_rod.json` with an unclosed
`range_dispatch` from 1002, so the planned Phantom Obliterator carrier must be settled before its
art ships.
