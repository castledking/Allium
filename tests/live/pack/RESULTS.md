# S06b core resource pack gate

**Automated gate PASS on 2026-11-06.** The visual result is still open: run the
[manual checklist](VISUAL-CHECKLIST.md) in a real client on each lane.

- The pack validates cleanly against Mojang's client jars for seven versions, from 1.20.1 to 26.3.
- Its SHA-1 is identical across five builds.
- A protocol bot loaded it cleanly on Spigot 1.20.1, Paper 1.21.8 and Paper 26.3: 125 live
  checks, 0 failures.

Hashes, versions and run paths are in the [manifest](evidence/s06b-manifest.json). Run directories
with server/client logs, bot downloads and validator reports are under
`/tmp/allium-s06b-20261106/runs/*-final2`. They are not committed.

## The pack

| | |
| --- | --- |
| File | `build/pack/allium-core-pack.zip`, bundled in `Allium.jar` as `pack/allium-core-pack.zip` |
| Size | **7,055 bytes**, 16 entries (stored, uncompressed) |
| SHA-1 | `2413dc172492b961ccd67270f0e6a840ab35226b` |
| Contents | Mob Disarmer: five state models and textures, the 1.21.2 `item_model` target, the golden-hoe carrier model with overrides and a vanilla copy, plus an overlay with the 1.21.4+ item definitions |

**Stable SHA-1:** `CorePackMain build` on OpenJDK 17, 21 and 25, plus `./gradlew build` and
`./gradlew clean build`, all produced byte-identical zips. The local SF export (not committed)
also rebuilt to the same SHA-1, `417076f4…`.

## Validation against Mojang client jars

`CorePackMain validate` read the pack as each version's client does. Every version was
compatible, with 0 errors and a clean structure check:

| Version | Client format | Pack range as read | Overlay applied |
| --- | --- | --- | --- |
| 1.20.1 | 15 | 15 | none (format 15 ignores overlays) |
| 1.20.2 | 18 | 15..97 | none |
| 1.21.2 | 42 | 15..97 | none |
| 1.21.4 | 46 | 15..97 | `allium_item_definitions` |
| 1.21.8 | 64 | 15..97 | `allium_item_definitions` |
| 1.21.9 | 69.0 | 15.0..97.* | `allium_item_definitions` |
| 26.3 | 97.1 | 15.0..97.* | `allium_item_definitions` |

On each version the validator checks the following:

- `pack.mcmeta` is read under that version's rules.
- Every JSON file parses.
- Every model, parent, override and texture reference resolves in the pack or in that version's vanilla assets.
- The replaced `golden_hoe` model and item definition equal vanilla apart from the dispatch.
- Every file is reachable from an entry point.
- Allocations (used and reserved) are disjoint and inside the 2577000–2577999 block.
- Every PNG is a square multiple of 16.

A render simulation also checks that values 2577020–2577024 select full, two_thirds, one_third,
empty and broken through the golden hoe, and through `item_model allium:mob_disarmer` from
1.21.2. It checks that no model data, 1, 2577019, the closing value 2577025, values past the
block and 2^24−1 all stay vanilla.

## Live resource load

Each lane's own `server.properties` offered the pack from a loopback HTTP server. Allium did not
send it: the server logged that the pack is bundled and that the delivery owner is `none`. The
bot accepted the pack and downloaded it. It checked the SHA-1 against the offer and ran the
validator above against that version's client jar before reporting its status. A second
connection declined.

| Lane | Client | Bot sent | Server recorded | Decline | Checks |
| --- | --- | --- | --- | --- | --- |
| Spigot 1.20.1 (3871-d2eba2c), Java 17 | minecraft-protocol 1.68.0, protocol 763 | ACCEPTED, SUCCESSFULLY_LOADED | events ACCEPTED, SUCCESSFULLY_LOADED | DECLINED | 43 |
| Paper 1.21.8-60, Java 21 | minecraft-protocol 1.68.0, protocol 772 | ACCEPTED, DOWNLOADED, SUCCESSFULLY_LOADED | final status SUCCESSFULLY_LOADED | DECLINED | 44 |
| Paper 26.3-141, Java 25 | owned raw client, protocol 777 | ACCEPTED, DOWNLOADED, SUCCESSFULLY_LOADED | final status SUCCESSFULLY_LOADED | DECLINED | 38 |

Paper sends `server.properties` packs during configuration. There it fires no
`PlayerResourcePackStatusEvent`, and keeps only the connection's last status (read via
`Player#getResourcePackStatus`). The configuration task finishes only on a terminal status for
the configured pack id, so the bot reaching the world confirms the server received it. Each
accepted connection downloaded the pack exactly once; the declining one downloaded nothing and
kept playing.

**What Allium stamps.** The gate created real disarmers in every state through `ItemService` and
read them back:

- **Every lane:** a `GOLDEN_HOE` named `§6Mob Disarmer` (non-italic gold), with model data
  2577020–2577024 and the verified `allium:mob_disarmer` behavior. Allium stamped no `item_model`.
- **1.20.1:** the model data is NBT `CustomModelData:2577021`.
- **1.21.8 and 26.3:** the component is `minecraft:custom_model_data={floats:[2577021.0f]}`. The
  1.21.8 client decoded `custom_model_data {floats:[2577020…2577024]}` and no `item_model`.

## Scope and limits

- **Visuals:** the bot does not render. A clean load here means the bytes match the offer and
  Allium's validator passed against the real client jar. Your manual checklist is the visual gate.
- **Format rules:** 1.21.9 was checked with the 26.3 rules; no 1.21.9 server lane was run.
  1.20.2, 1.21.2 and 1.21.4 were validated against their client jars only.
- **Tool coverage:** only the Mob Disarmer generates assets. Handcuffs, Phantom Obliterator and
  Item Renamer hold allocations, and SF's Nexo `items/blaze_rod.json` would conflict with the
  Phantom Obliterator carrier as noted in the inventory.
- **Charge refresh after recharge:** a recharged disarmer keeps its empty model until its state is
  next written (right-click air or a disarm).
- **Delivery:** not implemented. `resource-pack.owner` accepts `none`, `standalone` or `rpm-export`;
  only `none` acts, and the others warn.

## Rerun

```sh
./gradlew build
python3 tests/live/pack/run.py --lane spigot-1.20.1 --run-name <name>
python3 tests/live/pack/run.py --lane paper-1.21.8 --run-name <name>
python3 tests/live/pack/run.py --lane paper-26.3 --run-name <name>
```

The runs need the Mojang client jars in `/tmp/allium-s06b-20261106/clients/`, downloaded from
piston-meta and checked against Mojang's SHA-1, as recorded in the manifest. They also need the
retained S05 server artifacts. Each run uses owned loopback ports 25660–25662 and 25669 (HTTP).
It deletes its copied server binaries, libraries and world afterwards, and exits nonzero on any
failed check.
