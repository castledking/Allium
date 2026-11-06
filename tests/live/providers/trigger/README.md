# CE native trigger diagnostic (S05 only)

**Patched gate PASS, stock FAIL.** Two exact-source full CE builds with the one-line
count fix pass the expanded native suite below. Stock results are retained as the
negative reproduction; no production provider was replaced.

## Stock reproduction

**Stock quantity gate FAIL:** genuine clicks, full-stack drops, and pickups reset a stack
of seven to **one**. This repeats twice per trigger on both native versions below.
Each count-1 reset passes. Current-version count-7 controls keep seven. All cases
retain the exact Allium signature, instance UUID, spent charges (`9 → 2` before the
baseline), other state, every observed PDC key/type/value, and a raw custom-data
sentinel outside PDC. No Allium refresh or signing occurs after the baseline.

Completed 2026-11-06, using audited Allium.jar SHA-256
`4e90e660141964643ac32ee8580b74082ffec2460e3ca0a85428db4e88c1c14e`:

- **Exact pin:** official CE **26.9.1**, Paper **1.21.8-60-29c8822**, Java
  **21.0.13.0.3**, protocol **772**, loopback **25611**. CE SHA-256
  `021260c87e3546730d321f4360b1744e6e33caa1d68e98ae30dcdbbdc347ae0f`.
  [Evidence](../evidence/ce-trigger-paper1218-ce2691-20261106.log).
- **Adjacent patch:** SF CE **26.9.2**, Paper **26.3-141-54024d3**, Java
  **25.0.5.0.3**, protocol **777**, loopback **25610**. CE SHA-256
  `19535f1987e8a27ebe8c6d811e7deae9a1f05dbd3ef3359435aff5a3d819e0f9`.
  [Evidence](../evidence/ce-trigger-paper263-ce2692-20261106.log).

Both runs contain 12 cases (three triggers × current-version control, count-1
reset, count-7 reset, independent count-7 repeat). Only the six quantity checks
fail in each run. All 24 cases generated real native events; no event was manually
fired. Both clients and servers exited **0**. The diagnostic runner exits **1**
because the quantity contract fails. Full logs/JSON remain in
`/tmp/allium-s05-20261106/ce-triggers/{exact-1218,final-263-r2}`. Neither run has
quota/save/logging exceptions. These stock artifacts remain unsafe for reset
updaters; passing patched builds do not change their result.

## Minimal reproduction and cause

1. Before boot, enable `item.update-triggers.click-in-inventory`, `drop`, and
   `pick-up` in the disposable CE config. Disable workflows, packs/hosting and
   join delivery before boot. The fixture does this; it never invokes pack workflows.
2. Load an IRON_INGOT definition with reset updater version `1`. Give an actual
   survival player an existing signed/spent stack of seven. Snapshot it.
3. Change the carrier to EMERALD and raise updater version to `2`:

   ```yaml
   updater:
     '2':
       - type: reset
         keep_components: [minecraft:custom_data]
         keep_tags: [PublicBukkitValues]
   ```

4. Run native `ce reload`. Verify that the existing stack is still identical and
   no interaction event has occurred. Send one actual left-click in player
   inventory, full-stack drop, or move the connected player into the seeded item
   entity. Pickup entities are seeded three blocks away before the version raise.
5. Reread the resulting **cursor** for the click, **live dropped entity** for the
   full-stack drop, and **player inventory** after pickup. The replacement is
   EMERALD, CE version `2`, amount **1**, with unchanged metadata/signature.

The observer records `LOWEST` and `MONITOR` snapshots, then reads actual server
storage after vanilla event continuation. An ordinary single-item Q drop would
hide the bug (six remain and one drops); these trials deliberately drop the whole
stack. A consumed pickup entity handle can misleadingly regain its old count;
the pickup verdict uses the resulting player inventory and checks the live entity
list instead. All other trigger counts must remain zero in each observed case.

`ResetOperation.update` calls `buildItem(context)`. `BuildableItem` implements that
overload as `buildItem(context, 1)`. Reset copies selected components/tags and
replaces the old item; `ItemUpdateConfig` stamps the new version without restoring
count. This is present in both tested native jars, not merely inferred from a
newer checkout. See [source/bytecode evidence](../evidence/ce-trigger-reset-source-20261106.log).
The local source checkout is `b3079c5a` (26.9.2); the SF jar records `94b74598` and
the exact release records `2b9e6f59`, so those revisions are distinguished.

