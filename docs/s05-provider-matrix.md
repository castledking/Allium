# S05 provider implementation and validation

Updated 2026-11-06. **The recorded S05 gate passes with the count-preserving CE
source patch.** Both full patched runtimes pass 20 native cases / 28 observations
each. Stock CE 26.9.1/26.9.2 still reduce reset stacks from seven to one; they do
not inherit this PASS. Adapter, lifecycle and coexistence results retain their
individual scopes below. S06 remains unstarted. No production SF installation
was modified.

## Build and accepted runtimes

| Isolated source set | Compile-only API pin | Explicitly verified runtime |
| --- | --- | --- |
| `nexoAdapter` | `com.nexomc:nexo:1.29.1` | User-supplied `1.30-dev-36f4ce686` only |
| `craftengineAdapter` | `net.momirealms:craft-engine-core:26.9.1`, `craft-engine-bukkit:26.9.1` | Tested 26.9.1 and adjacent 26.9.2 |
| `oraxenAdapter` | `io.th0rgal:oraxen:1.211.0` | User-supplied `1.220.1` only |

Shared code and `allium-core` remain Java 17; provider APIs compile with JDK 21 and
emit `--release 17`. Core has no Bukkit dependency. The supplied runtimes retain
the bound native build/identity/event signatures; the Oraxen registry change has
a separate guarded probe. Accepting these exact builds does not accept arbitrary
future 1.30.x/1.220.x versions. Nexo and Oraxen bootstrap acceptance is restricted
to the exact supplied version strings above; their compile-only 1.29.x/1.211.x
API lines are not accepted runtime lines. Stock Nexo 1.29.1 has no runnable
artifact available in the recorded investigation and has not been live-verified.
Earlier Nexo 1.29 dev results do not establish stock 1.29.1 or 1.29.x support.
The historical recovered Oraxen 1.211.0 source-build result is scoped to its exact
artifact below, not an arbitrary stock distribution reporting that version.
Bootstrap does not fingerprint jars, so `1.211.0` remains rejected rather than
allowing all jars with that version string. CE's existing 26.9.x acceptance policy
is unchanged; its reset-safety release condition remains separate below.

The original S05-gated jar is 1,510,926 bytes, with 16 provider/bootstrap classes at
class major 61, and no native provider APIs, Kotlin or gate fixtures bundled. SHA-256:
`de103116a3724cb520d2351c5d9bc25aea5fd7fa91b2c997c74922ecbac580e7`.
The recorded unit suite passed **87 tests** (74 plugin, 13 platform-neutral core).
Nine separate runner regression tests and two native event fault-injection checks
verify that skipped assertions and abnormal process exits cannot produce a PASS.
[Supplied runtime hashes and reproduction](../tests/live/providers/supplied/README.md)
and the result manifests identify each live-tested Allium jar independently.
Earlier 2026-11-05/early 2026-11-06 runs used the previous committed artifact;
they are not presented as byte-identical to this original gate jar. The live
evidence and its hashes remain unchanged. This follow-up changes startup warnings
and bootstrap support policy; its artifact and unit-test/build results are recorded
separately. The original hash and 87-test count do not identify the current build
or establish a native runtime PASS for the follow-up.

The startup-warning/support-policy follow-up passed the root agent's offline
Gradle test and `shadowJar` run: **105 tests** (92 plugin, 13 platform-neutral
core), with no failures, errors or skips. Focused coverage includes delayed
readiness, managed preferred/alias bindings, native reset-chain inspection,
converter defaults and unchanged configuration bytes, and exact runtime allowlists.
This is separate unit/build validation, not a rerun of the original native gate.

Bootstrap checks presence, enabled state, supported version and API class before
loading an adapter by class-name string. Missing/incompatible/linkage failures
produce ABSENT with a diagnostic. Main code never eagerly references a provider
class. Provider libraries come from their owning plugins.

Startup diagnostics only warn; they never edit vendor configuration, update an
inventory, reload a provider or re-sign an item. The scheduled check runs after
`onEnable` registrations and waits while CraftEngine is LOADING. Once READY, it
reads a fresh snapshot of ItemService's actual appearance-to-behavior bindings,
including preferred appearances and aliases. Only those managed CE IDs are
inspected; unrelated native definitions do not produce reset warnings. The
isolated adapter reads `ItemDefinition.updater()` and the guarded private
`ItemUpdateConfig.versions` list, then checks native `Version.updaters()` for
`ResetOperation` across the entire chain without executing an updater or building
an item. Inspection failures warn separately and do not fail an otherwise healthy
provider. This startup check is not a continuous configuration/reload audit.

