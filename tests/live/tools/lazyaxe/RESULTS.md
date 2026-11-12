# S10b: Lazy Axe live gate

**PASS on 2026-11-12: 251 live checks, 0 failures**, all with Allium.jar `28cca311…`.
- **Bot:** a real protocol bot mines a log for real. It starts breaking, waits, and finishes, so
  the server's own break progress decides. It sneaks, switches hotbar slots and disconnects with
  real packets too.
- **Fixture:** `ToolsGate`, part of the `DisarmerGate` test plugin.
  - It grows vanilla trees with a seeded random, each in its own cleared arena, and builds the
    player-style structures.
  - It records every `BlockBreakEvent` the bot caused, with the server tick. For each, it also
    asks Allium whether its re-entry guard was set, which marks a break that came from a fell.
  - It also records each `LazyAxeFellEvent`, the drops, the leaves and the axe.
  - Random ticks are off during the cases, so leaves cannot decay mid-case.

The details are in the [manifest](evidence/s10b-lazyaxe-manifest.json). Runs are kept in
`/mnt/storage/.allium-validation/20261112/s10b-lazyaxe/`. The operator guide is
[docs/tools.md](../../../../docs/tools.md#alliumfell_tree), and the design is in
[docs/lazy-axe-design.md](../../../../docs/lazy-axe-design.md).

| Run | Lane | Checks |
| --- | --- | --- |
| `final-spigot` | Spigot 1.20.1, minecraft-protocol 1.68.0 | 112 |
| `final-paper26` | Paper 26.3-141 + SF's GriefPrevention3D 19.0.5, the owned raw client | 127 |
| `final-quick-paper1218` | Paper 1.21.8-60 + Nexo, CraftEngine and Oraxen: a Nexo appearance | 12 |

## What Spigot 1.20.1 and Paper 26.3 showed

The log counts are those of the seeded trees, the same on both lanes.
- **Natural trees fell completely:**
  - oak (5 logs), birch (7), spruce (5), cherry (10) and a mega jungle (77);
  - on 26.3, a pale oak (33) as well.
  - **Every log** was gone, and **no leaf** was broken; vanilla decays them later.
  - **Drops:** one log item per log.
  - **Durability:** with Unbreaking 0, one point per log. With Unbreaking III, the 77-log jungle
    cost 21 (Spigot) and 20 (26.3).
- **The re-entry guard:** in every fell, each log the axe broke fired a real `BlockBreakEvent` with
  Allium's guard set. Exactly one `LazyAxeFellEvent` fired per tree. On Spigot, the bot's chat
  showed no "already felling" message either, so the events the fell caused never started a
  second fell.
- **The per-tick budget:** the shipped 6 blocks per tick was never exceeded. The 77-log jungle
  took 13 ticks, and the 68-log one 12.
- **Never felled:** in each case only the mined log broke, and no fell event fired.
  - **A bare log frame**, 11 player-placed logs with no leaves.
  - **Persistent leaves:** a log trunk under 73 player-placed (persistent) leaves.
  - **The structure guard:** a natural oak with one plank touching its trunk.
  - **A creaking heart:** on 26.3, a pale oak with a creaking heart in its trunk. The seeded
    `PALE_OAK_CREAKING` grew without one on Paper 26.3, so the fixture set one into the trunk, as
    the decorator places it.
- **The block cap:** two more mega jungles (68 and 91 logs) grew before a restart, and the caps
  were written at their exact counts.
  - **`max-blocks: 68`** felled the 68-log tree completely.
  - **`max-blocks: 90`** left the 91-log tree standing, apart from the mined log.
  - **The restart:** both trees and both definitions survived it.
- **`stop-before-break`:** both cases used a dark oak and an axe with 4 durability left.
  - **`true` (the default):** it stopped after 3 breaks, with 1 left, and the axe was kept.
  - **`false`:** the axe broke on the fourth log, and nothing broke after it.
- **Sneaking:** the server saw the bot sneaking (a real packet).
  - **`disables`:** sneaking breaks one log.
  - **`ignored`:** it fells while sneaking.
  - **`required`:** it fells while sneaking, and breaks one log while standing.
- **Permission:** with `allium.treeaxe.use` set false, one log broke.
- **Interruptions:** each used a mega jungle at 1 block per tick.
  - **Hotbar swap:** the bot switched slots 26 logs in. Nothing broke after the slot change, and
    78 logs were left.
  - **Quit:** the bot disconnected 27 logs in. Nothing broke after it quit, and 27 logs were left.
  - **Chunk unload:** after 5 logs, the fixture took the bot 3000 blocks away, and the tree's
    chunk unloaded the next tick. Nothing broke after the unload, and 62 logs were left.
    - **On Paper 26.3** this case ran after the restart, with the lane's
      `delay-chunk-unloads-by` set to 0s. At Paper's default 10s the chunk outlives any fell.
- **`mode: direct`:** an oak fell completely and dropped every log.
  - **Events:** only the mined log fired a `BlockBreakEvent`, and the one `LazyAxeFellEvent` said
    direct.
  - **Durability:** one point per log.
- **A pre-recode `tree_axe`:** a netherite axe with `allium:custom_item_id` and `nexo:id`
  `tree_axe`, Efficiency V, Unbreaking III, Fortune III, Mending, a name, repair cost 7 and
  damage 100.
  - **Adopted in place** as a signed `allium:lazy_axe`, keeping its enchantments, name, repair
    cost and damage.
  - **It felled the oak**, and Allium logged the import.
  - **Fortune III** still dropped one log per log.
- **The claim on 26.3:** GriefPrevention3D 19.0.5's public `createClaim` made an admin claim from
  x = 5961 east. The line runs through the middle of a dark oak, whose log columns are
  5959–5962. The bot has no trust in the claim.
  - **Inside the claim:** all 20 logs stayed.
  - **Outside it:** all 25 logs were felled.
- **Log:** there were no listener or Allium exceptions in either log.

## Paper 1.21.8, the provider appearance

- **The setup:** the shipped definition, enabled, plus `appearance.aliases: [nexo:gate_axe]`,
  and a Nexo item `gate_axe` on a netherite axe.
- **The result:**
  - An axe issued as `nexo:gate_axe` is a signed `allium:lazy_axe` with the Nexo appearance.
  - It felled a 6-log oak in one fell: one `BlockBreakEvent` per log, one drop per log.

## A fresh install on vanilla terrain (`--natural`, 2026-11-12)

**PASS: 28 live checks, 0 failures** (14 on each lane: `natural-final-spigot` on Spigot 1.20.1,
`natural-final-paper26` on Paper 26.3), on Allium.jar `23e61770713fa4adbac4632f1038f6a86deeb7330df3b9185ec4ebd67fd3bc03`,
with the `/give` warning below. These runs and their hashes are retained separately in the
manifest; they do not replace the original 251-check gate. This is how an
admin meets the Lazy Axe:
- **Setup:** no tool files are written beforehand, so Allium installs its shipped defaults. The
  world is vanilla terrain, and the tree is the nearest natural (worldgen) tree to spawn, a spruce
  on both lanes. The axe comes from the console's `/give GateBot allium:lazy_axe`.
- **Disabled by default (live, both lanes):**
  - **The shipped file:** the installed `tools/lazy_axe.yml` says `enabled: false`, and the
    startup log says the Lazy Axe is disabled and names the file.
  - **`/give`:** it gives a signed `allium:lazy_axe`, and now warns the giver that it is disabled
    and works as a plain item until `enabled: true` and a restart.
  - **Mining:** it breaks one log, as a plain axe.
- **Enabled (live, both lanes):** after `enabled: true` and a restart, mining the base of a
  natural spruce felled it, whole trunk included, and broke no leaf. That was 56 logs on one lane
  and 7 on the other.
- **The harness bug it found:** the raw 26.3 client encoded a block position with a negative x
  as an out-of-range signed value and disconnected. The flat arenas above all sit at positive x,
  which is why it never showed there.

## Standard-gate regression after the fresh-install harness change

**Verified live:** `reviewed-spigot` passed 112 checks on Spigot 1.20.1 and
`reviewed-paper26` passed 127 on Paper 26.3 with GriefPrevention3D 19.0.5, both
without failures or listener exceptions. They used Allium.jar
`ba8fa8d316b995ee888cd2bfea1a370af955e1e48b194c10542bcc8dcdf5f80c`.
The manifest retains these runs separately. This confirms the standard grown-tree
cases still execute after renaming the fresh-install helper. The vanilla-grown
birch case ran on both lanes; the spontaneous worldgen fresh-install cases above
used spruce only. No new Paper 1.21.8 appearance run is claimed here.

## What the gate found and fixed

- **The shipped `lazy_axe.yml` could not be enabled.** The core item `lazy_axe` was bound to the
  behavior type `allium:fell_tree` instead of its definition `allium:lazy_axe`. The loader then
  rejected the file as claiming an appearance another identity owned. `CoreItemsTest` now checks
  each core tool item's binding.
- **The 26.x raw client's "finish breaking" was really "abort".** 26.x inserted
  `CHANGE_DESTROY_DIRECTION` at ordinal 1, so STOP is now 3. The fix is in the harness only.

## Scope and limits

- **The re-entry guard** is also unit-tested (`FellTreeBehaviorTest`): there, `breakBlock` fires
  the event straight back into the behavior.
- **Unit tests only:** a cancelled `LazyAxeFellEvent`, a block replaced mid-fell, and claim
  denial per log.
- **Fortune and Silk Touch** only change drops for blocks that do not drop themselves. Logs drop
  themselves, so live they show only that the drops stay one per log.
- **Mangrove and nether stems** were not grown live.

## Rerun

```sh
./gradlew build
python3 tests/live/tools/lazyaxe/run.py --lane spigot-1.20.1 --run-name <name>
python3 tests/live/tools/lazyaxe/run.py --lane paper-26.3 --run-name <name> --griefprevention
python3 tests/live/tools/lazyaxe/run.py --lane paper-1.21.8 --run-name <name> --quick
python3 tests/live/tools/lazyaxe/run.py --lane <spigot-1.20.1|paper-26.3> --run-name <name> --natural
```

The runs use the owned ports 25650–25652 and the disarmer gate's clients. Different lanes can run
at the same time, but only one run per lane. The prerequisites are the artifacts named in
`tests/live/tools/lanes.py`, kept in `/mnt/storage/.allium-validation/infra/`.
