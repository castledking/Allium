# S05 provider implementation and validation

Updated 2026-11-05. **Implementation is present; the complete live gate is still
open.** Nexo and CraftEngine pass the checks below. A runnable pinned Oraxen jar
and the remaining supported server lanes are still needed. S06 gameplay behavior
has not been started.

## Build and loading boundary

| Source set | Exact compile-only API pin | Compiler/output |
| --- | --- | --- |
| `nexoAdapter` | `com.nexomc:nexo:1.29.1` | Java 21 / `--release 17` |
| `craftengineAdapter` | `net.momirealms:craft-engine-core:26.9.1`, `craft-engine-bukkit:26.9.1` | Java 21 / `--release 17` |
| `oraxenAdapter` | `io.th0rgal:oraxen:1.211.0` | Java 21 / `--release 17` |

Shared/plugin code and `allium-core` remain Java 17; `allium-core` has no Bukkit,
Spigot or Paper dependency. Provider tests run on Java 21 against the real pinned
API jars, with injected native-access fixtures for deterministic lifecycle cases.
Those fixtures are not evidence of a complete native plugin boot.

The shaded jar contains the adapter classes, not provider APIs, Kotlin, or gate
plugins. Audited artifact: 1,509,041 bytes, 15 provider/bootstrap classes, adapter
class version 61. SHA-256:
`4e90e660141964643ac32ee8580b74082ffec2460e3ca0a85428db4e88c1c14e`.
The final clean build produced the same bytes as the live-tested jar and passed
all 83 tests (70 plugin, 13 platform-neutral core). The build uses
compile-only source-set dependencies with transitive provider dependencies off;
provider libraries must come from their owning plugins.

`ProviderAdapterBootstrap` checks plugin presence, enabled state, version line,
and the native API class before loading an adapter by class name. Known lines
are Nexo `1.29.x` (including the tested SF dev build), CE `26.9.x`, and Oraxen
`1.211.x`; unknown future major/minor lines fail closed rather than being assumed
compatible. Loading/linkage failures produce an explicit ABSENT diagnostic.
These lines establish the API boundary, not a claim that every patch/server
combination has been tested. Private readiness probes also validate their exact
field shapes and fail loudly if internals change.

## Provider lifecycle and identity

- **Nexo:** `NexoPreItemsLoadEvent` and `NexoItemsLoadedEvent` are present in the
  pinned 1.29 API. Item creation uses `NexoItems.itemFromId(id).build()`;
  identity uses `NexoItems.idFromItem(stack)`. The public API has no loading
  getter. A guarded read-only probe of the verified static boolean `loading`,
  combined with the real events and a deferred initial check, prevents an early
  published nonempty registry from being mistaken for a completed load. Empty
  completed registries become READY. No ItemStack templates are cached, avoiding
  duplicated provider-generated unique data or frozen dynamic processors.
- **CraftEngine:** `CraftEngineItems.byId(Key)` returns `BukkitItemDefinition`;
  `buildBukkitItem()` creates the stack and `getCustomItemId(stack)` identifies it.
  IDs retain `namespace:id`, such as `sf_ores:silver_ingot`. `CraftEngineReloadEvent`
  fires before loading flags reset. The adapter checks public full/pack/host
  loading flags before operations, then reconciles completion on the next tick.
  There is no guessed reload-start event and no legacy 0.0.67 fallback. An observed
  reload ending without successful completion fails instead of returning UNKNOWN_ID.
  Completion evidence belongs to its observed reload cycle: another busy transition
  cancels older callbacks. If still busy at the deferred check, the adapter requires
  a fresh completion instead of reusing the prior success. A failed reload occurring
  entirely between flag polls cannot be detected through this completion-only API.
- **Oraxen:** real `OraxenItems.getItemById(id).build()`, `getIdByItem(stack)` and
  `OraxenItemsLoadedEvent` bindings. The pinned reload is synchronous and exposes
  no pre-load event/loading flag. Main-thread operations cannot interleave with
  the normal reload command; post-load invalidates diagnostics, and every creation
  uses a fresh native build. A guarded read-only probe of the verified static
  `Map map` distinguishes uninitialized from initialized-but-empty registries.
  Off-thread/reentrant registry access during reload is not supported. Oraxen can
  swallow parse errors and retain its prior registry; its post event has no success
  flag, so that failure cannot be reliably identified by this API. No fake pre event
  or uncalled `beginReload()` method is presented as a working integration.