There is **no reset retention option for amount** in the inspected source or
native factory. It accepts only `keep_components` and `keep_tags`. Stack count is
a separate field, not a component or NBT custom-data entry; retaining
`minecraft:custom_data`, `PublicBukkitValues`, or `minecraft:max_stack_size` cannot
retain it. Metadata retention remains necessary and works in these tests.

The minimal proposed upstream fix is:

```java
Item newItem = this.item.get().buildItem(context, item.count());
```

[reset-preserve-count.patch](reset-preserve-count.patch) contains only that change.
It is applied in isolated clones at the exact native source revisions, then built
through the genuine `:bukkit:paper-loader:shadowJar` task. The original CE checkout
and installed production plugins remain unchanged. No Allium runtime code or
inventory repair listener was added. [Build recipe and provenance](builds/README.md)
include the external compiler-only Gson ordering adjustment, native descriptors,
full artifact hashes and the source diff.

For migrations that only change material, source `transmute` explicitly passes
`item.count()`; explicit `apply_data` operations can update selected components.
Those have narrower semantics than reset and are **not live-validated alternatives
here**. Recommend an upstream reset count fix and a rerun, rather than silently
repairing quantity or re-signing in Allium. No definition-only retention setting
closes the reset quantity gap.

## Patched native results

Both runtimes first pass the original 12-case diagnostic, then pass the expanded
suite with **20 case identities and 28 observations per runtime**, using Allium
SHA-256 `de103116a3724cb520d2351c5d9bc25aea5fd7fa91b2c997c74922ecbac580e7`.

| Full patched runtime | Source commit | SHA-256 |
| --- | --- | --- |
| CE 26.9.1, Paper 1.21.8 / Java 21 | `2b9e6f59a0bf4dac7f71958c0ea96c14b8e44985` | `9b86b1044e0d97d800e1722b1ca2b01f2582d44269a28508da7b9b79babfcd41` |
| SF CE 26.9.2, Paper 26.3 / Java 25 | `94b74598312d5a16216caed1c07fe3966d854096` | `d779823f05922747a70a268888610f408dcfa151b194ef46d28d483393d74852` |

The expanded cases add:

- Count 64 reset on each native trigger, alongside the original counts 1 and 7.
- Dynamic `item_name: "count=<arg:item.count>"` on each trigger. An existing count-7
  stack traverses reset versions 2 and 3 in one trigger, then version 4 in another,
  then a no-op at current version. Every dynamic name is `count=7`; this verifies
  that processors receive the preserved count during construction.
- Real right-click split into inventory 3 and cursor 4, then a left-click merge
  back into inventory 7, retaining the original identity and metadata on both.
- Real single-item drop: held count 6 remains IRON_INGOT/version 1, dropped count
  1 becomes EMERALD/version 2. Dropping the remaining six migrates all of them;
  the final live item entities conserve a total of 7, whether merged or separate.

All cases require exact MAC/UUID, spent charges, every recorded PDC type/value,
raw sentinel and native identity. Single-stack click/drop/pickup cases assert the
actual post-continuation destination: cursor/entity/inventory respectively. No
native update is called by the fixture. For repeated triggers, the already tested
stack is relocated, its full snapshot is compared before/after relocation, and
fresh observation files prevent an earlier result from satisfying a later wait.
The migration history stays on that same stack; it is not freshly minted.

[Exact 26.9.1 evidence](../evidence/ce-trigger-patched-ce2691-20261106.log) and
[SF 26.9.2 evidence](../evidence/ce-trigger-patched-ce2692-20261106.log) retain the
assertions, wire packets and every server-owned stage snapshot. The
[manifest](../evidence/ce-trigger-patched-20261106.json) identifies source builds,
lanes, counts, exits and hashes. These are local patched binaries, not upstream
official releases. Stack conservation does not implement splitting when a new
definition lowers the maximum stack size, nor override an arbitrary processor
that deliberately replaces an item/count. Other lanes/combinations require their
own release checks.

## Run it

From the repository root, use a fresh run name on every invocation:

```sh
python3 tests/live/providers/trigger/run.py --run-name reproduce-263
python3 tests/live/providers/trigger/run.py --exact1218 --port 25611 --run-name reproduce-1218
```

External prerequisites (no root Gradle build is used):

