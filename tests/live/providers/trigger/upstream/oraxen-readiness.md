Title: Expose public item-registry readiness and reload outcome

Body:

Could Oraxen expose a public registry-readiness/loading query and the latest
initial-load/reload result? Integrations attaching after load events need to
distinguish not-yet-loaded from a successfully loaded empty registry and an
unknown item ID.

In 1.211.0, the private registry `map` starts null; public item-map access turns
that into an empty map. In 1.220.1, the registry instead starts with an empty
`RegistrySnapshot` sentinel, with no public initialized/readiness getter. Testing
whether the item collection is empty cannot distinguish these states reliably.

The inspected reload path is synchronous and provides
`OraxenItemsLoadedEvent`, without a corresponding pre-load event or a success
field. A caught load failure can retain the old registry and still reach that
event; 1.211.0 can also fail after publishing partially processed state. The event
precedes optional item updates and the remaining full-reload work.

Please expose queryable state covering startup and each reload, document its
ordering relative to registry publication and the loaded event, and report a
failed/partial attempt separately from registry availability. Document whether
previous definitions remain usable after failure; retaining a snapshot does not
roll back all cleared caches/mechanics. A valid empty load should become ready.
An explicit start notification and terminal outcome would let integrations avoid
private probes and treating retained items or a post event as proof of success.
