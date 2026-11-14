# S14 economy design — DESIGN ONLY

**No production economy engine is implemented.** S14 supplies this design, a small JDBC/money
prototype under [tests/design/economy](../tests/design/economy/README.md), and a safety switch
for the existing scaffold. SQL prototype measurements do not validate Allium or Vault latency.
The replacement provider remains unreleased until the correctness and server-workload gates pass.
**The user must choose the durability option; S14 selects no replacement engine or new default.**

## Scope and source evidence

The scaffold analysis uses frozen recode baseline **`f3c4736a72ebb87229b18ea0f688cced2d326b6f`**.
Line numbers below refer to that baseline, before the registration safety change. External sources
are inspected local reference files, not evidence of a running server's schema or data.

| Source | Design consequence |
| --- | --- |
| [EconomyModule.java](../src/main/java/codes/castled/allium/module/EconomyModule.java), baseline lines 31–38, 50–71, 153–187 | Reads create defaults; withdrawal has a separate precheck; Vault ignores write results. Registration is not evidence of transaction safety. |
| [EconomyTable.java](../src/main/java/codes/castled/allium/database/EconomyTable.java), lines 22–98 | `allium_balances(uuid, balance DECIMAL(40,2))`, cascading player FK, missing/error conflation, read-modify-write and replacement/upsert must be retired. |
| [DatabaseManager.java](../src/main/java/codes/castled/allium/database/DatabaseManager.java), lines 38–114 | SQLite WAL attempt, one pooled connection, 10-second busy timeout and MySQL reconnect configuration need explicit validation/replacement. |
| [PreferenceRepository.java](../allium-core/src/main/java/codes/castled/allium/preference/PreferenceRepository.java), lines 33–90; [PreferencesModule.java](../src/main/java/codes/castled/allium/module/PreferencesModule.java), lines 133–217 | Nullable `accept_payments`, defaults and local pending state must preserve missing-versus-unavailable semantics. |
| [IgnoreRepository.java](../allium-core/src/main/java/codes/castled/allium/messaging/IgnoreRepository.java), lines 18–69; [IgnoreService.java](../src/main/java/codes/castled/allium/messaging/IgnoreService.java), lines 26–159 | Direction is `(owner, ignored)`; current independent connections/local overlays do not establish policy/debit ordering. |
| [PlayerNameService.java](../src/main/java/codes/castled/allium/naming/PlayerNameService.java), lines 24–122; [PlayerNameRepository.java](../allium-core/src/main/java/codes/castled/allium/naming/PlayerNameRepository.java), lines 26–63 | Completion is bounded; execution must resolve authoritative UUID identity without a network lookup. |
| [MailService.java](../src/main/java/codes/castled/allium/mail/MailService.java), lines 92–285, 859; [MailRepository.java](../allium-core/src/main/java/codes/castled/allium/mail/MailRepository.java), lines 26, 170–251; [mail.md](mail.md) | `CostPolicy` is a pure veto. Paid workflows need connection-aware commits plus existing playerdata receipt recovery. |
| [SchemaMigrator.java](../allium-core/src/main/java/codes/castled/allium/storage/SchemaMigrator.java), lines 34–60; [roadmap.md](roadmap.md), lines 408–442, 670 | Append-only migrations, separate MySQL DDL, on=accept PAY semantics and future atomic money/kit work remain requirements. |

Import references are `/mnt/storage/devops/Essentials/Essentials/src/main/java/com/earth2me/essentials/`:
`UserData.java:42–54,94–138,722–728`, `config/holders/UserConfigHolder.java:24–34,264–311`,
`config/EssentialsConfiguration.java:55–65`, `config/serializers/BigDecimalTypeSerializer.java:22–52`,
`config/EssentialsUserConfiguration.java:35–58`, and `commands/Commandpay.java:52–69` /
`Commandpaytoggle.java:30–39`; also `Essentials/src/main/resources/config.yml:875–908`.
Legacy Allium references are `/mnt/storage/repos/Allium/src/main/java/codes/castled/allium/`:
`managers/DB/Database.java:91–108,312–327,502–508,2122–2161`,
`managers/economy/EconomyManager.java:41–60,223–245`, and `src/main/resources/config.yml:569–589`
(relative to that checkout). These show live getters that default/clamp/create rows and a
sequential transfer/refund; import must read source records directly. Serialization/schema
fixtures against the supported source versions are still required before import implementation.

The only runtime change in this revision is the temporary scaffold registration guard:

```yaml
economy:
  scaffold-vault-provider-enabled: false
```

An absent/false key never registers the scaffold provider. Explicit true **and** a present Vault
plugin are required, with the flag checked before any Vault API linkage/static access. The
existing optional soft dependency remains. Opt-in emits an unsafe-scaffold warning and warns
when an economy provider is already registered; it does not repair money semantics. Registration
tests cover default/false, missing Vault, explicit opt-in and existing-provider diagnostics.
The scaffold table still exists; this flag is a temporary safety state whose release condition is
that the replacement economy engine and gated Vault adapter replace the scaffold entirely.