When enabled Nexo and installed Oraxen coexist, the startup check reads Nexo's
`converter.yml` without calling its lazy native converter getter. The inspected
compile pin and supplied runtime bytecode give all four `oraxenConverter` switches
a default of true, including a missing section; a missing file is not created.
Any enabled switch warns about automatic migration and points to the coexistence
recipe below. These are startup file settings, not a probe of changed in-memory
converter state. No new native runtime run is claimed for these warning changes.

## Readiness and identity

- **Nexo:** verified native `itemFromId(id).build()`, `idFromItem(stack)`,
  `NexoPreItemsLoadEvent` and `NexoItemsLoadedEvent`. Its private static boolean
  `loading` has a guarded read-only shape probe because there is no public getter.
  Initial probing is deferred past its queued first load; an early published
  registry does not imply READY. Completed empty registries are valid. Builds are
  fresh; caching templates could duplicate native unique data or freeze processors.
- **CraftEngine:** `byId(Key)` yields `BukkitItemDefinition`, `buildBukkitItem()`
  creates, and `getCustomItemId(stack)` identifies. Full `namespace:id` survives.
  Native full/pack/host loading flags gate every operation. Completion precedes
  flag reset, so reconciliation is deferred. Completion belongs to its observed
  cycle: new busy transitions cancel old callbacks, and continued loading at the
  deferred check requires fresh success. An observed failed reload cannot expose
  partial definitions as UNKNOWN_ID. A failure entirely between polls is not
  observable through this completion-only API. No legacy 0.0.67 fallback exists.
- **Oraxen:** verified `getItemById(id).build()`, `getIdByItem(stack)` and
  `OraxenItemsLoadedEvent`. Reload is synchronous; no fictitious pre event is used.
  The historical source-built 1.211.0 probe checks its null/uninitialized static
  `Map map`; retaining that probe does not accept the 1.211.x runtime line. Exact
  1.220.1 uses a volatile `RegistrySnapshot`; its verified schema and publication pattern
  distinguish the initial `Map.of()` sentinel from a published empty registry.
  Both probes are read-only and fail loudly on schema/access changes. Oraxen can
  swallow parse errors and keep an old registry; its post event has no success
  bit, so that failure remains unobservable. Off-thread/reentrant reload access
  is not a supported contract. No native ItemStack templates are cached.

External IDs keep Nexo/Oraxen case; vanilla/Allium/CE keys normalize as documented
in `ProviderKey`. When multiple native APIs recognize converter tags, an exact
API-confirmed Allium appearance stamp wins. Otherwise one native identity wins;
multiple unconfirmed identities refuse matching with a diagnostic. Privileged
MACs must verify against that resulting identity. Disagreement never grants or
re-signs behavior. Loading/absence does not delete persisted stacks or rewrite PDC.

## Runtime matrix

**H PASS** = actual native server, creation/identity, signed ItemStack YAML,
provider reload and a full restart restoring that YAML. It is **headless**, not a
player inventory check. **I PASS** = actual protocol client and player inventory,
native update/reload/restart checks. Neither label claims resource-pack visuals,
all commands, every provider feature, or stock CE multi-item reset safety.

| Server / Java | No optional providers | Nexo | CraftEngine | Oraxen |
| --- | --- | --- | --- | --- |
| Spigot 1.20.1 / 17 | PASS incl. real command packets | Supplied pin requires Paper | Bukkit pin not published for this lane | Supplied pin requires Paper |
| Paper 1.20.6 / 21 | PASS incl. real command packets | Unsupported by pinned Nexo line | H PASS exact Paper 26.9.1 | Not tested on this lane |
| Paper 1.21.4-232 / 21 | Not separately run | H PASS supplied 1.30 dev | H PASS exact 26.9.1 | H PASS supplied 1.220.1 |
| Paper 1.21.5-114 ALPHA / 21 | Not separately run | H PASS supplied 1.30 dev | H PASS exact 26.9.1 | H PASS supplied 1.220.1 |
| Paper 1.21.8-60 / 21 | Not separately run | H + I PASS supplied 1.30 dev | H + I PASS exact 26.9.1 | H + I PASS supplied 1.220.1 |
| Paper 1.21.10-130 / 21 | Not separately run | H PASS supplied 1.30 dev | H PASS exact 26.9.1 | H PASS supplied 1.220.1 |
| Paper 1.21.11-132 / 21 | Not separately run | H PASS supplied 1.30 dev | H PASS exact 26.9.1 | H PASS supplied 1.220.1 |
| Paper 26.1.2-74 / 25 | Not separately run | H PASS supplied 1.30 dev | H PASS exact 26.9.1 | H PASS supplied 1.220.1 |
| Paper 26.2-135 / 25 | Not separately run | H PASS supplied 1.30 dev | H PASS exact 26.9.1 | H PASS supplied 1.220.1 |
| Paper 26.3-141 / 25 | PASS incl. protocol777 command packets | H PASS supplied 1.30 dev; earlier I PASS 1.29 dev | H PASS 26.9.2; earlier I PASS 26.9.2 | H PASS supplied 1.220.1 |
| Spigot 26.2 / 25 | Not separately run | Paper plugin | H PASS exact Bukkit 26.9.1 | Supplied Paper plugin |
| Spigot 26.3 / 25 | PASS repeated 2026-11-06 | Paper plugin | Exact 26.9.1 release omits 26.3; 26.9.2 Bukkit pending | Supplied Paper plugin |

