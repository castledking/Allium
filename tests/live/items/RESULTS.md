# S04 follow-up: item library and M1 `/give` harness gate

**PASS on 2026-11-07: 93 live checks, 0 failures** on Spigot 1.20.1, Paper 1.21.8 (Nexo,
CraftEngine and Oraxen) and Paper 26.3, plus **36 compatibility checks** (below), all with
Allium.jar SHA-256 `270f8ee8…`. Unit tests: 175 (41 core, 134 plugin), 0 failures. Hashes, versions, every command and Allium's reply are in
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

## Follow-up: vanilla `/give` compatibility, player senders, skull fallbacks

`run.py --scenario compat` uses two bots. GateBot is not an operator. Spigot 1.20.1 passed 17
checks and Paper 26.3 passed 19.

- **How `/give` is routed:**
  - On Paper 1.20.6+, Allium registers `/give` through Paper's Brigadier API, with vanilla's own
    `players()` argument. Allium's legacy labels are yielded first.
  - The reason: Paper runs legacy Bukkit commands under `/execute` with the initiating sender (a
    probe saw the console) and no `as`/`at` context. The legacy route gave `@s` to nobody and
    measured `@p` from the console.
  - Spigot keeps the Bukkit command. There, plugin commands are not reachable under `/execute`
    at all, so `execute … run give` is vanilla's.
- **Command blocks:** `give @p diamond 1` gave the bot 3 blocks away, not the one 15 away, and
  then the reverse when they swapped places. Allium handled it on both lanes.
- **`/execute`:**
  - `execute as GateBot2 at GateBot2 run give @s diamond 1` gave GateBot2.
  - `execute as GateBot at GateBot2 run give @p diamond 1` gave GateBot2, so `@p` was measured
    from the execution position.
  - Allium handled both on 26.3; vanilla handled both on 1.20.1.
- **`minecraft:give`** reached vanilla: its own reply, and no `ItemGiveEvent`.
- **A datapack function's give** (`give @a[name=GateBot2] minecraft:diamond 1`) stayed vanilla:
  the diamond arrived with no `ItemGiveEvent` and no Allium reply.
- **Player senders:**
  - Without `allium.give`, `/give` does nothing.
  - With `allium.give` alone, `allium:handcuffs` is denied. Adding `allium.give.privileged`
    mints signed handcuffs.
  - `@s` needs vanilla's `minecraft.command.selector`. Paper's `players()` argument enforces it,
    and Allium's Bukkit route now does too.
  - `-e` put the iron helmet into the empty head slot and left the worn leather boots in place;
    the iron boots went to the inventory.
- **Empty targets:** on Paper, `give NoSuchPlayer stone` and `give @a[name=Nobody] stone` now
  report vanilla's "No player was found". Paper's resolver returns an empty list there, and the
  first build silently gave nothing.
- **Skull fallbacks:**
  - A lookup that fails, times out or is rate-limited is reported as `DEGRADED` ("given without
    its skin"). A profile that comes back without a skin is reported the same way.
  - With `skull-timeout-fallback: refuse` (1.20.1 lane), a hanging lookup gave nothing:
    `REFUSED_UNRESOLVED`.
- **One real Mojang lookup (Paper 26.3):**
  - On an offline-mode server, Paper's `PlayerProfile#update` only builds an offline profile and
    never contacts Mojang. So the lane ran in BungeeCord mode (proxy online mode, as behind an
    online-mode proxy), with forwarded bot logins.
  - **First `owner:Notch`:** one remote lookup, 2.0 s, and a head with its skin.
  - **Second `owner:Notch`:** served from the cache in 0.1 s, with no lookup and no "resolving"
    notice.
  - **`owner:jeb_`** within the same minute: stopped by the lane's 1-per-minute limit, no
    network call, and reported `DEGRADED`.
  - Counters at the end: 1 remote lookup, 1 resolved, 1 cache hit, 1 rate-limited.

## M1 close-out rerun (2026-11-07): scheduler boundary and carrier non-match

The gate was run again on Allium.jar `57ff304f…`, the jar that moved scheduling onto
`TaskScheduler`: item delivery's server-thread hop and the bounded skull-lookup pool. It
passed 105 checks with 0 failures: 31 on Spigot 1.20.1, 43 on Paper 1.21.8 with all three
providers, and 31 on Paper 26.3.

Each lane adds one case for the M1 gate "no vanilla-carrier false matches":
- A plain `golden_hoe` was given.
- A second golden hoe was dressed as a disarmer: the gold name "Mob Disarmer" and the
  disarmer's exact model data 2577020. Paper 1.21.8 and 26.3 set them with
  `[custom_name=…,custom_model_data={floats:[2577020f]}]`, Spigot 1.20.1 with
  `{display:{Name:…},CustomModelData:2577020}` NBT.
- Both arrived as given, and `identify` found neither to be `allium:mob_disarmer`: no
  behavior, signature or instance UUID.

## Scope and limits

- **Skull lookups:** only one real Mojang lookup is run, in the compat scenario; the main gate's
  lanes are offline-mode, so fault points stand in for slow and hanging lookups. Deduplication
  and the bounded queue are unit-tested.
- **Not built yet:** `/i`, item aliases, kits, `queue` overflow and a stable addon API.
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
