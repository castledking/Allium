# S22 buy, sell and trade signs — design only

Status: proposed. S21 contains no shop code. No shop schema migration, provider
release or deployment is introduced here. Use one SQLite WAL FULL database and
reuse E5's reservations, native receipts and recovery decisions. A Vault
withdraw/refund sequence is not a substitute for a transactional workflow.

## Phases and shop ownership

1. **First implementation: server shops, buy and sell only.** Owner is the literal
   server authority, never a player's UUID. Supply is infinite; no SQL item stock
   or item escrow exists. A server sell creates the configured exact item; a
   server buyback consumes accepted items. Money is an explicitly configured,
   journalled server sink/source. Normal account bounds still apply. Creating a
   sign cannot mint money: staff must publish an authorized server quote and
   buyback limits. Disabled quotes stop new admissions. No player owner is debited.
2. **Later phase: player shops.** Exact finite items are escrowed in SQL and
   buybacks use the owner's explicitly funded budget. Stocking, buying, selling
   and stock withdrawal each need their own receipt-backed workflows and gates.
3. **Later phase: trades.** Item-for-item (optionally money) exchange requires its
   own recovery/delivery-obligation gates. It is not included with server shops
   or implicitly enabled when player shops arrive.

Each phase needs its own schema/version, domain/concurrency tests, crash matrix,
real-client Spigot/Paper gates and latency evidence. None is implemented by S22.

Server shops have **no item-stock escrow**. Buyer money reservations still protect
paid delivery as in E5; they are not a finite shop-stock balance. For server sales,
reserve buyer money plus the PENDING delivery intent, deliver/save/prove once,
then capture into an audited server sink with the acknowledgement. Receipt absence
releases the buyer hold. Infinite supply never authorizes redelivery on recovery.

For server buybacks, freeze the accepted item/slot plan and exact payout in a
PENDING workflow; validate recipient cap and configured buyback limits before any
removal. Remove items once and save/prove a native removal receipt. One keyed
transaction credits the player from the audited server source and acknowledges
consumption. There is no owner-fund or stock reservation and no zero-value money
hold. If the recipient cap changes before credit, preserve the consumed-item
receipt and payout obligation for recovery/operator reconciliation. Never repeat
removal, silently drop the payout, or refund with a new ID. Server source/sink
operations need explicit engine support and tests, not a fabricated player account.

## Sign identity, ownership and frozen quote

Server-owned tile PDC identifies a sign UUID and revision. Display headers are
never execution authority. Freeze workflow UUID, sign revision, owner, payer,
recipient, exact item identities/counts, stock versions, unit/total price and
policy version at admission. Hash the full length-framed intent. Retrying an
original ID must match that intent; changing a price or payload under it is a
conflict. A new click is a new request, not an implicit retry.

Separate create/use/break/admin permissions, protection cancellations, explicit
shop access and the shared main-hand click budget apply. Recheck mutable shop,
stock and funding state in the reserve transaction. Destroying or changing a
sign closes new admission but cannot erase a previously admitted obligation.

Prices are plain decimal **text**, stored/configured quoted and parsed through
Money. No binary floating point, exponent notation, locale-dependent grouping or
rounding. Reject negative or excessive precision/overflow; compute quantity ×
unit price with checked integer minor-unit arithmetic. Record both quoted unit
price and exact total. Zero-price and item-only workflows still need item holds.

## Stock and strict item identity

For **player shops only**, stock is durable SQL escrow of exact serialized stacks, not a count inferred from
a nearby chest. Stocking is itself a receipt-backed removal workflow. A chest
integration needs a separate ownership/receipt model and is outside the first
implementation. Reserve stock units and funds in the same transaction using
conditional stock-version checks. Never oversell or allow negative stock.

Identity includes material, provider namespace/ID, serialization version, damage,
custom model data, enchantments, names/lore, attributes, potion/container contents,
all persistent metadata and provider-specific signed identity. Compare canonical
count-independent bytes after a digest match; material, display name or a hash
alone is insufficient. Freeze counts and slot plans separately. Preserve the
original signed payload, never recreate a signed item from a visible provider ID.
Use existing item services to validate provider availability and signatures.
Unknown versions/providers fail closed. Repair/rename or other identity changes
require a new quote. Validate provider stack limits and splitting rules, including
items whose quantity is part of their signature. REFUSE the entire plan if any
part does not fit; do not implement partial purchase fulfillment initially.

## One logical transaction across item and money steps

Use stable RESERVE/CAPTURE/RELEASE operation IDs and versioned workflows. Keep
inventory admission locked from saved baseline through native receipt proof and
SQL acknowledgement. File reads happen outside SQLite's writer lock. Under the
write lock, recheck HELD state/version, intent, owner/token and stock versions.
All connection-taking workflow/stock operations join that same transaction;
they never borrow another connection or commit independently. A lost callback or
acknowledgement never authorizes a new ID, second item effect or compensating
withdraw/refund. Receipt cleanup requires immutable SQL acknowledgement.