## Phase 1: one process owns the economy

The first implementation supports **one Allium process as the sole money/policy writer**, with
SQLite or a dedicated MySQL database. Multiple independent servers sharing money are Phase 2.
Use a bounded process coordinator to serialize money, payment-policy changes and configuration
changes. All relevant writers, including preference/ignore administration and imports, must join
it; the existing unrelated async repository calls cannot bypass it. Pure reads use authoritative
committed storage. Persistent settings epochs and policy mutex rows are **not Phase 1 features**.

Freeze effective money/payment configuration while requests execute. Reload closes admission,
drains/reconciles accepted work, validates all balances/holds against new limits, then installs
one new local configuration. Reject reloads that strand balances/holds. Audit its configuration
fingerprint without pretending it is a shared-database epoch. Local pending refusals block new
PAY admission immediately; unresolved acceptance cannot override a stored refusal. Process
ordering and transaction reads together determine whether a payment precedes a policy change.

## Money and account contract

Store immutable **signed `long` minor units**, scale 2 (`1.00 = 100`), SQL signed BIGINT / SQLite
INTEGER. Never store balances as REAL/DOUBLE; SQLite `DECIMAL(40,2)` affinity is insufficient.
Hard absolute balance/request bound: **1,000,000,000,000,000 minor units**, or
`10,000,000,000,000.00`. Use checked arithmetic, including guard thresholds and held totals;
admin differences may reach twice that bound. Use BigInteger for whole-import totals.

Commands accept an ASCII decimal token `[0-9]+(\.[0-9]+)?`, at most 64 characters; absolute admin
set may additionally start with `-`. Reject exponents, grouping, currency symbols and embedded
whitespace. Parse BigDecimal without a rounding MathContext; use
`setScale(2, UNNECESSARY).movePointRight(2).longValueExact()`, then bounds. `1.2300` is valid;
`1.001` is not. Core BigDecimal/config/import amounts remain exact: **no epsilon snapping**.
Read config as decimal text, never through `getDouble`. Formatting is presentation only, fixed
two decimals with explicit locale/symbol rules; it cannot feed money back into persistence.

Proposed replacement settings (design only, separate from the existing scaffold switch):

```yaml
economy:
  starting-balance: '100.00'
  min-money: '0.00'
  max-money: '10000000000000.00'
  minimum-pay-amount: '0.01'
  offline-pay-enabled: true
  pay-excludes-ignore-list: true
```

Require `-HARD <= min <= 0 <= max <= HARD`, `min < max`, `min <= starting <= max`, and
`0.01 <= minimum-pay-amount <= max`. PAY, Vault withdrawal and cost reserve use
`floor = max(0, min)`: configured debt allows audited admin/import debt, not implicit loans.
Positive mutations require at least one minor unit; zero deposit/withdraw/pay/reserve fails.
`has(0)` is a read; absolute set may set zero or permitted debt. Never clamp or partially credit.
For book balance B and held total R, enforce `min <= B <= max`, `R >= 0`, and
`R <= max(0, B-min)`; available is `B-R`. Admin set preserves holds. Credit caps use B.

Accounts belong to UUIDs independently of names/presence and have **no player/name-cache FK**.
Join/explicit opening inserts the account, one OPEN grant and its receipt/entry atomically;
repeated opening never resets or grants again. Imports open at the imported amount without a
starting grant. Deposits/withdrawals/PAY require existing accounts. Reads never create accounts.
Missing, unavailable and found are different results; confirmed missing maps to Vault 0.00/false,
while unavailable must not fabricate an empty balance or a starting grant.

### Vault doubles: narrowly snap binary residue

Only the double adapter allows **distance <= `0.000001` major units from the nearest cent**.
Check finiteness and nonnegative sign first; convert with `BigDecimal.valueOf(double)`, find the
nearest scale-2 HALF_EVEN value, compare absolute decimal distance with that inclusive epsilon,
then convert exactly and apply hard/configured/mutation/minimum rules. This is 0.0001 of a cent,
not permission to round genuine sub-cent prices. Negative tiny values fail before snapping;
near-zero positives may snap to zero and then fail a positive mutation. Signed zero is zero.

Examples/required fixtures:

- `0.1 + 0.2` / `0.30000000000000004` → `0.30`; `1.2300009` and `1.2299991` → `1.23`.
- Inclusive edges `1.230001` / `1.229999` pass; `1.2300011` / `1.2299989` fail.
- `0.001`, `0.005`, `1.001`, NaN, infinities, negative amounts, overflow and out-of-bound inputs fail.
- The same residue supplied as exact core BigDecimal/command text still fails strict conversion.
- Egress is `BigDecimal.valueOf(minor, 2).doubleValue()`; finite output must convert back through
  the same cent-distance check to identical minor units. Signed debt balances retain their sign
  (the nonnegative amount-ingress rule does not reject a balance). Test adjacent cents including
  ±HARD and every response amount/balance; validate conversion failures **before commit**.

