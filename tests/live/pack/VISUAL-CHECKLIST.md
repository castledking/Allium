# S06b manual visual checklist

The automated gate proves the pack loads cleanly for a protocol client. It cannot see pixels, so
these checks need your eyes in a real client. Run them on all three lanes, and record a result
for each line. A bot result is not a visual pass.

## Start a lane

```sh
./gradlew build
python3 tests/live/pack/run.py --lane spigot-1.20.1 --run-name manual-1201 --manual   # client 1.20.1, port 25660
python3 tests/live/pack/run.py --lane paper-1.21.8  --run-name manual-1218 --manual   # client 1.21.8, port 25661
python3 tests/live/pack/run.py --lane paper-26.3    --run-name manual-263  --manual   # client 26.3,   port 25662
```

Each run needs a fresh `--run-name` and uses the same disposable lanes as the gate. Join
`127.0.0.1:<port>` from this machine with a client of the lane's exact version (offline mode
accepts any account). In the server entry, set **Server Resource Packs: Prompt**, then accept the
prompt. Do not op yourself: ops bypass disarmer charges (`allium.mobdisarmer.bypass`), so an op's
tool never changes state. Type every command into the run's console instead:

```
pg give <you>      # five real Allium disarmers in hotbar slots 1-5, one per state
pg arena <you>     # four armed zombies with no AI in front of you
```

Type `stop` in the console to end the run.

## With SF's art instead

On SF, the pack is the local SF-art build (`pack/tools/sf_export.py` and `resource-pack.pack-file`;
see [pack delivery](../../../docs/pack-delivery.md)). With it, each state shows SF's existing
staff-like disarmer textures, the same ones SF's Nexo items use today:
- **full:** `mob_disarmer`;
- **two_thirds:** `_2-3`;
- **one_third:** `_1-3`;
- **empty:** `_0-3`;
- **broken:** also `_0-3`, since SF has no broken art.

The rest of the checklist applies unchanged: same-stack charge changes, held like a sword, plain
golden hoes vanilla, and no missing textures.

## What each item should look like

Every item is named **Mob Disarmer** in gold without italics. Each should show a gold handle
running from bottom-left to top-right, with a steel two-jawed clamp at the top. Three cyan pips
sit along the handle and light from the bottom up.

| Slot | State | Expected look | 1.20.1 | 1.21.8 | 26.3 |
| --- | --- | --- | --- | --- | --- |
| 1 | full | all three pips lit cyan | | | |
| 2 | two_thirds | lower two pips lit, top pip dark grey | | | |
| 3 | one_third | lowest pip lit, two dark | | | |
| 4 | empty (recharging) | all three pips dark | | | |
| 5 | broken | right jaw snapped off with a black break, all pips dark | | | |

Also check each of these:

| Check | Expected | 1.20.1 | 1.21.8 | 26.3 |
| --- | --- | --- | --- | --- |
| In hand, first and third person | held at an angle like a vanilla hoe or sword, not flat like a generic item | | | |
| Same-stack charge change | hold slot 1, right-click a zombie. The zombie's gear comes off and the item in your hand switches to two pips. Click two more zombies and it goes to one pip, then none. The slot never becomes a different item. | | | |
| Plain golden hoe | console `give <you> golden_hoe` looks exactly like vanilla | | | |
| Value past Allium's range | 1.20.1: `give <you> golden_hoe{CustomModelData:2577025}`; others: `give <you> golden_hoe[custom_model_data={floats:[2577025]}]`. It looks exactly like vanilla. | | | |
| `item_model` path (1.21.8 and 26.3 only) | `give <you> golden_hoe[item_model="allium:mob_disarmer",custom_model_data={floats:[2577022]}]` shows one lit pip | n/a | | |
| Inventory and crafting GUI | 16x16 icon, no purple-and-black "missing" texture anywhere | | | |

## Decline the pack (vanilla fallback)

Disconnect. In the server entry set **Server Resource Packs: Disabled** (or choose Prompt and
decline), then rejoin.

| Check | Expected | 1.20.1 | 1.21.8 | 26.3 |
| --- | --- | --- | --- | --- |
| The five items | plain vanilla golden hoes named **Mob Disarmer** in gold, never a missing-model cube | | | |
| Still works | right-click a zombie with slot 1: its gear comes off and you get the disarm message | | | |

Record PASS or FAIL in each cell, plus a note or screenshot for anything that looks off.