This is recoverable exactly-once processing across SQL and native playerdata,
not a claim that two files become one hardware-atomic write. Preserve E5's
process-halt versus OS/power-loss and coordinated-backup limitations.

### Player buy: SQL stock to buyer inventory

1. Reserve buyer funds and stock together, inserting PENDING delivery intent and
   frozen baseline evidence in one commit.
2. Apply the complete inventory plan once, save a native delivered receipt with
   token/item digest/counts, then verify the complete saved snapshot.
3. CAPTURE debits held buyer funds, credits the seller, consumes reserved stock
   and records delivery acknowledgement in one balanced SQL transaction.
4. Clear only the matching receipt after acknowledgement. Recovery with a valid
   receipt finishes capture without delivering again; valid saved absence releases
   both stock and money. Ambiguous evidence retains both and quarantines.

### Player sell: seller inventory to SQL stock and seller money

1. Reserve the shop's explicitly funded buyer budget and stock capacity, saving
   the exact offered item/slot intent and seller baseline.
2. Remove the frozen seller items once; save and verify their native removal
   receipt. An unexpected partial removal persists an unknown marker and hold.
3. CAPTURE debits shop-held funds, credits the seller and inserts the exact items
   into escrowed stock in the same commit. Valid absence releases the hold.
4. A removal receipt is not authority to return goods automatically. Reconcile
   its original workflow and money acknowledgement before any cleanup.

### Later trade phase: offered A to outgoing B, optionally with money

First escrow A using a saved removal receipt. Its confirmed receipt activates a
durable exchange entitlement. One SQL commit consumes incoming escrow, reserves
outgoing stock and captures any money component. Deliver B using a separate stable
stage/token, native receipt and acknowledgement. The outstanding obligation to
deliver B persists until complete; it cannot charge or remove A again. Inventory
admission remains fenced during recovery. No player/owner can withdraw stock
pledged to the exchange. This is a recoverable multi-phase exchange, not an
instantaneous cross-file inventory swap.

## Player stock withdrawal (later player-shop phase)

Only the UUID owner or an explicitly audited stock administrator can withdraw.
Freeze withdrawal UUID, shop/stock version, exact serialized identity/counts,
recipient UUID and native inventory baseline. Reserve only **unpledged** stock
units in the same transaction as the PENDING withdrawal intent. Units reserved
for buyers, trade obligations or another withdrawal cannot be taken. Withdrawals
never create a money charge or illegal zero-amount monetary hold.

Plan the entire delivery and refuse without mutation if it will not fit. Apply
once under inventory admission, save a versioned native withdrawal receipt and
verify it outside the writer lock. Under a stock/version/token recheck, atomically
consume the reserved stock and write the immutable withdrawal acknowledgement.
Saved receipt recovery finishes that transition without delivering again; proven
absence cancels the intent and unlocks stock. Missing/corrupt/partial evidence
retains the stock reservation and quarantines. Lost SQL acknowledgement replays
only the original withdrawal ID. Deleting a shop/sign, transferring ownership or
restarting does not erase a withdrawal obligation or change its frozen recipient.

## Sign identity under griefing and concurrent edits

A block binding is `(world UUID, x, y, z, placement epoch)` with one active shop
UUID/owner. Front/back quotes may share that shop, but two different shops cannot
claim the same block: enforce a unique binding transactionally, with concurrent
creation yielding one winner. Replacing the sign creates a new epoch and cannot
adopt old workflows merely because its coordinates match. Preserve S21's existing
PDC format; shop metadata needs a separately versioned identity rather than
reinterpreting service-sign bytes.

Breaking, exploding or replacing a sign with open workflows disables its new
admissions but leaves those workflows, receipts, reservations and journal rows
intact. Old callbacks must not clear or mutate the replacement's identity. Text
or price changed before admission requires a fresh validated quote revision.
After admission, execution/recovery uses only the frozen original intent and
price; current sign text cannot change an existing purchase or manufacture a retry.

## Crash and failure decisions

| Boundary or failure | Required recovery |
| --- | --- |
| Before reserve commit | No admitted obligation/effects; a new request is permitted. |
| Reserve acknowledgement lost | Reconcile original ID; never reserve twice. |
| HELD before item save | Only a complete valid absence snapshot permits release. |
| Partial mutation or save not confirmed | Persist unknown marker, retain holds/stock and quarantine. |
| Native receipt saved before capture | Verify it and finish original capture once, without repeating items. |
| Capture committed, acknowledgement lost | Replay immutable acknowledgement; no new debit, credit or delivery. |
| Receipt clearing interrupted | Clear only the receipt covered by the immutable acknowledgement. |
| Trade incoming accepted, outgoing not saved | Retain outgoing obligation/stock and resume its stage once; no second incoming removal or charge. |
| Missing/corrupt NBT, wrong token or conflicting digest | Retain evidence and holds; absence is not proven. |
| Provider unavailable or serialization changed | No replacement/substitute item; retain escrow for verified recovery. |
| Database unavailable, disk full or commit unknown | Bounded original-ID reconciliation; never refund on timeout alone. |
| Sign unloaded, changed or destroyed | Stop new admission; finish admitted intent from durable workflow. |
| Seller cap conflict | Keep native evidence and escrow; no silent loss, unjournalled credit or split retry. |
| OS/power fault or restored backup | Coordinated restore/reconciliation; no cross-file atomicity guarantee. |

