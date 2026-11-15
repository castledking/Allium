# E5 paid workflow reservations

Paid kit and mail costs use Allium's asynchronous SQLite engine directly, never Vault withdraw/refund calls. SF remains one server, one `allium.db`, WAL `synchronous=FULL`. Vault release and the proposed latency budget remain unapproved. The experimental dedicated reader is not adopted.

Mail prices are exact quoted decimal text, free by default:

```yaml
mail:
  costs:
    text: '0.00'
    gift: '0.00'
```

Kit definitions retain their quoted `cost`. A paid kit is a self claim by the target/payer. Paid grants to another player, including console grants, remain denied: staff cannot spend the target's funds through `/kit`. Existing free grants retain their permissions/cooldown rules. Console text mail has no payer and remains free. Permission/rate/ignore/toggle/mute checks and the pure preflight `CostPolicy` are preserved. Prices and UUIDs are frozen for an admitted workflow; later price changes do not alter an existing hold or capture.

## One commit per step

Economy schema migration 2 adds reservations keyed by `(KIT|MAIL, workflow UUID)`, with payer, exact amount, framed intent hash, HELD/CAPTURED/RELEASED state, version and original/final journal IDs. Original migration 1 and legacy balances are retained. Full mail/kit intents are hashed with length framing so a changed payload under an existing workflow key conflicts even at the same price.

- Reserve: under `BEGIN IMMEDIATE`, read the payer account, enforce available funds, increase R, write a zero-book/positive-held entry, insert HELD and insert the PENDING mail/kit delivery intent in the same commit.
- Capture: require HELD, reduce B and R together, record the sink/held entries, and save workflow completion/acknowledgement in the same commit. Native receipt verification precedes the write transaction while item admission remains locked; HELD state and workflow ownership are rechecked under the write lock.
- Release: require HELD and a validated native snapshot proving absence of that receipt. Reduce only R and cancel the workflow in the same commit. Missing, corrupt or ambiguous playerdata is unknown evidence; retain the hold and pending workflow.
- Text mail: SPEND collapses send/capture, READY row and CAPTURED reservation into one commit, without a visible hold or native item receipt.

All steps reuse deterministic operation IDs derived from workflow kind, ID and step. Terminal replay returns the frozen receipt without rerunning inventory/SQL effects. Commit uncertainty keeps its original ID; no compensating debit/refund or new-ID retry is invented. Paid text reports an unknown outcome ID and tells the sender not to resend until checked. A newly typed command is a new purchase, not a retry of an existing workflow.

SQLite owns the writer lock first, followed by the payer account (one account per cost), reservation and existing kit/mail locks; mailbox UUIDs are ordered. Connection-taking repository operations never borrow another pooled connection or commit independently. Settings validation protects existing holds. R equals the sum of HELD reservations; startup and monetary transitions validate it. Admin set, `/pay` and Vault mutations cannot spend reserved funds. Terminal markers remain after completion so disabling the economy cannot activate a paid pending workflow for free.

## Native item receipts and recovery

A paid item request first saves a valid inventory baseline. Kits reserve with their delivery ID/token and original cooldown intent. A complete inventory plan is applied, checked against the exact result, then the K receipt is saved in native playerdata and read back. Capture records charge, kit usage/cooldown and immutable claim acknowledgement together. Receipt recovery never gives the kit again.

Kit overflow uses **REFUSE the entire kit**: if any item does not fit, nothing is delivered, the hold is released only after saved absence proof, and no usage/cooldown is consumed. If an unexpected partial mutation occurs, a preinstalled `P/<deliveryId>` unknown receipt is persisted with the inventory. The player is quarantined; keep the HELD reservation and delivery row, with no automatic capture or refund. The marker survives reconnect. Operator reconciliation must preserve the inventory, marker, workflow and money audit; deleting a marker or hold is not a repair procedure.

Gift mail reserves with PENDING, takes the sender's exact items, saves/reads back the S receipt, then captures with the PENDING→READY acknowledgement. Unknown partial removal also retains an unknown marker and hold. Proven receipt absence cancels PENDING and releases the original hold; a timeout alone never refunds it. Recipient claims, including partial recipient claims and retries, do not charge either player again.

After an interrupted capture, a saved K/S receipt finishes the original capture once. An already committed acknowledgement clears the leftover receipt without another charge. An absent receipt is accepted only from a complete valid native NBT/GZIP snapshot. Missing evidence retains the hold; it cannot be treated as an empty receipt or zero balance. Free workflows retain their existing behavior; paid workflows fail closed if the economy is disabled or unavailable.

The proof validates native save/readback and process-crash recovery. It does not establish atomicity between SQLite and playerdata under hardware power loss, nor replace coordinated backups/restores. No such power-fault guarantee is claimed by the live Runtime.halt tests.

[Live/unit results](../tests/live/economy/E5.md) record the frozen artifact, sufficient/insufficient funds, finite-balance races, retry, persistence, and real before/after-capture and pre-delivery halts on Spigot and Paper. Signs remain S21.

## Operator inspection and explicit money resolution

The Vault provider is disabled by default (`economy.vault-provider-enabled: false`).
Commands and paid workflows do not need it. Enabling it still requires successful
schema validation and startup recovery readiness.

`/eco reservations [player]` lists the latest 100 reservations, including payer,
workflow kind/UUID, amount and state. Console or `allium.eco.admin` is required.
Before deciding a stuck hold, stop that player's activity and disconnect them;
preserve the native playerdata file and a coordinated SQLite backup, including WAL.
Inspect the inventory, complete K/S receipt, unknown P marker, workflow intent,
reservation and journal. Missing or corrupt evidence is not proof of absence.

Use exactly one explicit decision with a case reference and meaningful reason:

```
eco resolve KIT <workflow-uuid> capture confirmed kit delivery; incident 42
eco resolve MAIL <workflow-uuid> release verified sender inventory restored; incident 43
```

Capture consumes the held money; release makes it available without changing book
balance. The decision, actor, action, reason and time enter
`allium_economy_reservation_audit` in the same transaction as the original keyed
terminal operation. The HELD state is checked again under the writer lock. A
terminal reservation cannot be reversed with another resolve command. Inspection
and resolution never delete the native marker, workflow intent, reservation,
journal or audit. They never deliver/remove items or clear a quarantine. Operator
resolution intentionally freezes automatic completion of that workflow: preserve
the evidence for inventory reconciliation. This command settles money only; it is
not an automatic inventory repair or a command to make an unknown partial effect
safe. Do not delete a P marker to bypass quarantine.

## Offline startup sweep and write-lock scope

Startup materializes HELD workflow identities, closes the query connection, then
reads each offline payer's complete native NBT/GZIP snapshot. A matching kit token
and name or sender S receipt completes the original capture and workflow; valid
absence releases it. Unknown entries, wrong tokens, missing/corrupt snapshots and
missing/conflicting workflow intents leave the hold untouched and log its ID.
Online players use their existing inventory-locked recovery path. The sweep does
not mutate playerdata or deliver items, and terminal rows are skipped on restart.

Before this follow-up, paid reserve/capture/release callbacks read native files
inside `BEGIN IMMEDIATE`. They now verify first, keeping inventory admission
locked through the subsequent SQL commit. The engine rechecks the current HELD
state and version inside that transaction. Workflow ownership and acknowledgements
are still checked on the same SQL connection. File I/O no longer extends SQLite's
write lock; no weaker durability setting or separate money database is involved.
