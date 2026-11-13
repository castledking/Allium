# S14 economy design — Status: DESIGN ONLY

**Status: DESIGN ONLY — runtime behavior is not implemented.**

Baseline: **`f3c4736`**, full commit `f3c4736a72ebb87229b18ea0f688cced2d326b6f`.
This document specifies a replacement for the scaffold; it does not make that scaffold safe.
S14 is the reviewed design following S13; it adds no production economy implementation.
A disposable Java 17 JDBC probe outside Git supplied the measurements below. It used only
validation databases, dropped its MySQL database and restored the validation container to
stopped. Status is **JDBC PROTOTYPE MEASURED; VAULT DESIGN UNVERIFIED**.
The measured SQL timings inform the synchronous design; no Allium/Vault method was benchmarked.
Release remains denied until the full measurement gate below passes. Numbers labelled as
budgets are acceptance criteria; the separate measured tables are actual probe results.

## Source evidence and boundaries

Recode paths below are relative to this repository and line numbers refer to the baseline above.
External paths refer to the inspected local reference checkouts, not to a live server or proof
that its data matches those schemas.

| Primary source | Relevant contract/evidence |
| --- | --- |
| [`EconomyModule.java:31`](../src/main/java/codes/castled/allium/module/EconomyModule.java#L31), `:50`, `:63`, `:153`, `:181`, `:219` | Registers Vault after best-effort table initialization; reads create a default; withdrawal precheck; Vault ignores write results; account creation is separate. |
| [`EconomyTable.java:22`](../src/main/java/codes/castled/allium/database/EconomyTable.java#L22), `:36`, `:51`, `:71`, `:79`, `:96` | `allium_balances(uuid, balance DECIMAL(40,2))`, cascading player FK, error/missing conflation, replacement/upsert and read-modify-write operations. |
| [`DatabaseManager.java:38`](../src/main/java/codes/castled/allium/database/DatabaseManager.java#L38), `:48`, `:74`, `:84`, `:114` | SQLite WAL attempt, one pooled SQLite connection and 10-second busy timeout; MySQL pool and reconnect URL; player table; reusable `ConnectionSource`. |
| [`PreferenceRepository.java:33`](../allium-core/src/main/java/codes/castled/allium/preference/PreferenceRepository.java#L33), `:69`, `:90`; [`StoredPreferences.java:68`](../allium-core/src/main/java/codes/castled/allium/preference/StoredPreferences.java#L68) | Nullable `accept_payments`, field patches and versions; absent/unset differs from read failure; fallback only after a successful authoritative read. |
| [`PreferenceSessions.java:17`](../allium-core/src/main/java/codes/castled/allium/preference/PreferenceSessions.java#L17), `:38`; [`PreferencesModule.java:133`](../src/main/java/codes/castled/allium/module/PreferencesModule.java#L133), `:217` | Pending local patches; READY/LOADING/FAILED and ACCEPTS/REFUSES/UNAVAILABLE; configured acceptance default. |
| [`roadmap.md:408`](roadmap.md#L408), `:426`, `:442`, `:670` | Paytoggle on=accept; offline and cross-server authoritative acceptance/ignore enforcement; legitimate Vault/shop payouts are separate; M3 atomic money/kit scope. |
| [`IgnoreRepository.java:18`](../allium-core/src/main/java/codes/castled/allium/messaging/IgnoreRepository.java#L18), `:24`, `:42`, `:57`, `:69` | Directional `allium_ignores(owner, ignored)` storage; current writes use their own connections. |
| [`IgnoreService.java:26`](../src/main/java/codes/castled/allium/messaging/IgnoreService.java#L26), `:99`, `:126`, `:159` | Unloaded state is UNKNOWN; local mutations overlay asynchronous writes and undo known failures. |
| [`PlayerNameService.java:24`](../src/main/java/codes/castled/allium/naming/PlayerNameService.java#L24), `:80`, `:122`; [`PlayerNameRepository.java:26`](../allium-core/src/main/java/codes/castled/allium/naming/PlayerNameRepository.java#L26), `:63` | Bounded completion only; exact online or authoritative offline execution resolution; current-name index is not permission or payment acceptance. |
| [`MailService.java:92`](../src/main/java/codes/castled/allium/mail/MailService.java#L92), `:209`, `:285`, `:859`; [`mail.md:22`](mail.md#L22), `:39` | Reserved `CostPolicy` is side-effect-free; mail receipt/escrow recovery already exists; authoritative policy plus pending local refusals. |
| [`MailRepository.java:26`](../allium-core/src/main/java/codes/castled/allium/mail/MailRepository.java#L26), `:170`, `:240`, `:251` | Existing send transaction/mailbox lock order and separate activation/cancellation entry points require connection-aware integration for paid workflows. |
| [`SchemaMigrator.java:34`](../allium-core/src/main/java/codes/castled/allium/storage/SchemaMigrator.java#L34), `:60` | Append-only component migrations and explicit recognition that MySQL DDL can commit separately. |

The reference sources used for import decisions are:

- `/mnt/storage/devops/Essentials/Essentials/src/main/java/com/earth2me/essentials/UserData.java:42–54,94–138,722–728`:
  `userdata/<uuid>.yml`, money fallback/clamping in the live API, and accepting-pay semantics.
- In that same Essentials source tree, `config/holders/UserConfigHolder.java:24–34,264–311`:
  `money`, `npc`, `lastAccountName`, `acceptingPay=true`; `config/EssentialsConfiguration.java:55–65`
  uses Configurate object mapping; `config/serializers/BigDecimalTypeSerializer.java:22–52`
  serializes money as its decimal string and accepts several scalar types on reads.
- Essentials `config/EssentialsUserConfiguration.java:35–58` distinguishes old name files and
  alternate offline UUID files; `commands/Commandpay.java:52–69` separates minimum pay, offline
  permission, accepting-pay and recipient-ignore checks; `commands/Commandpaytoggle.java:30–39`
  directly stores accepting-pay. Essentials `src/main/resources/config.yml:875–908` contains
  `max-money`, `min-money`, `minimum-pay-amount`, `pay-excludes-ignore-list` and tax settings.
- `/mnt/storage/repos/Allium/src/main/java/codes/castled/allium/managers/DB/Database.java:91–108,312–327,502–508,2122–2161`:
  embedded H2 at `<plugin-data>/database/sfcore`, `player_data`, and exact `player_balances`
  schema; its balance getter creates zero rows and returns zero on failures, so is unsuitable
  for importing. That checkout's `managers/economy/EconomyManager.java:41–60,223–245` and
  `src/main/resources/config.yml:569–589` show display/starting/negative settings and a
  sequential withdrawal/deposit/refund transfer, not an atomic precedent to copy.

## Money representation and validation

Use **signed integer minor units**, Java `long` and SQL `BIGINT` (SQLite INTEGER storage), with
fixed scale 2: `1.00 = 100` minor units. `Money` is an immutable domain value; no `double`, SQL
REAL/DOUBLE or mutable BigDecimal balance enters the domain. SQLite's `DECIMAL(40,2)` declaration
does not enforce exact fixed-point storage; minor units give both dialects the same exact
arithmetic and simplify conditional updates. DECIMAL scale 2 would be reasonable on MySQL alone
but would require a different exact SQLite encoding. Do not preserve the scaffold's 40-digit range.

The hard absolute bound is **1,000,000,000,000,000 minor units** (`10,000,000,000,000.00` major
units). This is below `long` overflow and limits the Vault double compatibility surface. Validate
the exact round trip at the adapter boundary as well; do not infer cent fidelity from finiteness
or a cast. Use `Math.addExact`/`subtractExact` for all Java calculations, including guard thresholds,
reservation totals and import aggregates (use BigInteger for whole-import totals).
Ledger differences such as an admin change from -HARD to +HARD may reach 2*HARD; they are
checked `long` deltas, not new account balances, and must not be incorrectly rejected by the
single-balance bound. No account balance or externally requested debit/credit exceeds HARD.

- Commands accept one bounded ASCII token matching `[0-9]+(\.[0-9]+)?`, at most 64 characters;
  admin absolute-set additionally accepts a leading `-`. No separators, currency symbols,
  exponent, whitespace inside the token, wildcards, signs on pay, localized parsing or stripping
  arbitrary characters. Parse with `new BigDecimal(token)` without a rounding MathContext.
- Convert with `setScale(2, UNNECESSARY).movePointRight(2).longValueExact()`, then hard/configured
  bounds. Extra trailing zeros are harmless: `1.2300` is `123`. Nonzero precision beyond cents
  (`1.001`, `0.001`) is an error; never silently round, truncate or turn a nonzero amount into zero.
- Internal BigDecimal compatibility inputs use that same exact conversion, never `doubleValue()`
  first. Positive mutations require `amount_minor >= 1`; `has(0)` is a read-only comparison;
  deposit/withdraw/pay/reserve of zero fail without a journalled money change. Absolute-set may
  set zero or an explicitly permitted negative balance. Null, overflow and out-of-range inputs
  produce typed validation errors before SQL. Config parsing reads decimal scalars as decimal
  text, not `getDouble`; invalid precision makes configuration invalid.
- Formatting always prints exactly two decimals with an explicit locale and configured symbol/
  grouping rules. Formatting is presentation only and never normalizes money for persistence.
  Display rounding, if needed for a non-money statistics value, uses HALF_EVEN explicitly and
  cannot feed an economic operation. Money itself requires no rounding.

### Vault double boundary

`fractionalDigits()` remains 2; world overloads address the same global UUID account. Banks remain
unsupported (`NOT_IMPLEMENTED`); no implicit bank/account-name namespace is introduced.

1. Check `Double.isFinite(amount)` **before** `BigDecimal.valueOf(amount)`, then the sign, hard
   bound and exact cent conversion above. Reject NaN, either infinity, negative mutation amounts,
   sub-cent amounts, and arithmetic residue such as `0.1 + 0.2` if its canonical decimal is
   `0.30000000000000004`. This deliberate strictness must be documented for integrations: callers
   choose their intended cents before calling; Allium does not guess or round their charge.
2. Egress uses `BigDecimal.valueOf(minor, 2).doubleValue()`. Require finite output and
   `BigDecimal.valueOf(output).setScale(2, UNNECESSARY).movePointRight(2).longValueExact() == minor`.
   An unrepresentable result is an adapter failure, never a different balance. Validate every
   returned balance and response amount, and test bounds and adjacent cents before release.
   Binary exact representation of every decimal fraction is not claimed; cent-level canonical
   round trip is the contract. A larger storage range requires redesigning this boundary.
3. `has` uses the validated integer amount and authoritative **available** balance, not a floating
   comparison. Negative/nonfinite/excess-precision queries return false with diagnostics; they
   cannot authorize withdrawal. A successful `has` is informational, never a reservation.

## Accounts, limits and public API

Proposed configuration (all new keys are design, not existing runtime configuration):

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
`0.01 <= minimum-pay-amount <= max`. Default no overdrafts. Negative configured minima allow
explicit audited admin/import debt, not an implicit loan permission: PAY, Vault withdrawal and
cost reservation use `floor = max(0, min-money)`. Admin set may use the configured negative min.
Deposits can reduce existing debt. No operation clamps or partially credits at the maximum.
For `minimum-pay-amount=1.00`, `0.99` fails, `1.00` is permitted if other checks pass.
Admin set must also preserve outstanding holds (`new_balance - reserved >= min`).

Use one authoritative database/policy epoch for a shared economy, not different bounds/defaults
on different MySQL backends. Persist the monetary settings, offline/ignore policy and payment
acceptance default in the settings row specified below. Startup compares local effective config
with it and refuses conflicting values. An authorized reload updates the shared settings/epoch
transactionally; it must reject a bounds change that strands existing balances/holds. Recheck
all accounts while holding the settings row exclusively. Readers/mutations hold its shared lock
so this cannot race a debit. Unset `accept_payments` still follows
`preferences.defaults.accept-payments`; its payment-enforcement value comes from this shared
epoch. Wire that default's reload to the same coordinator, rather than leaving the current local
`PreferencesModule.reloadDefaults()` as an independent payment policy change. Other preferences
keep their existing default behavior.

Proposed core service exposes `balance(UUID)`, `createAccount(request)`, `deposit(request)`,
`withdraw(request)`, `set(request)`, `transfer(request)` and future reservation methods. Requests
carry an authenticated actor/origin, operation ID, external idempotency key when supplied, amount,
participants, reason/transaction type and policy epoch. Do not accept a client-supplied claim of
permission. Internal transaction types are closed enums: `OPEN`, `PAY`, `VAULT_DEPOSIT`,
`VAULT_WITHDRAW`, `ADMIN_SET`, `IMPORT_OPEN`, `COST_RESERVE`, `COST_CAPTURE`, `COST_RELEASE`.
Types are selected by trusted entry points, not a command argument that can bypass PAY checks.

Return typed results: committed receipt (operation ID, exact amount, resulting book/available
balances, versions), business rejection, storage unavailable, or outcome unknown. Reads distinguish
`Found(snapshot)`, `Missing` and `Unavailable`; never encode all three as null/zero/false. Allium
commands use bounded asynchronous storage and return to the server thread for messages/events.
Completion of their success future means the transaction has committed. Queue acceptance can
say “processing” with an ID, never “paid.” Connection generations fence notifications after quit;
disconnect after acceptance does not undo an already committed payment.

Account UUID is independent of presence/name. The new account table has **no FK to
`allium_players` or the name cache**: these are incomplete, mutable indexes and their deletion
must not delete money/history. UUID overloads can explicitly open an account without inventing
a name. Join or explicit account creation opens it exactly once, inserting the configured starting
grant and its OPEN journal/entry in one transaction. Duplicate creation returns an existing-account
result without resetting money. Vault `createPlayerAccount` returns true only when existence is
confirmed after that operation; an already existing account can return true with no new grant.
Deposits, withdrawals and transfers require existing accounts; a missing account is a typed failure,
not an accidental default grant or deposit that replaces one. Imports open directly with the
imported amount and never receive a starting grant on top.

Balance reads do not create accounts. Vault maps a confirmed missing account to 0.00 and false
for `hasAccount`; database unavailability is different (see synchronous behavior below).
Book balance `B` includes held funds; reserved total `R` is unavailable for other spending.
Require `min <= B <= max`, `R >= 0`, and `R <= B - min` (R=0 is allowed for debt within min).
Vault `getBalance` and `has` expose **available = B - R** so shops do not promise spending held
money; `/balance` may additionally label book and reserved values. Response balances use the
same available definition. Maximum-credit guards use book balance, so holds cannot create room
for credit that would exceed the cap after release.

## Storage schema and migration plan

Add an append-only `economy` component through `SchemaMigrator`; validate exact table shapes,
numeric types, indexes, engine and constraint support before enabling the service/provider.
The following is the v1 target, with no implementation implied. UUIDs are canonical lowercase
36-character strings; hashes are lowercase 64-character SHA-256 hex; times are epoch milliseconds.
All unspecified columns are NOT NULL. Enums are bounded VARCHARs validated in code and CHECKs
where supported. MySQL tables use InnoDB with binary/ASCII comparison for identifiers/keys;
SQLite uses integer bindings and `CHECK(typeof(column) = 'integer')` on money/version columns.
`BIGINT` must be signed on MySQL. Do not use SQLite REPLACE or broad MySQL INSERT IGNORE.

| Table | Exact logical columns and keys |
| --- | --- |
| `allium_economy_settings` | `id INT PK CHECK(id=1)`, `epoch BIGINT`, `min_minor BIGINT`, `max_minor BIGINT`, `starting_minor BIGINT`, `min_pay_minor BIGINT`, `accept_payments_default BOOLEAN`, `offline_pay_enabled BOOLEAN`, `pay_excludes_ignore_list BOOLEAN`, `updated_at BIGINT`. |
| `allium_economy_accounts` | `uuid CHAR(36) PK`, `balance_minor BIGINT`, `reserved_minor BIGINT DEFAULT 0`, `version BIGINT`, `created_at BIGINT`, `updated_at BIGINT`. CHECK hard money bound, reserved >= 0, version >= 1; configured bounds checked transactionally. |
| `allium_economy_operations` | `operation_id CHAR(36) PK`, `origin VARCHAR(64)`, `request_key VARCHAR(128)`, `payload_version INT`, `payload TEXT`, `payload_sha256 CHAR(64)`, `kind VARCHAR(24)`, `actor VARCHAR(128)`, `source_uuid CHAR(36) NULL`, `target_uuid CHAR(36) NULL`, `amount_minor BIGINT`, `policy_epoch BIGINT`, `state VARCHAR(16)`, `result_code VARCHAR(40) NULL`, `result_payload TEXT NULL`, `created_at BIGINT`, `expires_at BIGINT NULL`, `finished_at BIGINT NULL`. UNIQUE `(origin,request_key)`; indexes `(state,created_at)`, `(source_uuid,state)`, `(target_uuid,state)`. |
| `allium_economy_entries` | `operation_id CHAR(36)`, `account_uuid CHAR(36)`, `delta_minor BIGINT`, `reserved_delta_minor BIGINT`, `balance_before BIGINT`, `balance_after BIGINT`, `reserved_before BIGINT`, `reserved_after BIGINT`, `account_version_after BIGINT`. PK `(operation_id,account_uuid)`; index `(account_uuid,account_version_after)` UNIQUE. FK operation → operations RESTRICT; FK account → accounts RESTRICT. |
| `allium_economy_policy_locks` | `uuid CHAR(36) PK`. A persistent mutex row for recipient policy even when no preference/ignore row exists. No FK to players. |
| `allium_economy_reservations` (future M3 schema addition) | `reservation_id CHAR(36) PK`, `account_uuid CHAR(36)`, `workflow_kind VARCHAR(16)`, `workflow_id CHAR(36)`, `amount_minor BIGINT`, `state VARCHAR(16)`, `version BIGINT`, `prepare_operation_id CHAR(36) UNIQUE`, `final_operation_id CHAR(36) NULL UNIQUE`, `created_at BIGINT`, `updated_at BIGINT`. UNIQUE `(workflow_kind,workflow_id)`; index `(account_uuid,state)`; FKs account/operation RESTRICT. |
| `allium_economy_import_runs` | `run_id CHAR(36) PK`, `source_id VARCHAR(64)`, `source_kind VARCHAR(24)`, `manifest_sha256 CHAR(64)`, `mapping_version INT`, `state VARCHAR(16)`, `options_payload TEXT`, `report_payload TEXT`, `created_at BIGINT`, `finished_at BIGINT NULL`. |
| `allium_economy_import_rows` | `source_id VARCHAR(64)`, `source_record VARCHAR(128)`, `record_sha256 CHAR(64)`, `run_id CHAR(36)`, `target_uuid CHAR(36) NULL`, `raw_amount TEXT NULL`, `minor_amount BIGINT NULL`, `state VARCHAR(16)`, `reason VARCHAR(128)`, `operation_id CHAR(36) NULL UNIQUE`, `source_metadata TEXT`. PK `(source_id,source_record)`; FKs run/operation RESTRICT. |

Canonical operation payload includes all participants, intended amount/absolute-set target,
transaction type, actor/captured authorized capabilities, reason, settings epoch, workflow/import
identity and any expiry. Store this immutable payload, not just its hash, and compare both on
idempotent replay. `result_payload` freezes the original receipt, including resulting balances;
replay must not present today's balance as the original operation's resulting balance. Do not put
credentials, mutable Player objects or a presumed current online permission check into the payload.

DDL is separate maintenance work; never mix MySQL DDL into a monetary transaction. Migrate with
economy writes disabled on all nodes, inspect existing definitions rather than trusting
`CREATE IF NOT EXISTS`, and refuse a future schema version. Preserve the old `allium_balances`
read-only for audited conversion; do not change a DECIMAL column in place or run both writers.
Migration verifies original exact values, row count, per-row mapping and BigInteger totals before
switching service registration. Back up database plus journal as one unit; SQLite backup must
include WAL through an online backup or a stopped/checkpointed database, not a raw open-file copy.

## Transactions, lock order and conditional money changes

Each monetary execution uses **one borrowed connection and one transaction** for all participant
reads/changes, entries, reservation/workflow transitions and final operation state. Repository
methods accepting that connection must not open their own, commit, or dispatch another task.
Process-local locks are insufficient for MySQL backends. Restore pooled connection state, close
statements/result sets, and discard a connection whose commit/rollback state is uncertain.

Dialect rules:

- **SQLite:** obtain the write lock with `BEGIN IMMEDIATE` before any transaction reads. Use the
  JDBC driver's supported immediate-transaction mode with auto-commit disabled; do not execute
  BEGIN inside an already-started JDBC transaction. One bounded writer lane; WAL, foreign keys
  and `synchronous=FULL` verified, `foreign_keys=ON` on every connection. WAL's existence alone
  is not durability. Use bounded busy/borrow/statement timeouts chosen by the measurement gate,
  not the scaffold's 10-second main-thread wait. Multiple processes still rely on SQLite locks;
  network-mounted shared SQLite files are unsupported. Readers use committed snapshots.
- **MySQL:** explicit auto-commit false, InnoDB and READ COMMITTED; read/write only the authoritative
  primary, never a lagged replica. Disable reconnect-in-a-transaction behavior; a replacement
  session is a new transaction, not a continuation. Lock the settings row in shared mode
  (`LOCK IN SHARE MODE`); use `SELECT ... FOR UPDATE` for the operation, policy and account rows.
  Require verified durable commit configuration (`innodb_flush_log_at_trx_commit=1` and
  `sync_binlog=1` when binlogging) and documented storage guarantees. Failover with unacknowledged
  replication loss cannot supply this contract; do not automatically retry against a stale primary.

Global acquisition order for monetary/workflow transactions is: settings row → operation row →
policy mutex rows in ascending canonical UUID order → accounts in ascending canonical UUID order
→ reservation rows in ascending ID order → mail/kit workflow rows in ascending ID order. Acquire
each account with a point lookup in that order, not a hope that SQL `IN` result order equals lock
order. Shared settings locks permit independent MySQL accounts to proceed concurrently. Insert
missing mutex/account rows in the same sorted order using duplicate-key-specific conflict handling
and reread; no upsert can reset a balance. Normal money operations never create a missing account.
Opening/import operations insert accounts and then lock them before creating the opening entry.
Keep transactions short: no Bukkit calls, disk/playerdata save, external Vault call or notification
while holding them. A writer that needs only part of the order must never later acquire an earlier
class of lock. Reservation capture/release also follows this order; discover account IDs from
immutable request/reservation identity first, then verify the row again after locking.

For debit amount `d>0`, locked settings provide `floor`; compute `threshold = R + floor + d`
with checked arithmetic. Bind exact long values, then require one changed row:

```sql
UPDATE allium_economy_accounts
SET balance_minor = balance_minor - ?, version = version + 1, updated_at = ?
WHERE uuid = ? AND balance_minor >= ?;
-- bindings: d, now, uuid, threshold
```

Because every writer locks the account, R remains stable. The predicate is still mandatory:
`hasEnough` followed by an unconditional assignment is forbidden. For a credit, require
`balance_minor <= max - d`, use `balance_minor = balance_minor + d`, and require one changed row.
Neither SQL nor Java may saturate/wrap. For admin set, lock/read the current value and issue a
guarded UPDATE with expected version, bounds and holds check; record the exact difference as an
audited source/sink entry. A zero difference returns a committed no-change receipt without an
account version bump/entry. Treat a zero update count as a domain rejection only if the locked
row and predicates prove the reason; a vanished row or count >1 is a storage invariant error.

A PAY transaction locks both accounts, checks authoritative recipient policy, then conditionally
debits source and credits recipient; inserts exactly two opposite entries and sets the operation
COMMITTED before committing. If the recipient cap/credit fails, roll back the **entire** financial
unit (use a savepoint after locking the PREPARED operation), then persist REJECTED on that same
locked operation with no balance/entry changes. If even that cannot be committed, leave PREPARED
for recovery. Never deposit a compensating refund into another transaction. No user notification
or success event precedes commit. Every normal debit/credit, including Vault and opening grants,
uses the same journal engine; PAY additionally preserves total book money across its two accounts.
Opening/admin/import/payout/cost operations explicitly identify their issuance or sink reason.

After locking an operation, return a terminal replay result before evaluating new policy. For
PREPARED execution require its policy epoch to equal the locked settings epoch; otherwise record
REJECTED/STALE_POLICY with no financial effect, rather than mixing old authorization and new
limits. A fresh intent can use the new epoch. Future reservation capture/release requests capture
the current epoch independently of their old reserve request; reload may not strand a hold, and
must not make its release impossible. Validate any Vault receipt's double conversions from the
locked resulting values before commit, so a predictable conversion error cannot appear only
after money has already committed.

Known deadlock victims/lock timeouts with **confirmed full rollback** may retry the whole operation
using the same ID (at most two automatic retries, with bounded jitter on a worker). Retry logic
reacquires all locks and reruns guards. A network/commit exception is not evidence of rollback.
Synchronous Vault has no background success path; exhaustion returns known failure or unknown,
as appropriate, rather than scheduling a new operation and returning SUCCESS.

## Journal states, idempotency and ambiguous commits

Durably accept a valid operation by inserting **PREPARED** with immutable payload in a short
transaction before executing it. This is intent, with **no financial effect**. A duplicate
`(origin,request_key)` or operation ID must lock/read the existing operation: identical payload
returns or resumes its original result; different payload returns IDEMPOTENCY_CONFLICT without
money changes. Only duplicate-key errors take that branch; SQL failure is not a duplicate.
PAY intent expires after 30 seconds if still PREPARED; expiry can only reject it while holding
the operation lock, so it cannot undo a committed payment. Commands show success only after
execution commit, never after PREPARED commit. A repeated user command is a new intent unless
the entry point deliberately reuses its displayed operation ID; do not dedupe by amount/time alone.

| Durable state | Money/entries invariant | Allowed next state |
| --- | --- | --- |
| No row | No accepted intent and no entries attributable to this ID. Preparation may itself be ambiguous until resolved. | PREPARED by unique insertion. |
| PREPARED | Zero balance/reservation effect and zero entries for this operation. A concurrent executor may have an uncommitted transaction; take the row lock before interpreting it. | COMMITTED or REJECTED in a transaction holding that lock. |
| COMMITTED | Exact operation effect plus entries and frozen receipt committed together. PAY has both legs or neither; reservation/workflow finalization is included when applicable. | Terminal; identical replay returns receipt. |
| REJECTED | No financial effect/entries for this ID; stable business reason (policy, cap, missing account, expired, etc.). | Terminal; identical replay returns rejection. |

There is no committed DEBITED-only PAY state, no automatic REFUNDED state, and no terminal FAILED
meaning “we did not hear back.” UNAVAILABLE and OUTCOME_UNKNOWN are caller observations, not
proof of a database state. REJECTED may be recorded only when the transaction proves no effect.
An ordinary known-failure response also promises no later financial effect for that intent:
it requires a terminal REJECTED record, or proof that preparation never committed and no
executor can still run it. Merely rolling back one execution attempt while leaving durable
PREPARED intent is insufficient; report pending/unknown until it is finalized. A transient
worker/pool/queue failure after preparation cannot silently leave work to debit after an
ordinary “nothing happened” response.
If a process dies before final commit, recovery observes PREPARED after rollback; if it dies after
commit, recovery observes COMMITTED with both legs. Immutable journal and entries are not deleted
when players leave, names change or an account is administratively hidden. Do not prune dedupe
keys on a timer; archival must preserve authoritative uniqueness tombstones and receipts.

On lost commit acknowledgement (including preparing an intent):

1. Stop using that connection; do not report SUCCESS, refund, or replay under a new key. Record
   the operation ID in diagnostics and put affected local API paths in recovery/unavailable mode.
2. On a fresh connection to the **same authoritative primary**, obtain the appropriate transaction
   lock and `SELECT` the operation for update. This must wait for the previous transaction to end;
   an unlocked snapshot or a replica's missing row does not prove rollback. If preparation has
   no visible row, attempt the same unique insertion: a concurrent old insertion is resolved by
   the uniqueness/lock wait before any new execution. On SQLite BEGIN IMMEDIATE provides that
   serialization. A timeout doing this remains unknown.
3. COMMITTED returns the frozen receipt; REJECTED returns its rejection. PREPARED under its lock
   proves no final effect survived: execute the same request if still valid, or reject expired/
   stale-policy intent. Verify hash/participants and entries; inconsistent state quarantines the
   account/service for operator repair, never a balance guess. Each recovery executor competes
   on the operation row; only one can finalize it.
4. If storage cannot resolve the outcome, return OUTCOME_UNKNOWN with the ID, suppress success
   messages/events and keep retrying reconciliation with bounded backoff. At startup reconcile
   unfinished intents and audit terminal invariants before admitting affected accounts; independent
   accounts may become available after their own checks. A known PREPARED intent must not be
   abandoned just because a UI callback disconnected. Replays cannot apply its effect twice.

Internal callers must retain operation IDs across retries; a durable workflow records its key
before submission. The journal guarantees exactly-once **effects for a stable key**, not exactly-once
network delivery or user notification. Vault's API supplies no caller request key: one invocation
gets one internal ID, but a plugin repeating the call is a new request. Allium cannot safely
infer that two equal Vault calls are retries. On unresolved commit return a failure marked
`OUTCOME_UNKNOWN:<operation-id>; do not retry automatically`, fence the local adapter until
reconciled, and retain the receipt for diagnosis. This limitation remains even if the adapter
later recovers; external plugins needing safe retry must use the keyed Allium API. Never claim
unqualified exactly-once behavior for unkeyed Vault calls.

## PAY, offline identity, paytoggle and authoritative policy

PAY is player-to-player only: proposed `allium.pay`, optional `allium.pay.offline` (default false),
and no console source balance or wildcard/multi-recipient payment. Reject self-payment, unknown
identity, missing account, nonpositive/sub-minimum amount and unavailable policies before money
changes. Admin grants use the separate audited admin operation and permission. No tax is designed
for S14; a future tax requires another conserved journal leg, never silent amount reduction.

Reuse `PlayerNameService.complete` for bounded, permission/visibility-filtered suggestions; zero
SQL/disk/network work per keystroke. On execution use its exact online/authoritative offline
resolution on the correct thread, then freeze UUIDs in the request. Never execute from a stale
suggestion, display name, alias fragment, `getOfflinePlayers()` enumeration, or Mojang lookup.
Name resolution error is unavailable, not unknown name or a newly synthesized account. Recheck
current online visibility on the server thread before submission. A vanished player is treated
like an offline addressed target: only an authorized offline payment can proceed, using the same
generic errors as other offline recipients; no status leak. Names identify a request once; retry
uses frozen UUIDs so name reuse cannot redirect money.

`offline-pay-enabled=true` allows a sender **with** `allium.pay.offline` to pay an existing known
offline account; false refuses it for all ordinary payers. This setting does not make absent
preferences accepting. A disconnect before command admission rechecks the offline permission;
after a durable intent is admitted it is an addressed UUID payment, not contingent on the target
remaining online through SQL commit. Online players elsewhere in the network are offline from
this backend unless a future authoritative presence service supplies a stronger policy.

For recipient R and sender S, effective payment acceptance is
`allium_preferences.accept_payments` when non-NULL, otherwise the shared configured default
**after a successful read**. Read it authoritatively on every payment even for a locally READY
session. If `pay-excludes-ignore-list=true` (proposed default), block when
`allium_ignores.owner=R AND ignored=S`; this is recipient ignoring sender, not the reverse.
False deliberately disables that one check and must be visible in policy/audit. Persisted toggle
refusal and ignore refusal have the same generic recipient-not-accepting reply. Storage errors,
locally LOADING/FAILED acceptance or UNKNOWN required ignore state return `preferences.unavailable`
and produce no debit or recipient notification. A known missing preference row inherits defaults;
a failed SELECT does not. Module-disabled required policy is unavailable, not permission to pay.

Authoritative reads must participate in the financial transaction, not be a preflight TOCTOU
check. Add connection-taking preference/ignore reads and a common policy mutex protocol:

- PAY locks R's `allium_economy_policy_locks` row before reading the stored preference/ignore
  rows. Every payment-toggle writer and every recipient ignore add/remove writer acquires that
  same mutex on its connection before persisting. Missing rows are protected by the mutex too.
  An ignore writer locks the owner UUID. Multi-owner administration uses sorted UUID locks.
- This requires changes to `PreferenceRepository.apply` for ACCEPT_PAYMENTS, `IgnoreRepository`
  add/remove, payment-default reload and future imports. Existing repository methods do not yet
  implement it. Without all these writers adopting the protocol, strict policy/debit ordering
  is unverified and cannot ship. The settings lock precedes policy locks for writers that need
  both. SQL locks arbitrate cross-server races; whichever transaction wins determines whether
  the payment precedes or follows the persisted toggle/ignore change.
- Also keep the existing conservative local overlay: a pending local refusal blocks new
  admissions immediately; pending local acceptance never overrides stored refusal. Serialize
  local payment admission and policy-change admission through a per-recipient fence/epoch, so
  a refusal submitted first is persisted/failed before a later payment is executed. A previously
  admitted in-flight payment may finish before a later toggle; report toggle success only after
  its commit/fence completes. Never promise a toggle retroactively cancels a committed payment.
  This supplements the SQL protocol; a session cache alone cannot grant acceptance.

Design `/paytoggle [on|off] [player]` with **on=accept, off=reject**, bare self-toggle flips, explicit
on/off is idempotent, `allium.paytoggle` authorizes self-use, and `allium.paytoggle.others` is
additionally required for another UUID. Proposed self pay/paytoggle permissions default true;
offline-pay and other-player administration default false and require explicit grants. Console must
name a target; invalid syntax never falls back to toggling self. Offline administration uses the
authoritative name/UUID repository and nullable preference patch, not a fabricated session.
Bare flips must evaluate the authoritative effective value under the mutex and invert it there,
so concurrent flips do not lose one another. Write only `ACCEPT_PAYMENTS`, increment the preference
version and preserve unrelated fields; acknowledge persistence failures explicitly. Keep pending
local refusal active while the write is unresolved. These commands are new design, not features
already supplied by the scaffold or by the existing msgtoggle executor.

No payment-acceptance/ignore bypass is implicit for staff, op, display names, messaging bypasses,
or an alias. S14 defines no ordinary PAY bypass. Trusted shop rewards, Vault deposits and admin
grants are separate operation types and do not consult PAY acceptance/ignore or require offline-pay
permission. They still obey amount/bounds/account/transaction rules. A shop must not relabel an
arbitrary player payment as a trusted payout to bypass recipient policy.

## Synchronous Vault consistency and release measurement gate

Vault methods return synchronous values. **SUCCESS means the authoritative transaction and its
journal/entries have committed**, with the response amount equal to the exact applied amount and
the response balance captured from that transaction's resulting available balance. It does not
mean queued, preflight passed, cache updated, future scheduled, or “likely to commit.” A subsequent
uncontended authoritative read sees at least that committed version; later concurrent operations
can naturally change the value. No async success before commit and no write-behind balance cache.

The intended adapter calls the same transaction engine synchronously, including when invoked
on the server thread. It must not join an async task whose callback needs that thread, hold
server-thread locks while workers need them, or ask Bukkit to resolve a String name on a worker.
Internal commands remain async; this does not make Vault itself async. Reads are authoritative
primary reads with no dirty/uncommitted or fabricated cached success. `has` reserves nothing.

For a known business rejection the response is FAILURE with applied amount 0 and a validated
balance if one was authoritatively obtained. For unavailable/unknown storage, return FAILURE with
amount 0, a documented 0 placeholder balance and error code/ID; that balance is **not** a statement
that the account is empty. Never issue a second speculative read to manufacture a success balance.
Primitive reads cannot carry an error: `getBalance` throws a documented unchecked
`EconomyUnavailableException` on storage failure (never returns a default grant/zero/NaN); `has`
and `hasAccount` fail closed false with rate-limited diagnostics. Provider enable/registration is
conditional on migration/recovery/readiness, and storage failure opens its circuit. Integration
tests must verify dependent plugins tolerate these explicit failures; Vault cannot express an
unknown outcome as SUCCESS. No design can turn a timeout after COMMIT into proof of no debit.

Legacy String overloads are another synchronous boundary. Replace every
`Bukkit.getOfflinePlayer(String)` call in `EconomyModule.java:98–99,118–119,166–167,191–192,224–225`
with a shared no-network resolver: exact online snapshot when on the server thread, otherwise
the authoritative `allium_player_names` repository lookup. Do not call async
`PlayerNameService.resolve(...).join()` on the server thread. Off-thread String calls may use
the repository without Bukkit; the name cache is only an optimization for suggestions, not
authorization. Unknown names cannot open accounts; explicit UUID/OfflinePlayer overloads avoid
name lookup. Equal `last_seen` ownership conflicts should fail ambiguous rather than silently
redirect a payment; add a deterministic ambiguity check to the repository. The remaining JDBC
read/write and String lookup may block the main thread and are a release risk until measured.

### Measured disposable JDBC probe — full Vault gate still open

The disposable probe ran once on
**2026-11-13, 15:07:30–15:07:50 UTC**, exit 0; the interrupted follow-up only inspected and
summarized its retained successful results, without rerunning measurements. All source, classes,
copied dependencies, temporary SQLite data and raw output are retained outside Git under
`/mnt/storage/.allium-validation/20261113/s14-economy`. No plugin was loaded and no production
server/JVM on 25565 was started or contacted. MySQL used only the previously owned
`allium-gate-mysql` on `127.0.0.1:25733`, database
`s14_economy_3a8668d2351f48b4822288f8e54c65d9`; its successful DROP acknowledgement is retained.
The credential file `/mnt/storage/.allium-validation/infra/mysql.env` was read privately;
passwords were passed only in the child process environment, not arguments, copied files or logs.
The container was restored to **exited**, independently verified during final summarization.
The SQLite artifact contains disposable probe data only; no database is left running.

Reproducible local artifacts:

- [Probe source](</mnt/storage/.allium-validation/20261113/s14-economy/src/EconomyJdbcProbe.java>),
  [private-env launcher](</mnt/storage/.allium-validation/20261113/s14-economy/run_probe.py>),
  [compile/run commands and timestamps](</mnt/storage/.allium-validation/20261113/s14-economy/commands.json>),
  [environment and dependency origins](</mnt/storage/.allium-validation/20261113/s14-economy/environment.json>).
- [Raw per-operation nanosecond samples](</mnt/storage/.allium-validation/20261113/s14-economy/results/samples.jsonl>),
  [computed millisecond summary](</mnt/storage/.allium-validation/20261113/s14-economy/results/summary.json>),
  [read-only summarizer](</mnt/storage/.allium-validation/20261113/s14-economy/summarize.py>),
  [run log](</mnt/storage/.allium-validation/20261113/s14-economy/run.log>),
  [run status](</mnt/storage/.allium-validation/20261113/s14-economy/results/run-status.json>).
- [SQLite audit](</mnt/storage/.allium-validation/20261113/s14-economy/results/sqlite-audit.json>),
  [MySQL audit](</mnt/storage/.allium-validation/20261113/s14-economy/results/mysql-audit.json>),
  [unique-database cleanup](</mnt/storage/.allium-validation/20261113/s14-economy/results/mysql-cleanup.json>),
  [final stopped-container verification](</mnt/storage/.allium-validation/20261113/s14-economy/results/container-final-state.json>),
  [all artifact SHA-256s](</mnt/storage/.allium-validation/20261113/s14-economy/SHA256SUMS.json>).

Pinned provenance (SHA-256, exact copied inputs rather than a claim about a later root build):

| Artifact | SHA-256 |
| --- | --- |
| `src/EconomyJdbcProbe.java` | `c6075fec1226190be7e3bf62ccfda855b721cd784edfa109951ab009a4f1debe` |
| `results/samples.jsonl` | `01c781c0c8415dd7005780adef6f10a4b832507ba8226faf6b7714b8a72a8edf` |
| `deps/Allium.jar` (2,266,447 bytes) | `bc0842dc492c19ace87b1469005c4be3fadbb666134519d84458dcf7d3998334` |
| `deps/sqlite-jdbc-3.49.1.0.jar` | `5c8609d2ca341deb8c6f71778974b5ba4995c7d32d7c7c89d9392a3e72c39291` |
| `deps/mysql-connector-j-9.2.0.jar` | `7e9941bbdcca244d878ea95bfff788fd9ba6a65af757f24be6c632930d61c7ed` |

The design's source baseline remains `f3c4736`. Probe setup recorded checkout HEAD
`323039510c46097983821b5c9db5328dd2d31207`; the copied jar's build-to-commit provenance was **not
attested**. Packaging inspection found SLF4J in that jar but neither JDBC driver, so existing
validation drivers were copied from the recorded S12 Paper library paths instead. Allium.jar
served only the driver's logging dependency; no EconomyModule, Vault, Bukkit or pool code was
invoked. The probe used `/usr/lib/jvm/java-17-openjdk/bin/javac --release 17` and that directory's
`java`, OpenJDK **17.0.21.0.3+1**, `-Xms64m -Xmx128m`; the host's default Java 27 was not used.

Measured environment: Intel Core **i7-12700KF**, 20 logical CPUs; host Linux
**7.2.14-arch1-1 x86_64**, glibc 2.44. SQLite **3.49.1**, Xerial JDBC **3.49.1.0**, disposable file
on `/mnt/storage` **ext4 `/dev/sda4`**; WAL, `synchronous=FULL` (2), foreign keys ON,
`busy_timeout=2000` ms, driver transaction mode IMMEDIATE. MySQL **8.4.13**, Connector/J **9.2.0**,
InnoDB READ COMMITTED, flush-at-commit=1, binlog ON with sync_binlog=1, doublewrite ON, session
lock-wait timeout 2 seconds, client connect/socket timeouts 2000/3000 ms and autoReconnect=false.
The owned container image was `mysql:8.4`, image ID
`sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902`, Oracle Linux 9.8;
its `/var/lib/mysql` volume was **btrfs `/dev/nvme0n1p2`**. Thus these are different storage paths,
not a controlled database-engine comparison. No device/power-loss durability claim follows from
the configured flush modes.

Method: raw JDBC with persistent connections, two hot accounts initially **1,000,000 minor units
each**, transferring **1 minor unit** per successful operation. Each transfer first inserts
PREPARED in an acknowledged autocommit statement, then starts an explicit transaction, locks the
intent and accounts in ascending order, conditionally debits/credits, inserts both entries,
sets COMMITTED, calls `commit()` and restores auto-commit **before** the timed return. SQLite
uses BEGIN IMMEDIATE; MySQL uses ordered point SELECT FOR UPDATE. A separate persistent observer
connection immediately verifies the terminal state, exact entry count and zero-sum entries after
each return. That verification is separately timed and excluded from the synchronous return
latency. Connection opens, schema/seed setup and compilation are also excluded. Statement
preparation/execution, guard assertions, intent commit and financial commit are included.
There is no Hikari pool, policy/default lock, name lookup, holds, actual service dispatch or
response conversion in this intentionally small SQL prototype.

Each backend ran 40 discarded read warmups then 200 measured reads; 40 transfer warmups retained
as a separate phase; 200 single-writer transfers; two synchronized writer threads with independent
writer/observer connections doing 100 transfers each (200 combined observations, **not two server
processes**); 10 insufficient-funds rejections; 10 real duplicate-key SQL failures injected after
debit/credit to verify rollback and subsequent durable rejection; and 10 transfers while another
connection held the SQLite write/MySQL account lock for a controlled 75 ms. The latter measures
lock contention, not injected slow COMMIT, network failure or a timeout. No automatic retries or
failed samples were removed. Percentiles use nearest rank `ceil(p*n)-1`, in **milliseconds**;
small-n p95/p99 equal max for the 10-sample lanes and do not estimate a stable tail.

| Backend | Timed JDBC phase | n | p50 ms | p95 ms | p99 ms | max ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| SQLite | Read only | 200 | 0.006 | 0.013 | 0.021 | 0.035 |
| SQLite | Single-writer two-commit transfer return | 200 | 2.016 | 3.260 | 4.509 | 5.370 |
| SQLite | Two-writer two-commit transfer return | 200 | 1.983 | 3.095 | 5.628 | 230.137 |
| SQLite | Insufficient-funds durable rejection | 10 | 1.540 | 2.769 | 2.769 | 2.769 |
| SQLite | Injected SQL error, rollback and durable rejection | 10 | 1.987 | 6.611 | 6.611 | 6.611 |
| SQLite | Transfer with 75 ms held lock | 10 | 81.947 | 82.680 | 82.680 | 82.680 |
| MySQL | Read only | 200 | 0.100 | 0.300 | 0.354 | 0.370 |
| MySQL | Single-writer two-commit transfer return | 200 | 36.292 | 54.767 | 88.496 | 117.502 |
| MySQL | Two-writer two-commit transfer return | 200 | 42.945 | 61.307 | 75.642 | 79.066 |
| MySQL | Insufficient-funds durable rejection | 10 | 32.782 | 56.973 | 56.973 | 56.973 |
| MySQL | Injected SQL error, rollback and durable rejection | 10 | 34.044 | 68.255 | 68.255 | 68.255 |
| MySQL | Transfer with 75 ms held lock | 10 | 93.128 | 99.736 | 99.736 | 99.736 |

The **final explicit `Connection.commit()` call alone**, excluding the first intent autocommit
and the rest of the operation, cost:

| Backend | Phase | n | p50 ms | p95 ms | p99 ms | max ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| SQLite | Single writer | 200 | 0.854 | 1.148 | 2.777 | 3.348 |
| SQLite | Two writers | 200 | 0.850 | 1.177 | 3.042 | 3.395 |
| MySQL | Single writer | 200 | 17.317 | 28.437 | 46.725 | 51.494 |
| MySQL | Two writers | 200 | 18.324 | 30.338 | 40.431 | 42.358 |

All **1,340 retained observations**, including warmups/read samples, remain in JSONL.
Per backend the audit found **450 COMMITTED transfers** (40 warmup, 200 single, 200 concurrent and
10 slow), **20 REJECTED**, **0 PREPARED**, **900 entries**, exact final balances **999,550** and
**1,000,450**, and conserved total **2,000,000 minor units**. Every successful transfer's terminal
commit returned normally before its observer saw both legs. All ten insufficient debits per
backend changed zero rows; all ten injected SQL failures acknowledged rollback and left no money
effect/entries, then committed a REJECTED record. All 470 terminal operations per backend had
acknowledged final commits. The run executed **10,410 invariant checks**, exit 0. This proves the
observed success/known-failure SQL paths only: **commit exceptions, lost acknowledgement,
ambiguous-commit recovery, process crash and power loss were not measured**.

Design implication: synchronous completion must include both durable acknowledgement costs;
an early return would hide real latency, not solve it. The measured loopback MySQL write tails
and SQLite contention outlier rule out assuming this raw SQL shape fits a server tick. SQLite's
230.137 ms maximum was largely in PREPARED insertion (229.315 ms), so timing just final COMMIT
would miss writer starvation. The controlled held-lock lanes also exceeded a 50 ms tick on both
backends. Keep the proposed synchronous Vault provider **unavailable pending the full adapter
gate and a reviewed performance solution**; do not loosen durability or return async SUCCESS to
meet a budget. These data bound the available time for a future adapter on this measured lane;
they neither predict a universal MySQL cost nor measure an implemented single-account Vault call.

### DESIGN UNVERIFIED: remaining release gate

The minimal probe above is design evidence, not the full release benchmark. **The existing
scaffold's actual read/write methods and the unimplemented Vault adapter were not measured.**
Record the disposable targets and cleanup plan before starting further benchmarks. The remaining
gate requires explicit throwaway paths/credentials, no production plugin boot/data, no root
workspace build/artifact writes, and cleanup of owned processes/databases with retained evidence.
Never open the legacy H2 database via its live API just to measure it.

Source inspection alone predicts the scaffold's common existing-account Vault deposit path
performs a read, an upsert and a response read; withdrawal performs a precheck read, another read,
an upsert and a response read, each borrowing independently (`EconomyTable:36–89`,
`EconomyModule:153–187`). These are **statement counts inferred from code, not timings**. SQLite's
10-second busy timeout, and MySQL's configured/default long pool borrow timeout, are not acceptable
evidence of tick safety. Moving work to a worker and blocking for it does not remove that latency.

Before implementation release, a dedicated disposable harness must record both the baseline
scaffold path and the candidate committed engine/adapter path, with actual driver/server/JVM
versions, storage medium, SQLite PRAGMAs or MySQL RTT/durability settings, pool sizes, timeout
settings and reproducible seed/workload. Capture:

1. At least 10,000 known accounts; warmup then at least 10 minutes per lane. Separate UUID reads,
   String existing/missing lookups, account creation, deposits, conditional withdrawals, transfers
   and insufficient/cap failures. Record end-to-end p50/p95/p99/p99.9/max and sample counts,
   including pool wait, both journal commits, lock time and any recovery read. Measure cold
   connection/startup behavior separately. Do not benchmark only a cached BigDecimal operation.
2. SQLite WAL/FULL with its actual single pooled connection and simultaneous preference/mail
   traffic; MySQL on the intended network path with at least two independent clients/backends.
   Run 1, 10 and 100 operations/second mixes (50% reads, 20% has, 10% deposits, 10% withdrawals,
   10% PAY), 50% hot-account contention, and bursts of 25 calls in a tick. Include two-way
   opposite transfers and cross-server toggle/ignore races. Record aggregate server-thread time
   per tick and queue saturation, not just isolated SQL latency.
3. Borrow starvation, held SQLite/MySQL locks, injected slow I/O/network, deadlock, disconnect
   before commit, lost commit acknowledgement, process halt before/after each journal boundary,
   restart and same-key replay. Verify no double effects, no partial transfer, correct unknown
   outcomes, bounded failure response, circuit recovery and no main-thread deadlock. Measure
   failure latencies separately from the healthy distribution.
4. Proposed release budgets: healthy synchronous UUID read and committed single-account write
   each p99 <= 2 ms, p99.9 <= 5 ms; no healthy call > 25 ms; total economy main-thread work
   p99 <= 5 ms/tick under the declared supported normal workload. These are **targets, not
   measured claims**. Burst results must establish a supported per-tick call budget; publish
   the limit and admission/circuit behavior rather than hiding a long tail. Faulted calls must
   finish within a documented tested deadline <= 50 ms or declare unsupported synchronous
   availability for that deployment. A timed-out queued operation must be proven cancelled
   before execution or reported unknown; timeout cannot permit a late unacknowledged debit.

If results fail, do not release/enable synchronous Vault on that backend or silently acknowledge
async writes. Rework the architecture, restrict and document the supported workload/backend,
or keep the provider unavailable until an explicitly reviewed alternative passes. Transaction
integrity is mandatory even if performance requires withholding Vault. A cache-based alternative
would need a separate ownership/durability/coherence design and measurements; it is not supplied
by this document. Retain raw results, methodology and the reviewed supported deployment
matrix; “unit tests passed” or “WAL enabled” cannot clear this gate.

## M3 future mail/kit costs: durable reservations

The existing `MailService.CostPolicy`/`setCostPolicy` remains a **side-effect-free preflight veto**.
It may reject a request; it must never withdraw, reserve, capture or refund. A balance preflight
does not lock money, and repeated evaluation could charge twice. Default free mail remains free
until the following transactional workflow is implemented and validated separately. Kits are
also a future workflow, not an excuse to add an early Vault withdrawal around item delivery.

Design a connection-aware `CostReservationService` integrated with the same database/journal:

- **Reserve:** with stable workflow ID and key, lock settings/operation/account/reservation in
  the global order. Check `B - R - cost >= floor`; atomically increase account R by cost,
  increment account version, insert a HELD reservation and a COST_RESERVE journal entry with
  zero book delta and positive reserved delta. Insert the workflow's PENDING row on the same
  connection/commit. UNIQUE workflow identity prevents duplicate holds even with a new request
  key. Failure leaves no workflow and no hold. Reserve is not revenue or a completed debit.
- **Capture:** conditional HELD → CAPTURED, `B := B-cost`, `R := R-cost`, entry with both negative
  deltas and a sink reason; workflow activation/durable completion and COST_CAPTURE receipt
  are in that same transaction. An affected-row count of one is required. The maximum and
  minimum remain valid because reserve already fenced the available money. Replay with the
  same key returns the same receipt; a different finalize key cannot recapture a terminal hold.
- **Release:** conditional HELD → RELEASED, `R := R-cost`, book delta zero, COST_RELEASE entry;
  cancellation and release share a commit. Never model release as a deposit: held funds never
  left B. CAPTURED and RELEASED are terminal and mutually exclusive. A competing capture/release
  uses the same account/reservation locks; exactly one wins. Do not release on a timeout alone.
- **Text mail:** reserve plus text PENDING insertion, then capture plus visibility activation can
  be collapsed into one financial/mail transaction when no external effect is required. Existing
  `MailRepository.send` must gain connection-aware internals; separate repository commits are
  not a transaction merely because the service calls them consecutively.
- **Gift mail:** reserve when inserting the existing PENDING escrow; after native playerdata
  receipt is saved **and read back**, capture in the transaction that changes PENDING → READY
  and writes its immutable activation acknowledgement. Proven absence of a send receipt permits
  cancellation/release on the owning server. Unreadable/unknown receipts keep both gift and
  hold pending, following `mail.md`; no timeout-only refund and no independent activation/capture.
  Money and mailed items are not falsely described as one JDBC transaction: the receipt bridges
  the playerdata boundary. A READY gift's later claim/remnant is not another charge.
- **Kits:** stable delivery ID, hold plus PENDING kit/cooldown intent, inventory plan/cancellation
  before side effects, then a saved/read-back native playerdata receipt containing delivery ID,
  exact delivered items and inventory effect. Only matching receipt evidence can finalize kit
  completion/cooldown and capture atomically. Proven no delivery permits cancellation/release;
  partial delivery requires an explicit partial-cost policy or a complete-before-capture workflow,
  not an automatic full refund while keeping items. Until a durable kit receipt/recovery protocol
  exists, paid kits are denied. Reuse the item delivery service but do not mistake its in-memory
  event/callback for durable delivery proof.

R equals the sum of HELD reservations, checked during recovery/audit. Persist immutable IDs and
terminal acknowledgements; automatic expiry is only a recovery trigger to investigate proof,
not permission to drop a hold. A crash/ambiguous final commit resolves via journal and workflow
acknowledgement before inventory retry/refund. Mail lock ordering must be adapted so no existing
mailbox-first transaction later tries to acquire an earlier economy lock. Separate databases or
an external economy cannot join this local transaction; that is a new saga/outbox design, outside
M3's supported paid-mail/kit path. Database, journal, workflow tables, playerdata, server identity
and signing material require coordinated backup/restore.

## Imports: exact mappings, dry run and durable audit

Imports are explicit maintenance operations, never join-time money merges. Stop source writers,
take a verified consistent snapshot, disable target economy writers/provider across all nodes,
and inspect source schema before conversion. Read source artifacts without invoking their live
getters. Read-only H2 access must be against a separately copied **stopped/consistent** database,
with `IFEXISTS=TRUE;ACCESS_MODE_DATA=r` and a compatible H2 version; do not create/upgrade/open a
live legacy file. S14 has not opened an import source database or performed an import; only the
explicitly authorized disposable SQL probe above opened databases.

### Supported mappings

| Source | Exact read/mapping | Missing/unsupported cases |
| --- | --- | --- |
| Essentials UUID YAML | `<Essentials-data>/userdata/<uuid>.yml`: filename UUID → account UUID; root `money` scalar text → exact decimal → minor units. Root `last-account-name` → validated informational/current-name candidate only. Root `accepting-pay` boolean → `allium_preferences.accept_payments` **without inversion**. Root `npc: true` is not a player account. | Missing `money` is SKIPPED_MISSING_MONEY, not zero/starting balance. Missing `accepting-pay` leaves the destination override NULL (or existing override unchanged), so configured defaults apply; the report notes Essentials' runtime default true. Malformed/nonboolean/null explicit fields are rejected for review. NPC banks are excluded with an audited reason. Name-based/alternate offline files require an explicit reviewed UUID map; never guess a new UUID from the name. |
| Legacy Allium H2 | `<Allium-data>/database/sfcore.mv.db` (JDBC base path `database/sfcore`): `SELECT uuid, balance, last_updated FROM player_balances ORDER BY uuid`; schema `uuid VARCHAR(36) PRIMARY KEY, balance DECIMAL(20,2) NOT NULL DEFAULT 0.00, last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP`. UUID → UUID; `getBigDecimal(balance)` → exact cents; timestamp → source metadata, not journal time. Optional `LEFT JOIN player_data p ON p.uuid=b.uuid`, using `p.name` for a validated name candidate. | Missing player_data/name never drops a balance or invents a player FK. Missing balance row creates no import row/account. No inspected legacy payment-preference column exists in these two tables: do not manufacture paytoggle values from teleport toggle, display settings or allow-negative. |
| Recode scaffold conversion | `SELECT uuid, balance FROM allium_balances ORDER BY uuid`; `uuid VARCHAR(36)` → UUID and exact stored decimal → cents. MySQL source is DECIMAL(40,2). SQLite requires raw storage-class/value inspection before parsing. | SQLite REAL money is flagged LOSSY_SOURCE for explicit review/export; do not claim exact recovery from a floating value. TEXT/integer values must parse exactly; unsupported storage/null/corrupt values fail. Original wide values outside hard/configured bounds are rejected, not clamped. Missing `allium_players` rows do not discard money. |

Parse YAML money's original scalar decimal text, including quoted strings, with no binary-float
intermediate; safe bounded YAML loader, duplicate keys rejected. Quoted scientific decimal strings
that Essentials can serialize may be imported with `BigDecimal` exact parsing (bounded length and
exponent before expansion), unlike the intentionally narrower command syntax. YAML nonnumeric
tokens/NaN/infinity fail. Do not reuse Essentials' DECIMAL128 parsing to silently round an input;
do not invoke `UserData._getMoney`, which applies defaults and clamps. `accepting-pay` is a
positive acceptance flag in this inspected source, unlike inverted message/teleport fields;
keep this mapping field-specific. Verify the serialized field names against a disposable fixture
from the supported Essentials version as an implementation acceptance test. No production file
was loaded in this design task.

Import settings separately with a preview, never as side effects of balance conversion:

- Essentials `starting-balance`, `min-money`, `max-money`, `minimum-pay-amount`, and
  `pay-excludes-ignore-list` map to the corresponding proposed settings. Its reference minimum
  pay is **0.001**, which cannot be an exact cent: default import rejects that config with an
  explicit action required (choose at least 0.01), never silently rounds it. Its negative minimum
  can be represented, but does not silently grant loan behavior in Allium. Nonzero pay tax and
  NPC tax accounts are unsupported and flagged before cutover. Currency symbol/suffix are
  presentation settings only.
- Legacy Allium `economy.starting-balance`, currency symbol, symbol-before-amount and space-between
  are explicit mapping candidates. `economy.decimal-places` must equal 2 or require an operator
  choice; `allow-negative-balance=true` alone supplies no numeric minimum, so requires choosing
  one before accepting debt. False maps to min 0. Never use its getDouble-based config reader
  to recover exact amounts. Neither source's configured starting balance is added to imports.

### Idempotency and audit algorithm

1. Dry-run builds a sorted manifest of every source record's stable identity, raw bytes/canonical
   exact SQL values, hash, UUID, raw amount, converted amount, preference/name candidates and
   rejection reason. Record source checkout/schema/version, stable operator-assigned `source_id`,
   snapshot identity/hash, mapping version, chosen settings/UUID overrides and precision policy.
   SQL record hash includes UUID, exact decimal text and timestamp metadata. Use per-source
   record identity `UUID` for the two SQL sources, relative YAML path for Essentials. A file's
   absolute location is not a new identity merely because it was copied.
2. Default precision/bounds policy is REJECT, including sub-cent data, overflow and debt below
   configured min. If an operator separately authorizes lossy cleanup, require an explicit
   per-row override manifest with original value, replacement exact cents, signed difference,
   reason and author; a reviewed HALF_EVEN conversion may generate that manifest, but it never
   happens invisibly during import. Import totals include both original decimal totals and exact
   target minor totals/differences. Duplicate UUIDs, conflicting files/names, source identity
   reuse and multiple economy sources for one target are conflicts, never additive merges.
3. An apply run must use the approved identical manifest/options and maintenance lock. For each
   valid row, use an IMPORT_OPEN operation whose stable request key derives from
   `(source_id, source_record, mapping_version)`; the operation payload includes the record hash.
   Insert the account at the exact imported balance and OPEN entry, patch only approved unset
   preference fields, record an eligible name candidate without displacing a newer known name,
   and mark the import row APPLIED with its operation ID **in the same target transaction**.
   Follow settings → operation → policy → account lock order; import provenance/audit writes
   occur after those locks. Use connection-aware preference/name writes, never independent
   repository commits. Target accounts must be absent; any existing account, even zero/default,
   is CONFLICT_EXISTING_ACCOUNT. This prevents a rerun or second source from overwriting earned
   balances. Account-reset/replacement is a separate reviewed maintenance design.
   Imported names default to audit-only candidates; they do not overwrite the live directory or
   prove name ownership. Optional name seeding requires an approved UUID/name/last-seen manifest
   with provenance; unknown last-seen is 0, never import time, and conflicts require review.
   Until a join or approved seed establishes the directory entry, use UUID administration for
   that imported account rather than inventing a name resolution.
4. Identical rerun against `(source_id,source_record)` with the same record hash/mapping/options
   returns its existing APPLIED receipt or stable rejection; it does not reset the account,
   add the amount, reapply a preference, or grant defaults. Changed contents under the same source
   identity are CONFLICT_SOURCE_CHANGED, including changed options/mapping, and require a new
   reviewed adjustment plan. Do not bypass this by issuing a new run ID. Durable import-row
   uniqueness and operation-key uniqueness arbitrate concurrent/restarted runs. Store the
   effective mapping/options fingerprint with the row's `source_metadata` for replay comparison.
5. SQL/import errors never turn into successful zero/default rows. On an uncertain target commit,
   reconcile the stable operation and import row on the primary before continuing. Dry-run
   rejections enter the audit with no money operation; mark the run PARTIAL until all conflicts
   are explicitly resolved or accepted exclusions. Audit retains raw amount/source identity,
   target UUID, exact resulting cents, decision, settings epoch, operation ID, timestamps and
   per-row overrides. A run is COMPLETE only after count, exact totals and row/entry reconciliation
   match the approved manifest, including excluded rows. No enabling provider on an unexplained
   partial result. Recovery resumes by the same keys, never by “last row processed” alone.

## Scaffold replacement and acceptance checklist

The implementation sequence below is follow-up work, not authorization to start it during S14.

| Current specific defect | Required replacement |
| --- | --- |
| `EconomyTable:71–84` reads and writes on different connections: concurrent add/subtract loses updates. | Exact integer arithmetic UPDATEs with guards, one locked transaction and entries; never RMW upsert. |
| `EconomyModule:68–71,153–162` has an independent hasEnough precheck; concurrent callers can both pass. | Conditional available-balance debit inside execution transaction; preflight checks cannot authorize spending. |
| No transfer method/journal in this recode scaffold; legacy `EconomyManager:223–245` refunds in another call. | Two-account transaction, canonical lock order, both entries and COMMITTED receipt in one commit; no best-effort refund. |
| Vault subtract/add return values are ignored (`EconomyModule:161–162,186–187`); success is claimed despite a failed write. | Typed committed receipt drives SUCCESS; affected-row counts, commit result and ambiguity recovery are mandatory. |
| `EconomyTable:36–48,92–98` maps SQL errors to missing account; `EconomyModule:50–55` returns 100 even when creation fails. | Missing/unavailable split, pure reads, explicit audited account open and failure-aware provider readiness. |
| `EconomyTable:25–28` player FK plus `DatabaseManager:87–91` required name can reject offline/account creation; SQLite FK enabling is not configured. | UUID-owned independent accounts, history RESTRICT FKs, verify FK enforcement per connection; no name requirement or cascade money deletion. |
| MySQL DECIMAL(40,2), SQLite affinity and `setBalance` permit unchecked amounts; module checks only sign in some paths. | Fixed cents, exact scale/bounds checks at every entry, atomic cap/floor/hold guards and corruption checks. |
| Vault uses doubles, accepts zero, converts nonfinite unchecked, and uses floating `has`; world/global contract is implicit. | Finite/exact-cent/bounded conversion, round-trip output, explicit zero/global-world/bank semantics and available integer comparison. |
| Legacy String Vault methods call Bukkit offline lookup; every read/write can block the server thread; 10-second SQLite lock waits. | No-network identity resolver, async command service, explicitly synchronous measured/gated Vault, short tested deadlines and circuit behavior. |
| `EconomyTable.init` swallows errors but `EconomyModule:31–38` still registers provider. | Fail-closed schema/config/recovery startup; provider registered only when ready and measured deployment lane approved. |
| `INSERT OR REPLACE` and default creation `hasAccount`/upsert can reset concurrent state. | Insert-once journalled opening, specific duplicate handling, no REPLACE; separate guarded admin/import operations. |
| Existing preference/ignore repositories read/write on their own connections and sessions can be stale. | Connection-aware transactional policy reads/writes plus shared mutex protocol and local pending-refusal fence. |
| Mail cost hook is only `allows`, without transaction or delivery proof. | Keep it pure; add separate journalled holds, connection-aware workflow commit and verified receipt capture/release later. |

Follow-up implementation order: (1) review S14 and fixtures/import mappings; (2) exact Money,
settings/schema/typed results; (3) transaction engine/journal/recovery, including both dialects;
(4) authoritative policy adapters and keyed async PAY/paytoggle; (5) imports and cutover rehearsal;
(6) measured synchronous Vault adapter; (7) independently reviewed M3 paid mail/kit reservations.
Retire the scaffold writer before switching the provider. Do not temporarily wire new PAY to
the existing table or use existing Vault as the reservation engine.

Required future verification includes exact-cent/bounds/negative/nonfinite and cap-adjacent Vault
round trips; 100 concurrent debits against a finite balance; opposing transfers and many-writer
hot accounts; both-leg rollback on recipient failure; deadlocks/timeouts/unknown commit and
restart at each journal boundary; same-key/different-payload replay; missing-account/open races;
authoritative offline preference/ignore and cross-server toggle ordering; nullable defaults;
queue rejection and disconnected callbacks; held-money spending and competing capture/release;
mail/kit receipt failure/replay; exact schema fixture imports, missing money, duplicate identities,
changed source, partial-run resume and complete rerun without any account/preference rewrite.
Run against actual SQLite and MySQL semantics, not H2 pretending to be both. The full implementation
tests and release benchmark are **not run or claimed here**; the limited SQL probe and its exact
invariant checks/results are recorded above. Release requires the correctness matrix, coordinated
restore/recovery rehearsal and the measured latency gate; unresolved unknown outcomes or a
failed latency lane deny release for that lane.