Log workflow/sign revisions, actor UUIDs, item digest and serialization version,
exact counts/prices, stock versions, money operation IDs, delivery acknowledgements,
timestamps, refusal codes and operator decisions/reasons. Retain journal, workflow,
native markers and audited resolution evidence. Do not expose private inventory
payloads in public chat. Operator capture/release requires explicit evidence and
reason and never silently deletes markers or reissues goods.

## Tests required before implementation can ship

- Exact decimal parsing, precision/overflow, checked totals, balance caps and
  zero-price/item-only workflows; no doubles or implicit rounding.
- Strict identity: damaged, renamed, enchanted, container/NBT, provider and signed
  items; valid/invalid signatures, stack limits, split/merge restrictions and
  serialization-version changes.
- Finite funds/stock contention, simultaneous buy/sell/opposing trades, ordered
  locks, stale versions/quotes, no overselling, balanced ledger entries and
  R/stock/escrow invariants.
- Server-shop infinite supply/sink/source/cap/limit cases, with no item escrow
  or player-owner debit; player shops and trades gated separately.
- Stock withdrawal: reserved-unit exclusion, owner/admin authorization, full
  inventory refusal, partial save, before/after acknowledgement crashes, replay,
  competing buyers/withdrawals, ownership transfer and sign removal.
- Sign griefing: break/replace with open workflows, simultaneous two shops on
  one block, front/back conflicting owners, stale placement epochs, and text or
  price changed both before and after admission; no adopted or lost obligations.
- Every crash boundary above, before/after item mutation/save and SQL commit;
  lost acknowledgements, original-ID replay after restart and interrupted cleanup.
- Full inventories, refused/partial plans, unknown markers, missing/truncated/
  corrupt snapshots, mismatched tokens/digests and unavailable providers.
- Real clients on Spigot 1.20.1 and Paper 26.3: permissions, protection, main/offhand,
  click spam, waxed/front/back/hanging signs, chunk unload/reload, restart without
  rejoining, explosion/piston destruction and disabled commands/modules.
- Concurrent ordinary paid mail/kit claims, exactly one charge/delivery/stock
  transition after retries, and realistic latency on the agreed host with FULL.
- Audited operator decisions preserving evidence, explicit funding authority,
  coordinated backup/restore drills and clear process-halt/power-fault limits.

## Required extensions and boundary conditions

E5's current CAPTURE debits one payer into a sink; it does **not** already credit a
shop owner or atomically mutate stock. A shop implementation needs an explicit
journalled capture-to-recipient transition, ordered buyer/seller account locks
(UUID order), immutable shop/stock acknowledgements and versioned schema
migrations. The money entries, held-fund changes, stock transitions and workflow
acknowledgement must commit on the same connection. Seller balance caps and exact
money egress are rechecked before commit. A cap conflict after native item effects
retains escrow/evidence for recovery; it cannot silently drop goods, mint a refund
or retry with another ID. Taxes or additional recipients require balanced entries
and a defined lock order as part of that same transaction, not later callbacks.

Add versioned shop receipt variants carrying workflow/token, stage, exact identity
digest and counts; K/S receipts do not acquire new meanings. Zero-price purchases
and item-only trades still require durable item escrow, stock reservations and
immutable acknowledgements. They must not create illegal zero-amount money holds
or bypass inventory recovery. A trade stopped after incoming capture but before
outgoing save retains its delivery obligation and reserved outgoing stock. Resume
that outgoing stage under inventory admission locks without removing the incoming
goods or debiting money again. Absence of the outgoing receipt is not proof that
the initial exchange never happened.

Some provider/signed items cannot be split or merged without invalidating their
seal or identity. Respect those provider rules; reject the quote rather than
manufacturing a re-signed substitute. Cross-version serialization changes require
an explicit verified migration, never hash-normalization guesses during recovery.

Shop ownership also requires explicit funding authority: creating/editing a sign
or possessing its create permission never grants permission to debit another
account. Sell/buyback quotes spend only the owner's explicitly allocated budget;
trade quotes can pledge only stock the owner controls. Revoking a quote stops new
admission while preserving already admitted holds and obligations. Staff overrides
need a separate audited authority and reason.