`fractionalDigits()` is 2. World overloads use the same global UUID account; banks return
NOT_IMPLEMENTED. `getBalance`/response balances expose available funds. `has` compares validated
integer money with authoritative available money; it reserves nothing. Negative/nonfinite/
excess-precision queries fail closed with diagnostics. [MoneyBoundary.java](../tests/design/economy/MoneyBoundary.java)
is a passing design fixture, not a production adapter.

## Transactions, journal and recovery

One monetary execution uses **one connection, one transaction, one commit** for the operation,
all account changes, entries, receipt, and any workflow/import transition. Connection-taking
repositories must not borrow/commit independently or dispatch more work. No Bukkit calls,
playerdata save, network lookup, external economy call or notification while holding SQL locks.

Phase 1 schema is an append-only `economy` component through SchemaMigrator. Validate actual
columns, types, keys, engines and constraints; refuse future versions. Canonical UUID strings
are lowercase, keys compare as binary/ASCII, timestamps are epoch milliseconds. SQLite money/
version columns require INTEGER storage-class checks. Never use REPLACE or broad INSERT IGNORE.

| Table | Required data/invariants |
| --- | --- |
| `allium_economy_accounts` | UUID PK; exact `balance_minor`, `reserved_minor`, monotonic `version`, creation/update times; hard bounds, nonnegative held total; history cannot cascade-delete. |
| `allium_economy_operations` | Operation UUID PK; UNIQUE `(origin,request_key)`; kind, authenticated actor, participants, exact amount; immutable versioned canonical payload plus SHA-256; state, frozen result/reason and times. Payload includes workflow/import identity, configuration audit fingerprint and any expiry. |
| `allium_economy_entries` | PK `(operation_id,account_uuid)`; unique `(account_uuid,account_version_after)`; book/held deltas, before/after balances and version; RESTRICT FKs to operation/account. |
| Import run/row audit | Run: source/snapshot/options/mapping/manifest identity, status/report/times. Row PK `(source_id,source_record)`: raw value, record hash, UUID, converted value, decision, metadata and unique applied operation ID. |
| Future M3 reservations | Unique `(workflow_kind,workflow_id)`; account, amount, HELD/CAPTURED/RELEASED, version, creation/final operation IDs and times. |

Canonical payload comparison includes bytes/fields as well as hash; same key with a different
payload is IDEMPOTENCY_CONFLICT. Frozen receipts retain original resulting balances/versions;
replay does not substitute today's balance. Types are trusted closed enums: OPEN, PAY,
VAULT_DEPOSIT, VAULT_WITHDRAW, ADMIN_SET, IMPORT_OPEN and future COST_RESERVE/CAPTURE/RELEASE.
Names, op status or a caller-provided permission claim cannot authorize a transaction.

The core API exposes balance/open/deposit/withdraw/set/transfer plus future reservations, with
typed committed receipt, business rejection, unavailable and unknown results. Reads distinguish
Found/Missing/Unavailable. Allium commands use bounded async work and server-thread completion
messages; queue acceptance is not payment success. Disconnect fences stale callbacks but does
not undo accepted/committed money. Authenticated operation IDs survive internal retries.

### Normal execution: insert COMMITTED and effect together

1. Enter the process coordinator. SQLite uses the driver's immediate-transaction mode with
   auto-commit false (`BEGIN IMMEDIATE` before reads); MySQL uses explicit auto-commit false,
   InnoDB, READ COMMITTED and the authoritative primary with reconnect disabled.
2. **Insert the unique operation ID/key first**, provisionally state COMMITTED, inside this
   uncommitted transaction. Other sessions cannot observe that row as a completed operation.
   A specific unique conflict enters reconciliation; other SQL errors do not.
3. Lock accounts by ascending canonical UUID using point lookups (`FOR UPDATE` on MySQL), then
   future reservations/workflows by ascending ID. Opening inserts once; missing normal accounts
   fail. Read authoritative PAY policies on this connection. Apply checked guards and updates,
   insert entries and fill the frozen receipt before commit.
4. Require affected-row counts. Debit d requires `B >= R + floor + d`; update `B := B-d`, bump
   version. Credit requires `B <= max-d`; update `B := B+d`. PAY has two opposite entries and
   both legs in this commit. Admin set checks expected version/bounds/holds and records its exact
   issuance/sink difference; a no-change set can return a no-entry receipt without a version bump.
5. A business rejection rolls back all financial work to a savepoint after key insertion and
   stores REJECTED with no entries in the same transaction. Unexpected SQL/invariant errors roll
   back the whole transaction; never convert them to a successful default or compensating refund.
   Commit returns before success/future completion/event; restore/discard connection state safely.

