# S04 follow-up: item library and M1 `/give` harness gate

**PASS on 2026-11-07: 93 live checks, 0 failures** on Spigot 1.20.1, Paper 1.21.8 (Nexo,
CraftEngine and Oraxen) and Paper 26.3, all with Allium.jar SHA-256 `717912a9…`. Unit tests:
172 (41 core, 131 plugin), 0 failures. Hashes, versions, every command and Allium's reply are in
the [manifest](evidence/s04g-manifest.json).

Run logs, per-case snapshots and summaries are kept on persistent storage in
`/mnt/storage/.allium-validation/20261107/items-gate/`. A machine reboot cleared `/tmp` before
the final runs, so the lanes were rebuilt (see the manifest's `environmentNote`) and the gate
was run again from scratch.

| Lane | Client | Providers | Checks |
| --- | --- | --- | --- |
| Spigot 1.20.1 (3871-d2eba2c), Java 17 | minecraft-protocol 1.68.0, protocol 763 | none | 27 |
| Paper 1.21.8-60, Java 21 | minecraft-protocol 1.68.0, protocol 772 | Nexo 1.30-dev, CE 26.9.1, Oraxen 1.220.1 | 39 |
| Paper 26.3-141, Java 25 | owned raw client, protocol 777 | none | 27 |

## How it runs

Every give is a console `/give` aimed at the bot `GateBot`, which stays online as the target.
The `GiveGate` fixture is test-only. It fills or clears the bot's inventory, cancels the next
`ItemGiveEvent` on request, and arms the two skull fault points. It then records what the server
holds and what lies on the ground. On every lane the bare `/give` label reached Allium, not
vanilla.

## What each lane showed

- **Disarmers, correctly signed:**
  - `give GateBot allium:mob_disarmer` gave a golden hoe with model data 2577020. It verifies as
    `allium:mob_disarmer` with an instance UUID and a `v2:` MAC.
  - `allium:mob_disarmer 3` gave three single stacks with distinct instance UUIDs.
  - On 1.21.8, `nexo:mob_disarmer`, `craftengine:allium_gate:mob_disarmer` and
    `oraxen:Gate_Disarmer` (bound to the behavior in config) each arrived with their provider
    appearance, verified and signed.
- **Overflow (35 slots full, one free, 100 diamonds):**
  - `--overflow=refuse` gave and dropped nothing, and said so.
  - `partial` gave 64 and reported 36 as not given, with nothing on the ground.
  - `drop` gave 64 and dropped 36 at the bot's feet.
  - A cancelled `ItemGiveEvent` gave nothing.
- **Raw data:**
  - **1.20.1:** `[...]` components are rejected ("require Minecraft 1.20.5+"). `{...}` NBT with
    `Name:'{"text":"Blade, of Testing; Mk II"}'` and `Unbreakable:1b` produced that exact name
    on an unbreakable sword.
  - **1.21.8 and 26.3:** `[custom_name={text:"Blade, of Testing; Mk II",italic:false},unbreakable={}]`
    produced the same, and `{...}` NBT is rejected ("uses [...] data components").
  - **Every lane:** raw data on `allium:mob_disarmer` is refused (vanilla items only).
- **Tokens:**
  - `sharpness:5 name:&6Gate_Blade lore:One|"Two words" unbreakable` gave Sharpness V, a gold
    "Gate Blade", lore `One` / `Two words`, and unbreakable.
  - `potion:swiftness` set the base potion (`SPEED` on 1.20.1, `SWIFTNESS` later).
- **Resolution outcomes:** `nexo:does_not_exist` reported `UNKNOWN_ID` on 1.21.8, and
  `oraxen:gate_disarmer` reported `PROVIDER_ABSENT` where Oraxen is not installed.
- **Skulls:**
  - `owner:GateBot` (online) resolved at once.
  - `give NoSuchPlayer stone` was refused before anything was built.
  - With a 2 s slow lookup armed, the bot disconnected after "Resolving skull profiles for
    GateBot…". The give then reported "GateBot left before the items were ready, so nothing was
    given", and after rejoining there was no head in the inventory or on the ground.
  - With a lookup that never answers, the give finished after the 3 s timeout with a head and a
    warning that the skin could not be resolved.

## Scope and limits

- **Privileged items:** `allium.give.privileged` is checked only in unit tests, because the gate
  gives from the console, which holds every permission.
- **Skull lookups:** real Mojang lookups were not exercised (offline-mode lanes, no network in
  the gate); the fault points stand in for slow and hanging lookups. Profile caching,
  deduplication and the bounded queue are unit-tested.
- **Not built yet:** `/i`, item aliases, kits, `queue` overflow and a stable addon API.
- **Equip:** `-e` is unit-tested, including that it never overwrites, but not exercised live.
- **Grammar:** quote, comma and semicolon handling inside brackets is unit-tested; the live
  runs show it end to end for one name in each era.

## Rerun

```sh
./gradlew build
python3 tests/live/items/run.py --lane spigot-1.20.1 --run-name <name>
python3 tests/live/items/run.py --lane paper-1.21.8 --run-name <name>
python3 tests/live/items/run.py --lane paper-26.3 --run-name <name>
```

The runs use owned loopback ports 25670–25672, start servers with
`-Dallium.fault-injection=true`, delete their copied server files afterwards and exit nonzero
on any failed check. They need the lane artifacts named in `run.py`; after a reboot, restore
them as the manifest describes.