Nexo/Oraxen item IDs retain their native case. Vanilla/Allium/CE keys are normalized
as documented in `ProviderKey`. Provider-ID disagreement is diagnostic and read-only:
if a native API confirms the exact Allium stamp, that appearance wins, even when
old converter tags make another provider recognize the stack too. Otherwise a
single native identity wins; multiple native identities without a confirmed stamp
are ambiguous and refuse matching. Privileged signatures are always verified
against the resulting identity: disagreement never grants or silently re-signs a
privileged behavior. Loading/disabled providers never cause inventory metadata to
be rewritten or persisted items to be deleted.

## Provider × server lane matrix

**PASS** means an actual disposable server run; **unit** means deterministic
fixtures compiled against the pinned API; **pending** is not a support claim.

| Lane | No optional providers | Nexo 1.29 line | CE 26.9 line | Oraxen 1.211 line |
| --- | --- | --- | --- | --- |
| Spigot 1.20.1 / Java 17 | PASS: enable, command packets, clean stop | Not supported by this pinned line | Pin needs Java 21; not tested on this lane | Pin needs Java 21; not tested on this lane |
| Paper 1.20.6 / Java 21 | PASS: enable, command packets, clean stop | Not supported by this pinned line | Pending | Pending runnable plugin |
| Paper 1.21.4/.5/.8/.10/.11 / Java 21 | Pending in this slice | Upstream supported; live pending | Upstream supported; live pending | Pinned source includes these lanes; live pending |
| Paper 26.1/.2 / Java 25 | Pending in this slice | Upstream supported; live pending | Upstream supported; live pending | No support claimed by pinned 1.211 |
| Paper 26.3-141 / Java 25 | PASS: enable, protocol-777 command packets, clean stop | PASS with SF `1.29-dev-b3ebc6e48` | PASS with SF `26.9.2` | No support claimed by pinned 1.211 |
| Spigot 26.3 / Java 25 | Earlier baseline only; not repeated for this slice | Pending | Pending matching Bukkit artifact | No support claimed by pinned 1.211 |

The live Nexo dev build is not the exact 1.29.1 compile artifact. Live CE 26.9.2
is adjacent-patch validation of the 26.9.1 API, not an exact 26.9.1 boot. Upstream
support is an input to the matrix, not substitute evidence for Allium validation.

## Recorded checks

- All three providers absent: real Spigot 1.20.1, Paper 1.20.6 and Paper 26.3
  starts pass without eager optional-class loading.
- Nexo alone and CE alone: Paper 26.3 starts, delayed readiness, native-ID creation
  and signed identity pass. See `tests/live/providers/evidence/alone-*.log`.
- **SF combination:** Nexo + CE + ResourcePackManager **2.4.6**, on Paper
  **26.3-141-54024d3**, Java **25.0.5.0.3**, loopback/offline disposable server.
  Both start LOADING, expose PROVIDER_LOADING in their real reload events, and
  become READY. Native item creation/identification, strict carrier matching,
  reloads and runtime disable pass.
- Actual connected player `GateBot` holds both signed fixture items. The test
  changes provider definitions, reloads providers, and compares existing inventory
  charges (`2`), UUIDs and exact signatures. In the final artifact check, Nexo's
  updater changes PAPER to BOOK. CE's held item stays DIAMOND through reload; its
  native reset updater then changes it to EMERALD (updater version 3), preserving
  Allium state/signature. The test rereads the actual player inventory and never
  calls Allium appearance refresh/re-sign to mask a native update failure. See
  `reload-cycle-final.log`; earlier native runs are retained in `sf-combination*.log`.
