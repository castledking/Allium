# Minimal native CE reset reproduction

Use CE 26.9.1 / Paper 1.21.8 / Java 21, or the tested SF CE 26.9.2 / Paper 26.3 /
Java 25. This recipe needs CE and an actual survival player; item signing is not
required. Use a disposable server with automatic pack workflows/delivery disabled.

Enable these entries in `plugins/CraftEngine/config.yml` before boot:

```yaml
item:
  update-triggers:
    click-in-inventory: true
    drop: true
    pick-up: true
```

Create `plugins/CraftEngine/resources/count_repro/pack.yml`:

```yaml
namespace: count_repro
author: CountRepro
version: 1.0
```

Create `plugins/CraftEngine/resources/count_repro/configuration/items.yml`:

```yaml
items:
  count_repro:ingot:
    material: iron_ingot
    updater:
      '1':
        - type: reset
          keep_components: [minecraft:custom_data]
          keep_tags: [PublicBukkitValues]
```

1. Boot CE, connect a survival player with empty inventory/cursor, and run
   `/ce item give <player> count_repro:ingot 7`. Keep that existing stack.
2. Replace the definition with:

   ```yaml
   items:
     count_repro:ingot:
       material: emerald
       updater:
         '2':
           - type: reset
             keep_components: [minecraft:custom_data]
             keep_tags: [PublicBukkitValues]
   ```

3. Run `/ce reload` from the console and wait for completion. Before interaction,
   the existing stack should still be seven IRON_INGOTs at version 1.
4. Left-click that stack in the player's inventory with an empty cursor. Read the
   resulting cursor after vanilla completes the click: **expected 7 EMERALDs,
   version 2; actual stock result 1 EMERALD, version 2**.

For each other trigger, restore the initial definition, reload, and give a fresh
version-1 stack before repeating the material/version change:

- **Drop:** select the seven-item stack before raising the version. After reload,
  use Ctrl+Q to drop the entire stack without an intervening inventory click.
  Inspect the live dropped entity before pickup: expected 7, stock 1.
- **Pickup:** while still at version 1, drop the seven-item stack out of pickup
  range. Raise the definition to version 2 and reload while it remains on the
  ground; then walk into it. Inspect the resulting player inventory: expected 7,
  stock 1. Do not infer the result from the consumed entity handle.

An ordinary single-item Q drop updates only the dropped one and hides this loss.
Real click/drop/pickup events must drive the update; do not call the updater or
fire synthetic events. Current-version seven-item controls and one-item resets
pass on stock.

`ResetOperation` uses `buildItem(context)`, which delegates to
`buildItem(context, 1)`. The [patch](reset-preserve-count.patch) instead passes
`item.count()`. The dynamic builder creates the stack with this count, puts it
into the context, then invokes processors. An optional regression check adds
`data: {item_name: "count=<arg:item.count>"}` to the version-2 definition: the
patched result must contain seven items **and** render `count=7`. Restoring the
amount after building would render `count=1` on this path.

## Retained validation

The recorded native fixture also retained exact signed metadata/PDC and a raw
custom-data sentinel, without refreshing or re-signing after its baseline.
Stock fails all seven-item resets on both runtimes; local full Paper JARs built
from the exact corresponding sources pass **20 cases / 28 observations each**.
Those checks include counts 1/7/64, dynamic names, successive resets of the same
stack, split/merge and partial drops.

- CE 26.9.1, source `2b9e6f59a0bf4dac7f71958c0ea96c14b8e44985`:
  [stock](../../evidence/ce-trigger-paper1218-ce2691-20261106.log),
  [patched](../../evidence/ce-trigger-patched-ce2691-20261106.log).
- SF CE 26.9.2, source `94b74598312d5a16216caed1c07fe3966d854096`:
  [stock](../../evidence/ce-trigger-paper263-ce2692-20261106.log),
  [patched](../../evidence/ce-trigger-patched-ce2692-20261106.log).
- [Result manifest](../../evidence/ce-trigger-patched-20261106.json),
  [stock bytecode/cause](../../evidence/ce-trigger-reset-source-20261106.log),
  [full-build provenance and hashes](../builds/README.md).

The Java patch is the sole tracked source change in each full build. The recorded
build recipe also used an external compiler-only Gson classpath ordering fix;
dependency versions and runtime packaging were unchanged. These are local patched
builds, not official releases. Quantity conservation does not add splitting when
a new definition lowers its stack limit or override processors that intentionally
replace the count.
