Title: Expose public item-registry readiness and load outcome

Body:

Integrations need to distinguish an unknown item from a registry that has not
finished loading, including when they attach after the load events have fired.

In 1.29.1, `NexoItems.loading` is private and initially false before the queued
first load. Empty public collections can mean pre-load or a valid empty load;
preliminary publication can also precede completion. `NexoPreItemsLoadEvent`
follows initial parsing, while `NexoItemsLoadedEvent` fires after the busy flag
clears and has no outcome payload. Deferred failures/timeouts can be logged and
followed by completion; an escaping failure can instead leave the flag set.

`NexoItems.loadItems()` and `ReloadCommand.reloadItems()` already return
`CompletableFuture<Void>`. These help callers observe their own invocation, but
provide no passive query for startup/another caller's reload and do not report
all swallowed contribution failures.

Please expose a supported public readiness/loading query covering the pending
initial load and every reload, together with the latest load's success/failure
result, including partial completion. Define publication/event ordering and
whether previous definitions remain usable after failure; a successfully loaded
empty registry should be ready. A busy getter alone cannot establish initial
success or recover the outcome of a missed load cycle. This would let late-loading
integrations classify missing IDs without private-field reflection or timing
guesses.
