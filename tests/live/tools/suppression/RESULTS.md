# S10: core tool carrier suppression live gate

**PASS on 2026-11-12: 63 live checks, 0 failures**, all with Allium.jar `28cca311…`.
- **Stations:** real station blocks do the work, each next to a twin holding the plain item.
- **Menus:** the anvil, grindstone and smithing table are real block menus, which the bot opens
  with a real right-click. The crafting table and enchanting table are real menus the server opens
  for the bot.
- **Inputs:** the fixture puts the items into a menu's input slots, as a hotbar swap would, and the
  server computes the result.
- **Clicks on Spigot 1.20.1:** the bot takes results with real window clicks there (`--full`).

The details are in the [manifest](evidence/s10-suppression-manifest.json). Runs are kept in
`/mnt/storage/.allium-validation/20261112/s10-suppression/`. The operator guide is
[docs/tools.md](../../../../docs/tools.md#carriers-are-not-materials).

| Run | Lane | Checks |
| --- | --- | --- |
| `final-spigot` | Spigot 1.20.1, minecraft-protocol 1.68.0, with clicks | 31 |
| `final-paper26` | Paper 26.3-141, the owned raw client | 32 |

The tools used were the Phantom Obliterator (phantom membrane), the Mob Disarmer (golden hoe) and
the Lazy Axe (netherite axe).

## Both lanes

- **Hoppers:** a hopper fed each station: a brewing stand, a furnace, a blast furnace and a smoker,
  plus a crafter on 26.3.
  - **Tools:** each tool stayed in its hopper. Every transfer attempt (8 per station in 3 s)
    reached `InventoryMoveItemEvent` cancelled.
  - **Plain items** moved in.
- **Already inside a fueled station:**
  - **Blast furnace:** a Mob Disarmer was never smelted, while a plain golden hoe smelted into a
    gold nugget.
  - **Brewing stand:** a Phantom Obliterator over three awkward potions brewed nothing, while a
    plain membrane brewed slow falling.
- **Crafting table:** a Mob Disarmer with a golden hoe gave no result, while two plain golden hoes
  still repair-combined.
- **Powered crafter (26.3):** a crafter holding a Mob Disarmer and a golden hoe, powered by
  redstone, crafted nothing, and both stayed in its grid. Its plain twin crafted.
- **Anvil, as material:** the Phantom Obliterator in the second slot gave no result for a damaged
  elytra. A plain membrane still repaired it.
- **Anvil, in its own slot:** with the Lazy Axe in the first slot,
  - a book of Sharpness III gave the same signed tool (same UUID) with Sharpness III;
  - a netherite ingot repaired it (damage 500 → 0), still the same signed tool.
- **Smithing table:** vanilla has no smithing recipe for these carriers (the netherite upgrade
  takes diamond gear). The fixture registers one, golden hoe to netherite hoe, as a server's
  custom recipe would.
  - **The Mob Disarmer** gave no result.
  - **A plain golden hoe** upgraded.
- **Enchanting table:** the Lazy Axe got its three offers, and the event was not cancelled.

## Spigot 1.20.1 only: real clicks

- **Anvil:** the bot shift-clicked the repaired Lazy Axe out of the anvil. It paid 2 levels, and
  the tool in its inventory was the same signed tool, repaired.
- **Enchanting table:** the bot enchanted the Lazy Axe (Efficiency I and Unbreaking I). It is still
  the same signed tool.
- **Grindstone:** taking the result for an enchanted Lazy Axe was refused. The tool stayed in the
  grindstone, enchanted, and never reached the bot's inventory.

## Scope and limits

- **Clicks on 26.3:** the raw 26.3 client opens block menus but does not click. The click paths
  run there only through the same listener code as on Spigot.
- **Villager trades** are unit-tested only (`ToolCarrierSuppressionListenerTest`).
- **What counts as a tool:** a stack carrying an Allium behavior, or a pre-recode tool ID. Plain
  items and other plugins' custom items are never blocked (unit-tested).
- **Found and fixed before this run:**
  - The listener first blocked every custom item, Nexo items included.
  - It blocked a tool in the anvil's first slot.
  - Its crafter hook never fired: it asked the event for an inventory the event does not have.

## Rerun

```sh
./gradlew build
python3 tests/live/tools/suppression/run.py --lane spigot-1.20.1 --run-name <name> --full
python3 tests/live/tools/suppression/run.py --lane paper-26.3 --run-name <name>
```