COMMITTED insertion does **not** require a preceding durable PREPARED commit. An observer sees
all changes or none. No committed DEBITED-only PAY or automatic REFUNDED state exists.
Known deadlock victims with confirmed full rollback may retry the entire operation under the
same ID, at most twice on workers with bounded jitter. A timeout/network exception is not proof
of rollback. Storage unavailable and OUTCOME_UNKNOWN are caller observations, not journal states.

### PREPARED only for durable, keyed asynchronous acceptance

Keep a separate durable PREPARED stage **for keyed async PAY that promises restart replay**:
otherwise a crash after saying “accepted” but before execution could silently lose the payment.
It contains immutable authorized intent and no money/entries. Only acknowledge durable acceptance
after its commit; never say “paid” yet. Workers lock that row and finalize COMMITTED/REJECTED with
all effects in one execution commit. A 30-second PAY expiry can reject only under that lock;
recheck current policy/limits and request validity when executing, with no Phase 1 shared epoch.

Imports normally have a durable approved manifest and stable source-row keys already, so apply
each row with the single-commit path; **no extra PREPARED row is required**. If a future import
queue separately promises durable acceptance/automatic replay, it must persist that intent
before acknowledging it, like PAY. Ordinary synchronous Vault calls get no PREPARED stage and
no background execution after a known failed/cancelled request.

| Durable state | Invariant and recovery |
| --- | --- |
| No visible row | Does not prove rollback while an old transaction may still be alive; resolve via the same unique insertion/SQLite write lock. |
| PREPARED (keyed accepted work only) | No committed effect/entries. Lock before executing/rejecting; an old executor may still be in flight. Do not report ordinary no-effect failure while automatic execution remains possible. |
| COMMITTED | Receipt, exact effects and entries committed together; terminal replay returns original receipt. |
| REJECTED | Stable reason and no effects/entries; terminal replay returns rejection. |

### Lost acknowledgement: the same ID is the recovery fence

Stop using the suspect connection. Preserve ID/payload; do not refund, invent a new ID or report
success. On a fresh connection to the **same primary**, retry the identical unique operation
insertion before touching money. MySQL uniqueness arbitration waits for an old uncommitted
insertion: rollback permits insertion/execution; commit produces the specific duplicate error.
After a duplicate, end the attempted transaction and read/verify the committed matching receipt
and entries. SQLite obtains BEGIN IMMEDIATE first; an active writer must finish before retry
can interpret the insertion result. BUSY/LOCKED/lock timeout means **unresolved**, never duplicate
or committed proof. Missing snapshot reads, replica reads and connection loss alone prove nothing.

For existing PREPARED work, lock/reconcile that row rather than create another intent. Bound
recovery attempts/backoff; unresolved outcomes return OUTCOME_UNKNOWN with ID and fence affected
local paths. Startup reconciles accepted/unknown work and audits invariants before admitting
those accounts. Do not expire dedupe identities without authoritative uniqueness tombstones.

Exactly-once effects apply only to a stable key, not notifications/network delivery. Vault has
no external request key: an internally retried invocation keeps its ID, but a plugin repeating
the same amount is a **new request**. Unknown Vault mutations return FAILURE with
`OUTCOME_UNKNOWN:<id>; do not retry automatically` and hold the adapter unavailable until
reconciled. Integrations needing safe retries must use the keyed API.

**Operator lookup for `OUTCOME_UNKNOWN:<id>`:** When an unkeyed Vault call returns
`OUTCOME_UNKNOWN:<id>`, that operation ID (`<id>`) is the **only** way to verify afterwards
whether the money actually moved. An administrator inspects the operation via
`/allium economy op <id>` (console or `allium.economy.admin`), or queries the database directly
after reconciliation:

```sql
SELECT id, kind, state, reason, amount_minor, created_at, updated_at
FROM allium_economy_operations
WHERE id = '<id>';

SELECT account_uuid, delta_minor, held_delta_minor, balance_before_minor, balance_after_minor, account_version_after
FROM allium_economy_entries
WHERE operation_id = '<id>';
```

- If `state = 'COMMITTED'` (with matching `allium_economy_entries` rows), the money moved once and
  must not be manually re-applied.
- If no row exists (or `state = 'REJECTED'`) after startup/coordinator reconciliation has finished,
  the transaction rolled back and no money moved.

## PAY and policy behavior

PAY is player-to-player, no wildcard/self/console-source balance; admin grants are separate.
Proposed permissions: `allium.pay` / `allium.paytoggle` self-use default true;
`allium.pay.offline` / `allium.paytoggle.others` default false. Offline pay additionally requires
`offline-pay-enabled=true`, a known identity and existing account. Freeze UUIDs at admission;
a later disconnect does not revoke a durably accepted addressed payment. Another server's online
presence is not Phase 1 local presence. No implicit staff/op/messaging bypass or tax is designed.

