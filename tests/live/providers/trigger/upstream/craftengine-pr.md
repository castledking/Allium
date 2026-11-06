Title: Preserve stack count when resetting an item

Body:

`reset` rebuilds an existing stack with `buildItem(context)`, whose default count
is 1. A version-1 stack of seven IRON_INGOT items therefore becomes one EMERALD
when a version-2 reset runs through a real inventory click, full-stack drop, or
pickup. The expected result is seven EMERALDs.

Pass the existing count into construction:

```java
Item newItem = this.item.get().buildItem(context, item.count());
```

This also supplies the correct count before dynamic processors run; setting the
amount after construction would leave count-dependent names/data built for one.
Component/tag retention cannot preserve count because it is a separate field.

To reproduce, enable `item.update-triggers.click-in-inventory`, `drop`, and
`pick-up`; give a survival player seven items with a version-1 reset definition;
change `material: iron_ingot` to `emerald` and the updater key from `'1'` to `'2'`;
run `/ce reload`, then trigger one update on that existing stack. Inspect the
cursor after a left-click, the live entity after a full-stack drop, or inventory
after pickup. Stock gives 1; this patch preserves 7.

Validated against stock CE 26.9.1 / Paper 1.21.8 and SF CE 26.9.2 / Paper 26.3
(stock fails), then full Paper JARs built from their exact source revisions
`2b9e6f59a0bf4dac7f71958c0ea96c14b8e44985` and
`94b74598312d5a16216caed1c07fe3966d854096` with this one-line Java change:
20 cases / 28 observations pass on each, including dynamic count names,
successive resets, split/merge and partial drops. Custom data remains intact.