- A persisted checkpoint followed by a complete server stop, restart and actual
  GateBot reconnect preserves both items' exact signatures, UUIDs, spent charges
  and single-item quantities. See `restart-checkpoint.log`, `restart-verify.log`
  and the final-artifact repeat in `restart-final.log` in the evidence directory.
- Disabling Nexo makes its adapter ABSENT with CE still READY. Disabling CE makes
  its adapter ABSENT; **CE itself then stops the disposable server** and warns that
  runtime plugin disabling is unsupported. This is a fail-safe observation, not
  an endorsement of hot-unloading or re-enabling the provider.
- Unit cases cover all three services registered together, case-sensitive IDs,
  delayed empty/nonempty readiness, reload/build races, disable events/polling,
  linkage failures, CE namespace round trip and signed-state preservation.
  **All three full native plugins together remain untested.**

Native runtime traces are retained as filtered logs in
[`tests/live/providers/evidence/`](../tests/live/providers/evidence/). Validation
fixture source is in [`tests/live/providers/plugin/`](../tests/live/providers/plugin/).
Build/source work and server tests do not modify the production SF installation.

## CraftEngine updater retention prerequisite

CE's destructive `reset` updater drops custom data unless configured to retain it.
There is no global old/new update event in the pinned API. Configure every reset
operation in the migration chain for Allium-managed items:

```yaml
items:
  sf_ores:silver_ingot:
    material: gold_ingot
    updater:
      '1':
        - type: reset
          keep_components: [minecraft:custom_data]
          keep_tags: [PublicBukkitValues]
```

CE uses `keep_components` on 1.20.5+ and `keep_tags` on older lanes. Both are shown
for a definition shared across lanes. Wildcards are unsupported; preserve the whole
custom-data/PDC container to retain arbitrary `allium:state_*` fields and their
original types. An `apply_data` operation that overwrites these values afterwards
can still invalidate them. Keep Allium's identity/state/signature namespace out
of provider default-value overrides. Native Nexo updates copy old PDC except keys
explicitly overridden by its item definition; the same ownership rule applies.

Allium does not silently rewrite third-party configurations. Test an existing
signed item before raising a CE updater version, trigger the native updater, reread
the replacement item, and compare signatures/state. A reload alone does not prove
that CE's destructive updater was exercised. Trigger configuration and stack counts
also require verification for the server's chosen click/drop/pickup workflow.

## Remaining live gate

1. Obtain a full runnable Oraxen 1.211 plugin and run its supported lane alone
   and with Nexo/CE on a common lane such as Paper 1.21.8. Maven API and `-all` jars
   inspected locally have **no plugin descriptor** and cannot be called runnable
   plugin artifacts. An attempt to build the exact local `v1.211.0` source tag in
   a disposable directory failed because `com.github.VolmitSoftware:Iris:6a89b8bd`
   is unavailable from its declared repositories. No fake plugin descriptor or
   replacement API stubs were used to claim a live Oraxen pass.
2. Complete the other provider-supported lanes and exact stable pin boots,
   including restart inventory checks for Oraxen and the additional combinations.
   The recorded Nexo/CE combination passes an actual restart round trip; that does
   not establish retention for an untested provider or version.
3. Validate the chosen CE inventory update trigger and multi-item stack quantities
   on the deployment's supported lanes. The recorded native-update run invokes the
   same native updater service directly on an actual inventory stack.

Until these rows are completed, S05's entire gate stays unchecked and S06 does not
begin. Pack generation/delivery/visual correctness belongs to S06; RPM presence
here proves coexistence, not a complete production resource-pack configuration.

## Primary references

- [Nexo item API and asynchronous readiness](https://docs.nexomc.com/community-guides/api).
- [Nexo version support](https://docs.nexomc.com/version-support).
- [Oraxen v1.211.0 build targets](https://github.com/oraxen/oraxen/blob/v1.211.0/build.gradle.kts).
- [Oraxen item API](https://docs.oraxen.com/developers/api).
- CE signatures/events verified with pinned 26.9.1 jars/source jars. Reset updater
  behavior was checked in `ResetOperation`, `ItemUpdateConfig`, and `ItemEventListener`
  in the local CraftEngine source as well as the 26.9.2 native run.