Use bounded PlayerNameService completion only for suggestions. Execution resolves exact online
identity/visibility on the server thread or authoritative offline identity, without Mojang,
`getOfflinePlayers()` enumeration or fabricated name-derived UUIDs. Resolution errors are
unavailable; equal-name ownership ambiguity fails closed. Retry uses frozen UUIDs, not names.

Read `accept_payments` authoritatively in the money transaction, using
`preferences.defaults.accept-payments` only after a successful NULL/missing read. With
`pay-excludes-ignore-list=true`, reject `(owner=recipient, ignored=sender)`. Missing rows inherit
policy; failed reads, disabled required modules, local LOADING/FAILED acceptance or UNKNOWN ignore
state do not authorize PAY. Refusal/ignore share a generic recipient-not-accepting reply.

All Phase 1 toggle/ignore writers use the same coordinator; pending local refusal fences new
admission until persisted/failed. A previously admitted payment may precede a later toggle.
Acknowledge toggle success after commit. `/paytoggle [on|off] [player]` uses on=accept/off=reject;
bare self-toggle reads/inverts authoritative effective value in that serialized transaction.
Explicit on/off is idempotent. Console must name a target, invalid syntax never falls back to
self, and patches change only ACCEPT_PAYMENTS/version. Trusted shop/Vault payouts and admin
grants are distinct operation types without PAY acceptance/offline checks; they still obey all
money/account/commit rules. Do not relabel ordinary player payments as payouts.

## Three measured durability options — user decision remains open

All options require bounded borrow/lock/busy deadlines and verified per-connection settings;
WAL by itself is not a durability contract. No option is silently selected by this design.

| Option | Contract/caveat to approve |
| --- | --- |
| SQLite WAL + synchronous=FULL | Local embedded database, one bounded writer; per-commit WAL sync is intended to survive OS/power loss subject to filesystem/device sync guarantees. No shared network filesystem. Hardware power-loss behavior was not tested. |
| SQLite WAL + synchronous=NORMAL | Faster measured path with weaker durability: **recent acknowledged transactions may be lost on OS crash or power failure**. Application-process crash and power loss are different claims. Choose only with explicit acceptance of this loss window and coordinated economy/workflow backups/recovery. Connection-close tests do not validate power durability. |
| MySQL InnoDB flush=1 + sync_binlog=1 | Verify `innodb_flush_log_at_trx_commit=1`, `sync_binlog=1` when binlogging, storage guarantees and primary consistency. Network/pool latency remains synchronous cost; replication failover to a stale primary cannot preserve the contract. |

### Frozen prototype and actual results

Reproducible source: [SingleCommitProbe.java](../tests/design/economy/SingleCommitProbe.java),
[MoneyBoundary.java](../tests/design/economy/MoneyBoundary.java) and [run.py](../tests/design/economy/run.py).
The retained [evidence](../tests/design/economy/evidence/single-commit.json) includes the manifest,
raw samples, fault outcomes and environment data. Successful outside run:
`/mnt/storage/.allium-validation/20261113/s14-revision/run-20261114T065519Z/` (UTC run ID;
2026-11-13 local date). Source was copied before javac; manifest SHA-256 values freeze what ran:

- `SingleCommitProbe.java`: `9b15f08e1abfd13d8cfe0ab2bfacd8f030a05111bb4d38ec938d114ad63a47e1`
- `MoneyBoundary.java`: `acd018cb5f5c90221cc0c1b27ecd0e731e0a088c08c7712134a639d0792fc125`
- Raw `results.json`: `0ddf8db6e6d7a2d276641225d24bf58823d30a63b29b14e2ffde8a5a729c9929`

Driver hashes in that manifest are SQLite JDBC 3.49.1.0
`5c8609d2ca341deb8c6f71778974b5ba4995c7d32d7c7c89d9392a3e72c39291`
and Connector/J 9.2.0 `7e9941bbdcca244d878ea95bfff788fd9ba6a65af757f24be6c632930d61c7ed`.
SQLite reports 3.49.1, WAL with synchronous 2 (FULL) / 1 (NORMAL). MySQL reports 8.4.13,
flush=1, sync_binlog=1, log_bin=1 on owned `allium-gate-mysql`, `127.0.0.1:25733`.
Compilation/runtime used `/usr/lib/jvm/java-17-openjdk/bin/`, `--release 17`, `-Xmx128m`;
OpenJDK 17.0.21.0.3+1 on Linux 7.2.14-arch1-1 x86_64, glibc 2.44, i7-12700KF.
Credentials stayed in a private child environment. Allium.jar supplied classpath support only:
no plugin, server or Vault provider was executed. Scratch MySQL database creation/drop is in the
harness; only the owned validation container was used. See retained provenance for actions,
cleanup and environment details; no OS syscall trace or storage-device flush proof is claimed.

