# S21 service signs

Signs are opt-in. The default `enabledSigns: []` activates none. Set
`sign-use-per-second: 4` (1–100) for the shared per-player budget across all types,
faces and blocks. `/allium reload` replaces validated sign settings. Disabling
`modules.signs`, an individual type, the related command family or its module
prevents actions. Unknown type names refuse sign-module startup/reload; legacy
`colour` normalizes to `color`.

Types, in order: **color, warp, kit, balance, heal, repair, disposal, workbench,
anvil, mail, info**. Buy/sell/trade are excluded. All actions are self-service;
paid kit signs call the same kit handler and receipt-backed reservation service as
`/kit`, with the same payer/target identity and inventory admission rules.

Write `[type]` on line one. Recognized headers display in title case, such as `[Repair]` and `[Warp]`. Warp and kit require the destination/kit name on line
two. A warp may explicitly say `public` on line three, but its creator must also
have `allium.signs.create.warp.public` (default false). This authorizes access
through that sign without a per-warp permission; ordinary warp signs retain
`allium.warps.<name>`. All warp signs still require the ordinary `allium.warp`
command permission and use the same safe teleport, readiness and destination
checks. Public signs are grants for that warp name; remove them to revoke access.

Each type has independent false-default permissions:

```
allium.signs.create.<type>
allium.signs.use.<type>
allium.signs.break.<type>
```

Editing an existing action face requires its break permission and the new type's
create permission. Users also need the ordinary command permission to invoke its
service. Color only formats the sign text; it has no executable action. Ampersand
colors in a newly admitted sign are converted on creation. Balance displays
**available** funds without opening an account or mutating money. Heal, repair,
disposal, workbench, anvil and info use the exact handlers registered for their
commands. Repair accepts `Hand` or `All` on line two and a non-negative price on line three. Blank mode means `Hand` and blank price means free. Invalid lines show an on-sign hint and a numbered chat error. Repair preserves item metadata and lists each repaired item in chat.
Inventory utilities refuse while mail/kit receipt recovery holds admission.
Mail opens `/mail read`; info displays server name and visible online count. Other lines
never execute a command or supply a target player.

Allium writes a versioned UUID/type/argument identity into the sign tile's PDC,
separately for FRONT and BACK. Colored headers, sign-item names, and visible text
alone cannot activate anything. Waxing does not alter authority. Standing, wall
and hanging signs use the same face identities. Actions re-read the current tile
and identity after protection handlers finish; cancelled interactions and changed
or destroyed tiles do nothing. Only the main hand is processed; a pending action
per player and the shared budget prevent same-tick duplicate admission. The kit
service additionally owns its existing in-flight/receipt locks.

There is no location cache or SQL sign registry. Chunk saves/reloads and restarts
retain tile PDC; explosion/piston/native destruction removes it with the sign.
A replacement block inherits no authority. Break protection and per-face break
permissions apply before native destruction; environmental destruction remains
subject to the server's normal explosion/piston protection events. Sign settings
never delete economy workflow, journal, marker or recovery evidence.

## Editing and disabled types

The bundled config includes every supported type as a commented `#- type` entry
under `enabledSigns:`. Remove its `#` to enable it; normalization preserves the
checklist even when every type is disabled. `colour` normalizes to `color`.

Submitting a recognizable disabled header keeps all four lines as vanilla text,
creates no action identity, and sends `signs.disabled`. Existing authenticated
faces still require their break permission to replace. Late protection
cancellation is respected. Actionable enabled faces suppress the native sign
editor only for interaction opens, using the runtime's `PlayerSignOpenEvent`;
placement and explicit plugin editor opens remain available. The interaction
itself retains the deferred protection check. Repair success/refusal use the
colored, sound-configurable language keys `utility.repair-held` and
`utility.repair-hold-damaged`.


Headers and the mode, name and price lines are read without color codes. Re-editing a face keeps
each line's existing style on Paper, so an Allium header arrives as `§1[repair]`; before this
was handled, re-editing an Allium sign on Paper silently dropped its identity.

Repair signs store mode and price in the sign identity. A priced repair sign is settled so a
crash never loses money and never repairs for free:

1. **Reserve.** The player is saved, then the price is held (not yet spent) as a `REPAIR`
   reservation. Damaged items are planned first, so an undamaged hand or inventory is never
   charged, and too little money refuses with nothing repaired, held or charged.
2. **Apply and save.** On the server thread the plan is rechecked (any changed item releases the
   hold), the items are repaired, the workflow ID is added to the player's
   `allium:repair_receipts`, and the player is saved: repaired items and receipt reach disk in one
   file write.
3. **Capture.** Once the saved file is proven to list the receipt, the hold is captured and the
   receipt dropped.

A hold whose receipt the saved file lacks is released. That decision is made from the complete,
validated player file at the player's next join, or at startup for an offline player
(`EconomyRecovery`). `/eco reservations` and `/eco resolve REPAIR <workflow> <capture|release>
<reason>` work for repair holds like kit and mail holds. Fault points
`repair.halt-after-reserve` and `repair.halt-after-save` (with `-Dallium.fault-injection=true`)
halt the JVM at each boundary for gates.
Paid kit signs retain their existing receipt recovery. Stock repair messages update automatically. Custom repair messages can include `{items}` for the comma-separated list.

## Paid signs and `unsafe-sign-prices`

Every actionable sign type (`warp`, `kit`, `balance`, `heal`, `repair`, `disposal`, `workbench`,
`anvil`, `mail`, `info`) accepts an optional non-negative price (plain decimal or `k`/`m`/`b`/`t`
shorthand, optionally prefixed/suffixed with `economy.currency-symbol`). Place the price on line
three — or on line four for a public `[Warp]` sign whose line three is `public`. Blank means free
(or, on a `[Kit]` sign, the kit's configured command cost).

- **Priced `[Kit]` signs** record an explicit price override in the face identity and bypass the
  kit's configured command cost, charging the sign's price through the same E5 `KIT` reservation
  and `allium:kit_receipts` crash-recovery workflow.
- **Priced `[Repair]` signs** continue to use `PaidRepairService` (`REPAIR` reservation +
  `allium:repair_receipts`).
- **Other priced service signs** (`[Warp]`, `[Heal]`, `[Disposal]`, `[Workbench]`, `[Anvil]`,
  `[Balance]`, `[Mail]`, `[Info]`) precheck the player's command permission and destination/service
  readiness, then charge atomically through `PaidSignService` (`SIGN` `SPEND`) before running the
  action.
- **`unsafe-sign-prices: false` (default):** refuses creating a sign whose explicit price is lower
  than the underlying kit/command's configured cost (highlighting the price line in red and sending
  `signs.unsafe-price`). Set `unsafe-sign-prices: true` to allow discounted or free override signs.

Set `economy.currency-symbol` and `economy.currency-symbol-suffix` to control display
and optional symbol placement on sign prices. Enable `economy-log-enabled` for
`plugins/Allium/trade.log` entries for successful paid kit, sign, mail, cheque, `/pay`, `/tax`
and `/eco` or `/money` balance changes. Use `economy-log-uuids`
for UUIDs and `economy-log-update-enabled` to opt into Vault API mutations.
Each CSV row contains the timestamp, origin, action, actor, account, exact amount,
and operation ID. The SQLite operation journal remains
the authoritative transaction record.