- Stopped disposable runtime template
  `/tmp/allium-s05-20261105/provider-gate/lane`, containing Paper 26.3's libraries,
  version jar and genuine dependency libraries. `--template` overrides the path.
  Allium defaults to this repository's `build/libs/Allium.jar`; `--allium-jar`
  selects an explicit artifact. Its actual hash is recorded for every run.
- Adjacent full CE jar at
  `/tmp/allium-s05-20261105/provider-gate/artifacts/craft-engine-sf-26.9.2.jar`;
  `--artifacts` overrides its directory.
- For `--exact1218`: the official full CE jar
  `/tmp/allium-s05-20261106/pins/ce/craft-engine-paper-plugin-26.9.1.jar` and
  `/tmp/allium-s05-20261106/lanes/artifacts/paper-1.21.8-60.jar`.
  Provenance is retained in `/tmp/allium-s05-20261106/pins/ce/README.md`.
- Java 21 and 25 at `/usr/lib/jvm/java-{21,25}-openjdk`; Python 3 and Node.js.
  Build-only Spigot API 1.20.1 at
  `/tmp/allium-lane-spigot-1.20/bundler/libraries/spigot-api-1.20.1-R0.1-SNAPSHOT.jar`
  (override `SPIGOT_API`). Gson/Guava build jars come from the disposable template.
- Exact 1.21.8 client uses external `minecraft-protocol` **1.68.0** installed at
  `/tmp/allium-s05-20261105/client/node_modules/minecraft-protocol`
  (override `MC_PROTOCOL_MODULE`). Its transport handles protocol772. The owned
  raw protocol777 client has no npm dependencies and reads packet IDs from the
  exact running server's protocol catalog.
- Network access for native dynamic dependency downloads and first Paper bootstrap.
  Fresh data directories are generated under the owned `/tmp/.../ce-triggers`
  root by default. `--run-root` also accepts the owned storage path shown below;
  allow roughly 0.3 GB per run and additional transient runtime headroom.

Only owned loopback ports **25610–25612** are accepted by both the fixture and
clients. The runner refuses existing run directories; it never copies worlds,
player data, databases, keys, Nexo, RPM, or other plugins. Only Allium, CE, and the
validation fixture are installed. It disables the native resource-pack host
registry, workflows and send-on-join before first boot. Normal application
commands, adapters and root build files are not changed by this diagnostic.

The clients send real left/right clicks with no predicted changed slots and an
empty cursor prediction; vanilla executes them and returns an authoritative
resync. Drop action ordinals come from the exact running server's native enum
catalog, including the different protocol772/777 values. Protocol777 movement
also sends `client_tick_end` and the initial `player_loaded` packet. Packet
formats must be revisited for another protocol.

The runner always shuts down its own client/server in `finally`, with bounded
terminate/kill/reap fallbacks. Renders from a completed run are reproducible:

```sh
python3 tests/live/providers/trigger/evidence.py reproduce-263
python3 tests/live/providers/trigger/evidence.py reproduce-1218
```

The patched gate can be repeated with the full built native artifact:

```sh
python3 tests/live/providers/trigger/run.py --extended --exact1218 --port 25611 \
  --run-name verify-patched-2691 \
  --run-root /mnt/storage/.allium-validation/20261106/ce-count-fix/runs \
  --ce-jar /path/to/craft-engine-paper-plugin-26.9.1.jar \
  --provenance /path/to/provenance.json
python3 tests/live/providers/trigger/evidence.py verify-patched-2691 \
  --run-root /mnt/storage/.allium-validation/20261106/ce-count-fix/runs
```

For SF 26.9.2 / Paper 26.3, omit `--exact1218`, use port 25610 and its patched
artifact/provenance. Never reuse a run directory. The final reviewed patched runs
are `patched-2691-reviewed` and `patched-2692-reviewed` under that storage root.
Both stock failures and patched passes are committed as separate evidence.
Earlier harness attempts (reload completion text and missing protocol777 client
 tick marker) are not passing tests and remain temporary diagnostics. A review
also hardened failure-path process cleanup. Nonzero exits and any forced shutdown,
including a forced process that exits zero, now fail the runner. Five deterministic
owned-subprocess checks cover clean shutdown, existing/new nonzero exits, forced
zero exit and ignored SIGTERM with kill/reap fallback:

```sh
python3 -m unittest discover -s tests/live/providers/trigger -p 'test_*.py' -v
```

The recorded native failures already have clean client/server exit zero. The
cleanup checks do not turn stock quantity failures into passing tests.