Each lane uses two existing hot accounts, a preopened transaction connection plus observer,
20 warmup transfers, 100 single transfers and 60 transfers in 20 bursts of three sequential
operations. Each transfer inserts one COMMITTED row, updates both balances and entries, commits
once and restores auto-commit. The timer includes transaction work/commit, excludes connection
creation and observer verification. Percentiles are nearest rank in **milliseconds** (including
both per-operation figures and the 3-operation burst totals across the 20 bursts):

| Lane | Phase | n | p50 | p95 | p99 | max |
| --- | --- | --- | --- | --- | --- | --- |
| SQLite FULL | Single | 100 | 0.9735 | 1.0713 | 1.4448 | 1.5177 |
| SQLite FULL | Burst, per operation | 60 | 0.9731 | 1.2669 | 2.1977 | 2.1977 |
| SQLite FULL | Burst total (3 operations) | 20 | 2.9009 | 3.3561 | 4.4053 | 4.4053 |
| SQLite NORMAL | Single | 100 | 0.0363 | 0.0541 | 0.0672 | 0.0809 |
| SQLite NORMAL | Burst, per operation | 60 | 0.0498 | 0.1694 | 0.2410 | 0.2410 |
| SQLite NORMAL | Burst total (3 operations) | 20 | 0.2467 | 0.2962 | 0.3682 | 0.3682 |
| MySQL durable | Single | 100 | 13.4628 | 24.1892 | 26.5557 | 36.0993 |
| MySQL durable | Burst, per operation | 60 | 13.3692 | 30.1548 | 44.0402 | 44.0402 |
| MySQL durable | Burst total (3 operations) | 20 | 44.6298 | 60.2650 | 125.2765 | 125.2765 |

MySQL misses the `p99 <= 2 ms` synchronous-Vault target by an order of magnitude (`26.5557 ms`
single, `44.0402 ms` per burst operation, and `125.2765 ms` for a 3-operation burst total), so
synchronous Vault is supported on SQLite only, and on MySQL economy is commands-only or limited.

Small samples do not estimate p99.9 or sustained server load. These are hot-account SQL timings,
not UUID/String Vault calls, pool starvation, mixed policy/mail traffic or server-thread latency.
The old outside `/mnt/storage/.allium-validation/20261113/s14-economy/` two-commit probe is
historical only: schema/SQL/workload changed, so no controlled speedup ratio is claimed.

The new probe passed **2,794 invariant checks, 540 timed samples and six connection-loss cases**
(two per durability lane); money boundary fixtures also passed. Each lane ended with exactly
182 COMMITTED rows, 364 entries and balances 999,818 / 1,000,182 minor units: one effect per ID,
conserved total, matching committed payloads and two opposite entries.

Fault ordering is explicit: old transaction inserts ID/changes money; same-ID recovery starts
and remains blocked for at least the controlled 150 ms hold. MySQL additionally verifies real
`performance_schema.data_lock_waits`; SQLite separately proves a 100 ms busy attempt is
unresolved and **not** duplicate. Then the fault seam runs:

| Lane | Kill before commit | Kill after inner commit |
| --- | --- | --- |
| SQLite FULL | Native connection close rolls back; recovery inserts/applies once; 183.7735 ms | Inner commit, native close, discard return; recovery sees duplicate COMMITTED; 182.3679 ms |
| SQLite NORMAL | Native connection close rolls back; recovery inserts/applies once; 182.5446 ms | Inner commit, native close, discard return; recovery sees duplicate COMMITTED; 183.3342 ms |
| MySQL durable | Administrative KILL CONNECTION ends old session; recovery inserts/applies once; 175.8131 ms | Inner commit, actual KILL CONNECTION, discard return; recovery sees duplicate COMMITTED; 176.4399 ms |

Times cover recovery launch through verification, including the intentional hold/control work;
they are not unknown-outcome service deadlines. All callers receive OUTCOME_UNKNOWN at the
seam. The after-commit branch deliberately throws **after actual JDBC commit and physical
connection kill/close but before caller return**. It exercises ambiguous caller acknowledgement
and uniqueness recovery, **not dropped TCP acknowledgement packets**. SQLite is embedded: no
remote KILL/session/socket exists; native close ends its transaction. No process halt, OS crash,
power failure, real wire-loss proxy, failover or full restart recovery was tested. The SQL
prototype omits the production schema, payload conflict variants, rejection paths and policy/
workflow coordination; those remain gates, not inferred successes.

### Realistic server release gate, still unverified

Benchmark the scaffold and eventual engine/adapter on disposable servers, recording driver/JVM/
server versions, actual storage, pool/deadline settings, MySQL RTT and selected durability mode.
Use about **100 online players**, 10,000 known accounts, warmup then at least ten minutes per
lane with **5 money mutations/sec and 50 reads/sec**. Cover UUID reads/has, String existing/
missing lookups, opening, deposits, guarded withdrawals, PAY and business rejections. Include
bursts of **three writes in a tick**, opposite transfers, a declared hot-account fraction and
normal concurrent preferences/mail work. Measure end-to-end p50/p95/p99/p99.9/max, sample/failure
counts, pool/queue/lock waits and **actual aggregate economy server-thread time per tick**.