Exact CE distributions came from official Modrinth releases
[Paper EDh6mvv2](https://modrinth.com/plugin/craftengine/version/EDh6mvv2) and
[Bukkit prX5nH7G](https://modrinth.com/plugin/craftengine/version/prX5nH7G). Both are
labelled beta. Their 26.9.1 metadata omits 26.3; the 26.9.2 pass must not be
transferred to that exact pin. Nexo's supplied dev build is not stable 1.29.1;
stock 1.29.1 remains unavailable for live validation and is rejected by bootstrap.
Paper 1.21.5 has no stable build in the queried service; its ALPHA test result is
not a stable-server recommendation. Version support is limited to these checks.

## Native inventory and coexistence checks

On Paper 1.21.8, the supplied Oraxen passes alone. Supplied Nexo + exact CE 26.9.1
+ supplied Oraxen + RPM 2.4.6 also pass together, with an actual GateBot holding
all three signed items. The tests change definitions, reload, invoke native
updaters, reread actual inventory slots, stop the server, reconnect after restart
and compare exact signatures/UUIDs/charges/quantities. No Allium refresh/re-sign
masks native update results. Oraxen case-sensitive IDs and independent new UUIDs
are checked. Disable makes Oraxen/Nexo ABSENT without disabling the other provider.
Earlier Paper 26.3 SF-stack runs cover Nexo 1.29 dev + CE 26.9.2 + RPM 2.4.6.

**Coexistence prerequisite:** disable Nexo's automatic Oraxen migration before
boot when preserving both providers' native identities:

```yaml
# plugins/Nexo/converter.yml
oraxenConverter:
  convertItems: false
  convertResourcePack: false
  convertSettings: false
  convertFurnitureOnLoad: false
```

The default-enabled migration failed the mixed inventory restart: the Oraxen
native API returned null while Nexo returned `Gate_Oraxen`; the stack carried
`nexo:id` instead of `oraxen:id`. Allium's state and original MAC stayed unchanged.
A fresh fixture with migration disabled passes. Allium does not silently edit
vendor configs or re-sign a changed provider identity. See the negative
[migration diagnostic](../tests/live/providers/evidence/nexo-oraxen-migration-20261106.log).
Pack generation/delivery/hosting is disabled in fixtures; RPM presence proves
coexistence only. CE intentionally shuts down a server when disabled at runtime;
the earlier test recorded containment, not supported hot unloading/re-enabling.

## CE reset retention and conditional quantity gate

Every destructive reset in an Allium-managed item's version chain must retain
custom data. The pinned API has no global old/new update event:

```yaml
updater:
  '2':
    - type: reset
      keep_components: [minecraft:custom_data]
      keep_tags: [PublicBukkitValues]
```

CE uses components on 1.20.5+ and tags on older lanes. Preserve the whole container
for arbitrary `allium:state_*` keys/types; wildcards are unsupported. Subsequent
`apply_data` can overwrite values. Nexo likewise copies old PDC except explicit
provider overrides; keep Allium-owned keys out of provider default overrides.

The genuine client-trigger suite covers clicks, full-stack drops and pickups:
24 cases across exact CE 26.9.1/Paper 1.21.8 and CE 26.9.2/Paper 26.3. Each trigger
checks a current-version seven-item control, a one-item reset, a seven-item reset
and an independent seven-item repeat. All metadata types, exact MAC/UUID, spent
charges and raw custom data survive with the recipe above. **All six seven-item
reset cases per runtime lose six items (7 → 1).** Controls and one-item resets pass.
The fixture observes genuine native events and rereads cursor/entity/inventory
after continuation; it does not manually fire Bukkit events or re-sign items.

There is no reset amount-retention setting in the inspected native factories.
The minimal upstream fix builds the replacement with `item.count()`, before
native dynamic processors run. Two isolated full Paper plugin builds apply only
that Java source change to the exact revisions used by the failing runtimes:

| Patched runtime / lane | Source revision | Full artifact SHA-256 |
| --- | --- | --- |
| CE 26.9.1 / Paper 1.21.8 | `2b9e6f59a0bf4dac7f71958c0ea96c14b8e44985` | `9b86b1044e0d97d800e1722b1ca2b01f2582d44269a28508da7b9b79babfcd41` |
| SF CE 26.9.2 / Paper 26.3 | `94b74598312d5a16216caed1c07fe3966d854096` | `d779823f05922747a70a268888610f408dcfa151b194ef46d28d483393d74852` |

Both patched artifacts first pass the original 12-case diagnostic, then pass an
expanded 20-case / 28-observation suite each, using the final Allium jar above:

- Every original control/reset/repeat plus a 64-item reset per trigger.
- Dynamic `count=<arg:item.count>` definitions: two reset migrations in one
  trigger to version 3, another migration of the same existing stack to version
  4, then a current-version no-op. Names render `count=7` throughout.
- Native right-click split `7 → 3 + 4`, then left-click merge back to 7.
- Native single-item drop leaves six old-version items held and one migrated item
  entity; dropping the remaining six migrates them and preserves a total of seven.

Exact MACs, instance UUIDs, spent state, PDC types/values and raw custom data
survive all observations. Both clients and servers exit zero without forced
shutdown or logged exceptions. Relocating an existing stack for a repeat compares
its full snapshot before/after; it does not create or re-sign an item. No Allium
inventory repair or post-baseline refresh masks the native result.

The [patch, retained evidence and reproduction](../tests/live/providers/trigger/README.md)
and [source-build provenance](../tests/live/providers/trigger/builds/README.md)
identify these as local patched builds, not official stock distributions.
Compilation uses the original dependencies; an external compiler-only classpath
ordering adjustment puts the declared Gson ahead of an older copy shaded inside
Geyser. Runtime packaging and dependency versions are unchanged.

**Release condition:** use a verified count-preserving CE build for reset
updaters, plus the retention recipe above. Allium's version check does not
fingerprint or automatically patch the installed provider. Stock binaries remain
unsafe for these resets. The fix conserves quantity; it does not split stacks
when a migration lowers the maximum stack size, or override processors that
intentionally replace items/counts. These runs do not transfer to other CE
binaries, Bukkit builds, lanes or configuration combinations.

## Evidence and remaining work

- [Supplied all-three lane manifest](../tests/live/providers/evidence/supplied-lanes-20261106.json):
  eight headless lanes, final artifact, converter disabled before boot.
- [Actual inventory manifest](../tests/live/providers/evidence/supplied-inventory-20261106.json):
  supplied Oraxen alone and all-three + RPM, two boots each.
- [Earlier exact CE lane manifest](../tests/live/providers/evidence/paper-lanes-20261106.json)
  and [Spigot results](../tests/live/providers/spigot/RESULTS.md): scopes and earlier
  artifact hashes retained independently. A quota-failed Spigot attempt was retried
  fresh; only the clean retry is a PASS. No passing run contains quota/save failures.
- [CE trigger diagnostic](../tests/live/providers/trigger/README.md): retained stock
  failures, patched passes, typed metadata snapshots, native source evidence and
  exact-source build provenance.
- The recovered Oraxen `v1.211.0` source build, using a genuine source-built Iris
  dependency, passed native creation/identity, inventory, reload/update, restart
  and disable checks on Paper 1.21.8-60 across two boots. This is historical
  evidence for **exactly** `oraxen-1.211.0-source-built.jar` (4,505,675 bytes),
  SHA-256 `fa2ae33f4cbc08a396fd8cdccce2b044ddeb4b6706a00a365119c299e9bfc036`,
  with the earlier Allium artifact identified in its manifest. The retained local
  evidence is `/tmp/allium-s05-20261106/oraxen/logs/`: `artifact-manifest.json`,
  `native-result.json`, `evidence-boot-1.log` and `evidence-boot-2.log`.
  It does not verify the official stock 1.211.0 binary or the 1.211.x line, and
  version text alone cannot authorize that exact source-built artifact at runtime.
  The current predicate therefore rejects `1.211.0`; supplied `1.220.1` remains
  directly validated and accepted. No fake API jar descriptor was used.

The recorded S05 gate is complete for the explicit patched configurations above.
Interactive coverage outside the recorded lanes, unlisted server/provider
releases, pack visuals, GUI compatibility and complete production configuration
still need their release checks. Deploying a verified provider fix is separate
from this validation; no production files or running services were changed. S06
has not begun.
