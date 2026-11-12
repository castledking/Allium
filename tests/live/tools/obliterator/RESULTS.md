# S10: Phantom Obliterator live gate

**PASS on 2026-11-11: 171 live checks, 0 failures**, with carrier suppression and privileged adoption rules.
- **Bot:** a real protocol bot right-clicks air with the vanilla use-item packet.
- **Fixture:** `DisarmerGate`, extended for tools, spawns marked targets 3 blocks above the arena
  at x = 4000. It records the bot's damage events, deaths and their killer, the protection check
  events, the chunk loads during the use, and the tool's signed state.

The details are in the [manifest](evidence/s10-obliterator-manifest.json). Runs are kept in
`/mnt/storage/.allium-validation/20261109/s10-obliterator/`. The operator guide is
[docs/tools.md](../../../../docs/tools.md#alliumobliterate_phantoms), and the design is in
[docs/phantom-obliterator-design.md](../../../../docs/phantom-obliterator-design.md).

| Run | Lane | Checks |
| --- | --- | --- |
| `spigot-test1` | Spigot 1.20.1, minecraft-protocol 1.68.0 | 76 |
| `paper26-test1` | Paper 26.3-141 + GriefPrevention3D 19.0.5, the owned raw client | 86 |
| `final-quick-paper1218` | Paper 1.21.8 + Nexo, CraftEngine and Oraxen: a Nexo appearance | 9 |

## What Spigot 1.20.1 and Paper 26.3 showed

- **Carrier suppression (live checked on both lanes):**
  - **Brewing stand:** hoppers feeding a brewing stand are blocked when transferring an identified
    tool (Phantom Obliterator on `phantom_membrane`). The tool stays in the hopper. When a plain
    vanilla phantom membrane is put in the hopper, it transfers into the brewing stand normally.
  - **Furnace:** hoppers feeding a furnace are blocked when transferring an identified tool (Mob
    Disarmer on `golden_hoe`). The tool stays in the hopper. A plain vanilla tool transfers normally.
  - **Crafting, Anvils & Smithing:** unit and live checks confirm identified tools are blocked from
    being used as crafting ingredients, crafter ingredients (1.21+), anvil repair material/sacrifice,
    smithing recipes, grindstone combining, and villager trades. Plain items are unaffected.
- **Privileged legacy adoption rules & creative forgery protection:**
  - A creative-forged legacy tag (`allium:custom_item_id = phantom_obliterator`) is **rejected**
    at runtime by default: it is not adopted, no behavior is assigned, no HMAC signature is minted,
    and target phantoms are untouched.
  - When an explicit time-limited adoption window is opened by an operator (`dg adopt-window 60000`),
    legacy tools are adopted in place with their charges, depleted timestamps, uses, and enchantments
    preserved, and Allium explicitly logs `ADOPTED PRIVILEGED ITEM`.
  - Closing the adoption window returns to rejecting unmigrated privileged legacy items.
- **Instance-bound refunds:**
  - If a player swaps or drops their tool during an obliterate execution where all targets are spared,
    Allium detects that the instance UUID has moved or is gone from the main hand, logs the failed
    refund diagnostic, and credits nothing else.
- **Security Scope:**
  - Server-secret HMAC signing prevents unauthorized **minting** and **editing** of PDC tags and item
    state; creative-mode stack duplication is an inventory/creative permission concern.
- **A use with six targets** (`authorized`): unnamed phantoms at 6 and 20 blocks, a named one at
  8, a guarded one at 10, a claimed one at 12 and a zombie at 5.
  - **Identity:** the fresh tool was a signed `allium:phantom_obliterator` on the phantom membrane
    carrier.
  - **Kills:** both unnamed phantoms died, killed as the bot (`getKiller()` is GateBot, so loot and
    experience drop as from a player kill).
  - **Spared:**
    - The named phantom was never damaged.
    - The guarded one was damaged once and spared by a damage listener.
    - The claimed one was never damaged: the protection check event denied it before anything was
      spent.
    - The zombie is not a target.
  - **Protection** was asked once per scanned target: 4, for the two unnamed phantoms, the guarded
    one and the claimed one.
  - **No chunk loaded** during the use.
  - **The tool:** exactly one charge was spent (3 → 2, uses 1), with the same UUID, re-signed.
- **Nothing to spend on:**
  - **Empty scan:** only a named phantom and a zombie. Nothing was spent, nothing was asked and
    nothing was damaged; the tool was field-for-field unchanged.
  - **All spared:** a lone guarded phantom was damaged and spared, and the charge was refunded
    (still 3 charges, 0 uses).
- **A rejected write:** one armed `item.state-write` fault. The write was rejected, so nothing
  died and nothing was spent (spend first), and Allium logged it.
- **Signed identity required:**
  - A phantom membrane with the built-in's model data 2577030 and its name did nothing, and
    protection was never asked.
  - A genuine tool whose `state_charges` was edited without re-signing was rejected ("Rejected
    item claiming behavior 'allium:phantom_obliterator'"). It did nothing.
- **Permission:** with `allium.phantomobliterator.use` set false nothing happened, and the tool was
  unchanged.
- **Legacy import:**
  - **Recharging:** a pre-recode netherite sword with Unbreaking III, both IDs on
    `phantom_obliterator_0-3`, 0 charges, a recharge 30 s away and 7 uses.
    - It was adopted in place: still a netherite sword with Unbreaking III.
    - It kept 0 charges, its depleted timestamp (20–30 s left) and 7 uses.
    - Still recharging, it killed nothing, and Allium logged the import.
  - **Charged:** a charged legacy tool imported and worked: 3 → 2 charges, uses 4 → 5.
  - **One ID only:** a legacy sword with only `allium:custom_item_id` (no matching `nexo:id`) was
    never imported and did nothing.
- **A second definition's radius:** `allium:gate_obliterator` (an echo shard, radius 16). The
  phantom at 10 blocks died, and the one at 20 was never touched. It spent its own charge.
- **Charges and recharge:**
  - Three uses emptied a fresh tool (2, 1, 0). The last started the 120 s recharge, measured at
    110–120 s left.
  - A fourth use killed nothing, and the state was unchanged.
- **Persistence:** a spent keeper (2 charges) was field-for-field the same after a restart, and
  worked on (2 → 1).
- **The claim on 26.3:**
  - **The claim:** GriefPrevention3D 19.0.5's public `createClaim` made an admin claim over the
    arena.
  - **Inside it:** two phantoms in the claim, which the bot has no trust in, were protected by
    Allium's GriefPrevention hook. The check event never fired, and nothing was damaged or spent.
- **Log:** there were no listener or Allium exceptions in either log.

## Paper 1.21.8, the provider appearance

- **The setup:** the shipped definition plus `appearance.aliases: [nexo:gate_obliterator]`.
- **The result:**
  - A tool issued as `nexo:gate_obliterator` is a signed `allium:phantom_obliterator`, with Nexo's
    native ID `gate_obliterator`.
  - It obliterated, spending exactly one charge, with the same UUID, re-signed.

## The carrier

The built-in Phantom Obliterator moved from `blaze_rod` to `phantom_membrane`: SF's Nexo pack
overrides `items/blaze_rod.json`, and no readable SF pack overrides the phantom membrane. The
S06c rpm-export dry run against a copy of SF's mixer was rerun on this jar and now reports no
carrier collision at all (8 checks, 0 failures). SF's CraftEngine pack could not be read; the staging RPM
merge confirms it. The art does not ship yet.

## Scope and limits

- **Unloaded chunks:** "loaded chunks only" is shown by zero chunk loads during every use.
  Putting a target inside an unloaded chunk was tried and abandoned: Spigot 1.20.1 would not
  unload a chunk holding a just-spawned entity.
- **The off hand:** only main-hand right-clicks were sent. The off hand and the stale-stack
  recheck are unit-tested (`ObliterateBehaviorTest`), as are the target cap and the nearest-first
  order.
- **Break chances** are unit-tested; the shipped default never breaks.

## Rerun

```sh
./gradlew build
python3 tests/live/tools/obliterator/run.py --lane spigot-1.20.1 --run-name <name>
python3 tests/live/tools/obliterator/run.py --lane paper-26.3 --run-name <name> --griefprevention /path/to/GriefPrevention3D.jar
python3 tests/live/tools/obliterator/run.py --lane paper-1.21.8 --run-name <name> --quick
```

The runs use the disarmer gate's owned ports 25650–25652 and its clients, and run one at a time.