Proposed healthy budgets: synchronous UUID reads and single-account writes p99 <= 2 ms,
p99.9 <= 5 ms; no healthy call > 25 ms; aggregate economy main-thread work p99 <= 5 ms/tick for
that supported workload. These are future acceptance targets, not results. The MySQL SQL lane
misses the `p99 <= 2 ms` synchronous-Vault target by an order of magnitude, so synchronous Vault is
supported on SQLite only; neither SQLite lane clears the full server-workload adapter gate yet.
Separately exercise pool starvation, held locks, deadlocks, slow storage/network and connection/
commit faults. Publish tested deadlines/admission/circuit behavior; faulted synchronous calls
must finish within <= 50 ms or declare that deployment unsupported for synchronous availability.
Timeout never permits an unacknowledged late debit to be called a known no-effect failure.

Vault SUCCESS requires an authoritative committed receipt. FAILURE uses amount zero and a
validated balance only if obtained; unavailable/unknown may use a documented zero placeholder,
not an empty-account claim. Primitive unavailable `getBalance` throws a documented unchecked
EconomyUnavailableException; `has`/`hasAccount` fail closed with diagnostics. Replace legacy
Bukkit String offline lookups with a no-network exact resolver; never block on an async callback
that needs the server thread. Registration requires migration/recovery/readiness and a passing
supported workload; failures open its circuit. Test dependent plugin handling explicitly.
If a lane fails, withhold it or review a different architecture/workload; do not weaken atomicity,
return async SUCCESS or claim an unmeasured cache solves durability/coherence.

## Phase 2: explicitly separate multi-server work

Before adding any second writer, implement and test a shared settings row/epoch for money,
offline/ignore policy and the effective nullable payment-acceptance default. Refuse conflicting
backend config; reload validates balances/holds under exclusive settings lock. Add persistent
per-recipient policy mutex rows, including when preference/ignore rows are absent, and make
**every** toggle/ignore/import writer participate. Use shared settings lock → operation → sorted
policy mutexes → sorted accounts → reservations → mail/kit workflow locks; adapt existing
mailbox-first code so it never acquires earlier locks later. Point-lock accounts in order.

Both PAY and policy changes must enforce SQL ordering across processes, plus conservative local
pending-refusal fences. Define epoch handling for accepted intents without stranding reservations.
Measure two independent clients/backends, opposing payments, toggle/ignore/default reload races
and primary/failover behavior. Shared SQLite/network files are unsupported. Phase 1 neither
implements nor claims these multi-server guarantees.

## Future M3 mail/kit costs

Keep `MailService.CostPolicy` a **side-effect-free preflight veto**. Do not withdraw/reserve in
that callback or wrap delivery in unrelated Vault calls. A connection-aware reservation service
must use stable workflow keys and the same journal/database:

- Reserve: check `B-R-cost >= floor`, increase R, bump version, insert HELD reservation and
  zero-book/positive-held entry with PENDING workflow intent in one commit.
- Capture: conditional HELD→CAPTURED, decrease B and R, record sink entry and durable workflow
  completion/activation together. Release: conditional HELD→RELEASED, decrease only R and record
  cancellation together. Require one changed row; terminal capture/release compete, never both.
- Text mail may collapse send/capture into one transaction. Gift mail requires saved **and read
  back** native playerdata delivery receipt before capture+PENDING→READY. Proven absence permits
  cancellation/release; unknown evidence keeps hold/gift pending. Money and playerdata are not
  one JDBC transaction. READY gift claims do not charge again.
- Paid kits need a durable delivery ID/receipt plus cooldown/workflow intent. Verify exact
  inventory effects before capture; partial delivery needs an explicit cost/completion policy.
  Until this recovery protocol exists, deny paid kits. An in-memory delivery callback is not proof.

R must equal the sum of HELD reservations. Expiry triggers investigation, not timeout-only
refund. Stable replay cannot double hold/capture/release. Mail/workflow repositories must accept
the money connection. Separate databases/external economies need a new saga/outbox design;
coordinate backups of journal, workflows, playerdata, server identity and signing material.

## Import mappings and audit

Imports are maintenance, never join-time merges. Stop source writers, take a verified consistent
snapshot and disable target writers/provider. No import source database was opened by S14.
H2 must be a separate stopped/consistent copy with compatible driver,
`IFEXISTS=TRUE;ACCESS_MODE_DATA=r`; never create/upgrade/open the live legacy file.

