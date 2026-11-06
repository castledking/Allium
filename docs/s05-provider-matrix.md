# S05 provider implementation and validation

Updated 2026-11-06. **The quantity gate fails:** CraftEngine's native reset updater
reduces a stack of seven to one. Adapter implementation, provider lifecycle,
identity, signatures and the recorded native runs below pass their stated scopes.
S06 remains unstarted. No production SF installation was modified.

## Build and accepted runtimes

| Isolated source set | Compile-only API pin | Additional explicitly verified runtime |
| --- | --- | --- |
| `nexoAdapter` | `com.nexomc:nexo:1.29.1` | User-supplied `1.30-dev-36f4ce686` only |
| `craftengineAdapter` | `net.momirealms:craft-engine-core:26.9.1`, `craft-engine-bukkit:26.9.1` | Tested 26.9.1 and adjacent 26.9.2 |
| `oraxenAdapter` | `io.th0rgal:oraxen:1.211.0` | User-supplied `1.220.1` only |

Shared code and `allium-core` remain Java 17; provider APIs compile with JDK 21 and
emit `--release 17`. Core has no Bukkit dependency. The supplied runtimes retain
the bound native build/identity/event signatures; the Oraxen registry change has
a separate guarded probe. Accepting these exact builds does not accept arbitrary
future 1.30.x/1.220.x versions. Original 1.29.x/1.211.x API lines remain accepted.

The final jar is 1,510,926 bytes, with 16 provider/bootstrap classes at class major
61, and no native provider APIs, Kotlin or gate fixtures bundled. SHA-256:
`de103116a3724cb520d2351c5d9bc25aea5fd7fa91b2c997c74922ecbac580e7`.
The unit suite passes **87 tests** (74 plugin, 13 platform-neutral core).
Nine separate runner regression tests and two native event fault-injection checks
verify that skipped assertions and abnormal process exits cannot produce a PASS.
[Supplied runtime hashes and reproduction](../tests/live/providers/supplied/README.md)
and the result manifests identify each live-tested Allium jar independently.
Earlier 2026-11-05/early 2026-11-06 runs used the previous committed artifact;
they are not presented as byte-identical to this final jar.

Bootstrap checks presence, enabled state, supported version and API class before
loading an adapter by class-name string. Missing/incompatible/linkage failures
produce ABSENT with a diagnostic. Main code never eagerly references a provider
class. Provider libraries come from their owning plugins.

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
  The 1.211 probe checks its null/uninitialized static `Map map`. Exact 1.220.1
  uses a volatile `RegistrySnapshot`; its verified schema and publication pattern
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
transferred to that exact pin. Nexo's supplied dev build is not stable 1.29.1.
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

## CE reset retention and failing quantity gate

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
The proposed minimal upstream fix builds the replacement with `item.count()`.
The [patch and full reproduction](../tests/live/providers/trigger/README.md) are
reviewable; the patch has not been applied, built or accepted as a passing fix.
This external bug keeps S05's full quantity gate open. An unvalidated listener
that guesses counts or repairs/re-signs inventory is not used to hide it.

## Evidence and remaining work

- [Supplied all-three lane manifest](../tests/live/providers/evidence/supplied-lanes-20261106.json):
  eight headless lanes, final artifact, converter disabled before boot.
- [Actual inventory manifest](../tests/live/providers/evidence/supplied-inventory-20261106.json):
  supplied Oraxen alone and all-three + RPM, two boots each.
- [Earlier exact CE lane manifest](../tests/live/providers/evidence/paper-lanes-20261106.json)
  and [Spigot results](../tests/live/providers/spigot/RESULTS.md): scopes and earlier
  artifact hashes retained independently. A quota-failed Spigot attempt was retried
  fresh; only the clean retry is a PASS. No passing run contains quota/save failures.
- [CE trigger diagnostic](../tests/live/providers/trigger/README.md): quantity failure,
  typed metadata snapshots, native source evidence and proposed upstream patch.
- The older Oraxen 1.211 source tag was also recovered/built with a genuine
  source-built Iris dependency and passed its native Paper 1.21.8 check. It is a
  source-built artifact, not the official binary; supplied 1.220.1 is now directly
  validated and no fake API jar descriptor was used.

To close the current gate, obtain/fix the native CE reset count behavior and rerun
the trigger suite requiring every quantity assertion to pass. Interactive coverage
outside the recorded common lane, unlisted server/provider releases, pack visuals,
GUI compatibility and complete production configuration still need their release
checks. S06 has not begun. Nothing here constitutes a production deployment.
