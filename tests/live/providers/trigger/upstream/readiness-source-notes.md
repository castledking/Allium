# Pinned readiness audit notes

These notes support the two short submission drafts. They describe the inspected
versions, not an assumed future API. Paths below identify local audit inputs;
they are not dependencies of the proposed public API.

## Nexo 1.29.1

Exact Maven JAR SHA-256:
`3f0f6578d49c9c24c6bbdcd3dafb2bb43980d9929d069345ab5ae9a646209cbf`.
The cached artifact at
`/home/towkio/.gradle/caches/modules-2/files-2.1/com.nexomc/nexo/1.29.1/1a93983d1b2e9cfdf120b9f605441692a56fc458/nexo-1.29.1.jar`
matches `/tmp/allium-s05-20261106/pins/nexo/official-nexo-1.29.1.module`.
No source JAR/checkout was available: this audit inspected exact bytecode and
embedded Kotlin source-line tables. Positions below are embedded source positions,
not links to locally available `.kt` files. Retained release research associates
the version with `ee6c06915e88bfe268f71d5523ac22fe1c7f2bdc`; this was not verified
against a source checkout and is not used as source-build provenance.

- `NexoItems.kt:41–45`: public collections start empty; `loading` and `pendingLoad`
  are private static fields with no public getters.
- `NexoPlugin.kt:88–92`: initial loading is queued for the next tick. The busy flag
  remains false before that task, so `!loading` alone cannot mean ready.
- `NexoItems.kt:223–248`: set busy, clear/parse config and registry, then fire
  `NexoPreItemsLoadEvent`. This pre event does not precede all registry mutation.
- `255–272`: deferred contributions can cause preliminary publication, followed
  by a wait with a 60-second timeout. Deferred errors/timeouts are logged and
  loading continues; the completion handler also logs escaping failures.
- `297–309`: final publication/reference resolution/callback cleanup, then busy
  false, then `NexoItemsLoadedEvent`. The event has no outcome payload. Neither
  loading nor finalization has a `finally` flag reset; an escaping failure can
  leave busy true. External-item parsing can also log and omit failed items.
- Existing `NexoItems.loadItems()` and `ReloadCommand.reloadItems()` return
  `CompletableFuture<Void>`. They observe a caller's own invocation, not a passive
  snapshot of startup/another caller's attempt; logged contribution failures do
  not necessarily make the future exceptional.

Therefore request initial/reload state plus attempt outcomes, with documented
partial-failure semantics. Do not describe the missing API as merely a getter
for the current boolean or claim no asynchronous return API exists.

## Oraxen 1.211.0 and 1.220.1

Exact upstream tags recovered in the retained audit:

- `v1.211.0`: `43fd84ef4376fb3445e58084686d4f9b7bc312b6`, source root
  `/tmp/allium-s05-20261106/oraxen/source`; tag evidence is
  `/tmp/allium-s05-20261106/oraxen/logs/official-tag.json`.
- `v1.220.1`: `cb06f1524a88b88ef9c63c396b3281e656c87a4d`, audited files in
  `/tmp/allium-s05-20261106/oraxen/audit-1.220.1`; lifecycle files match the cached
  tag-tree blob hashes under `publication-support/`.

The older November 5 checkout has a local snapshot commit; it is not the upstream
1.211.0 revision. Use the tag above. The inspected 1.220.1 source agrees with the
supplied runtime's registry shape; these findings do not accept arbitrary versions.

- 1.211.0 `core/src/main/java/io/th0rgal/oraxen/api/OraxenItems.java:40,80,213`:
  `map`/`items` initially null; public `getMap()` substitutes an empty `HashMap`.
  Loading catches/logs `Exception` and returns `void`. Failure can retain an old
  or null map, or occur after publication and leave incomplete processing.
- 1.220.1 `api-OraxenItems.java:38,40,72,201,250`: private volatile
  `RegistrySnapshot` starts with `Map.of()`, `Map.of()`, `Set.of()`. Successful
  loads publish fresh snapshots, including valid empty loads. Public content
  getters expose no initialized/loading/outcome state. Failure before assignment
  retains the prior snapshot/sentinel but does not roll back cleared caches and
  mechanics. Per-item reference failures can also be swallowed before publication.
- Both versions load items inline in `onEnable()`, then schedule the startup
  loaded event through `postLoading()`: 1.211.0 `OraxenPlugin.java:146,173`;
  1.220.1 `OraxenPlugin.java:157,221`.
- Reload is synchronous: load items, regenerate inventory manager, emit
  `OraxenItemsLoadedEvent`, then optional item/furniture updates; full reload also
  continues with pack/HUD/recipes. References: 1.211.0
  `commands/ReloadCommand.java:30`; 1.220.1 `commands-ReloadCommand.java:36`.
- Neither inspected version supplies an item pre-load/pre-reload event. The loaded
  event contains only Bukkit handler plumbing, with no result field. A swallowed
  failure can still reach dispatch if intervening work completes; an escaping
  failure can prevent dispatch. Neither the event nor retained definitions proves
  that the attempt succeeded or that every reload stage is complete.

A public query must distinguish a completed empty registry from initialization.
Start and terminal notifications should agree with that query and document the
item-registry boundary, partial failures, and usability after a failed attempt.