| Source | Exact mapping and exclusions |
| --- | --- |
| Essentials UUID YAML | `userdata/<uuid>.yml` filename→UUID; original root `money` scalar text→exact cents; `accepting-pay`→ACCEPT_PAYMENTS without inversion; `last-account-name`→audit name candidate. Missing money is SKIPPED_MISSING_MONEY, not zero/grant. Missing acceptance preserves NULL/existing override; explicit malformed/null/nonboolean fields fail. Exclude `npc: true`; name/alternate offline files need a reviewed UUID map. |
| Legacy Allium H2 | `database/sfcore.mv.db`, JDBC base `database/sfcore`; `SELECT uuid,balance,last_updated FROM player_balances ORDER BY uuid`. Inspected schema: UUID VARCHAR(36) PK, balance DECIMAL(20,2) NOT NULL DEFAULT 0.00, last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP. Use getBigDecimal; timestamp is source metadata. Optional LEFT JOIN player_data on UUID for name; missing name never drops money. No inspected payment-preference column: do not invent paytoggle values. |
| Recode scaffold | `SELECT uuid,balance FROM allium_balances ORDER BY uuid`; MySQL DECIMAL(40,2)→exact cents. SQLite inspect raw storage class/value: REAL is LOSSY_SOURCE requiring explicit review, never exact recovery. INTEGER/TEXT must parse exactly; null/corrupt/out-of-bounds fail. Missing player row never discards money. |

Use a safe bounded YAML parser rejecting duplicate keys; preserve original numeric text without
binary floats. Bounded quoted scientific decimals may use exact BigDecimal during import,
unlike command syntax. Reject nonnumeric/NaN/infinity; never use a source live getter's defaults,
DECIMAL128 rounding or clamps. Verify actual serialized field names with supported-version fixtures.

Settings import is separate and previewed. Essentials starting/min/max/minimum-pay/ignore policy
are candidates; its `minimum-pay-amount=0.001` is rejected until the operator chooses an exact
cent value. Nonzero tax/NPC accounts are unsupported. Legacy decimal-places must be 2; an
allow-negative flag alone needs an explicit numeric minimum. Currency settings affect display
only. Neither source's starting balance is added to imported balances.

Dry-run produces a sorted manifest: stable operator `source_id`, record identity (SQL UUID or
relative YAML path), snapshot/schema/version, raw bytes/exact SQL values, record hash, UUID,
original/converted amount, metadata, mapping/options and rejection decision. Precision/bounds
policy is REJECT. Any separately approved cleanup needs per-row original/replacement/difference,
reason and author; include exact original/target totals. Duplicate UUIDs, conflicting sources or
UUID maps are conflicts, not additive merges. Copying a file does not create a new identity.

Apply only the identical approved manifest/options under maintenance admission. Use stable
IMPORT_OPEN keys from `(source_id,source_record,mapping_version)`; atomically open the **absent**
account at its exact amount, insert OPEN entry/receipt, patch approved unset preferences and mark
that import row APPLIED. Existing accounts, even zero/default, are conflicts. Names default to
audit-only; optional approved seeding includes provenance/last-seen (unknown=0) and cannot replace
a newer/ambiguous directory owner. Use UUID administration until a valid name entry exists.

Identical rerun returns the APPLIED receipt without any balance/default/preference rewrite.
Changed source hash/mapping/options under the same identity is CONFLICT_SOURCE_CHANGED, never
bypassed with a new run ID. Reconcile uncertain commits by stable operation/import-row keys before
continuing. SQL failures cannot become successful zeros. Keep PARTIAL until conflicts/exclusions
are resolved; COMPLETE requires matching row counts, exact totals and entry reconciliation.
Audit retains configuration fingerprint, source identity/raw value, UUID, resulting cents,
operation/timestamps/overrides. Resume by keys, not a “last processed row” cursor alone.

## Follow-up acceptance

Implement later: exact Money/settings/schema/typed results; Phase 1 transactions/recovery;
coordinated PAY/policy; import rehearsal; measured Vault; independently reviewed M3 reservations;
then separately Phase 2. Retire the scaffold writer before cutover; never wire new PAY to it.
Migration keeps old balances read-only, validates original values/counts/BigInteger totals and
separates DDL from monetary commits. SQLite backup includes WAL through online backup or a
stopped/checkpointed database. Provider stays unavailable on invalid schema/config/recovery.

Required verification extends beyond this probe: finite/exact/snap boundaries and cap-adjacent
round trips; many debits against finite funds, opposing/hot transfers, recipient failure rollback,
missing/open races, same-key different-payload conflict, rejection and PREPARED restart/expiry;
offline/null/default/pending policy ordering; queue cancellation/disconnected callbacks; holds and
capture/release competition; mail/kit receipts; import schema fixtures, missing money, duplicate/
changed source and partial/rerun audit; coordinated restore and real server-workload latency.
Run both actual dialects, not H2 emulation. No full engine, Vault, network/power-fault or release
benchmark success is claimed by S14.
