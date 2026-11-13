# S12 mail live gate

**Live verified: PASS, 541 checks.** Only the supplied executed runs are included.

**Unit only:** 441 tests, zero failures/errors/skips; `test shadowJar` passed. Selected mail/preference repository tests also passed against disposable MySQL.

- **sqlite-spigot — PASS**: 128/128 assertions passed, 0 failure(s). Runtime: `/mnt/storage/.allium-validation/20261113/s12-mail/runs/pdc-sqlite-spigot-01`.
- **sqlite-paper — PASS**: 128/128 assertions passed, 0 failure(s). Runtime: `/mnt/storage/.allium-validation/20261113/s12-mail/runs/pdc-sqlite-paper-01`.
- **mysql-spigot — PASS**: 128/128 assertions passed, 0 failure(s). Runtime: `/mnt/storage/.allium-validation/20261113/s12-mail/runs/pdc-mysql-spigot-01`.
- **mysql-paper — PASS**: 128/128 assertions passed, 0 failure(s). Runtime: `/mnt/storage/.allium-validation/20261113/s12-mail/runs/pdc-mysql-paper-01`.
- **shared-mysql — PASS**: 29/29 assertions passed, 0 failure(s). Runtime: `/mnt/storage/.allium-validation/20261113/s12-mail/runs/pdc-shared-mysql-01`.

## M2 close-out: requested live recovery cases

Both cases below are **live verified**, not unit-only. Each ran in `sqlite-spigot`,
`sqlite-paper`, `mysql-spigot`, and `mysql-paper`: Spigot 1.20.1 and Paper 26.3 on both backends.
The assertion names in the manifest carry the corresponding lane prefix.

| Requested case | Exact assertion names after the lane prefix | Observed result |
| --- | --- | --- |
| Sender disconnects mid-send | `send disconnect/immediate reconnect keeps inventory and cancels old-generation PENDING gift` | A real client disconnects during the slow send and reconnects immediately. Its eight items remain in inventory; the pending gift is cancelled and nothing is delivered twice. This is before escrow removal, so it retains the items rather than exercising a post-removal refund. |
| Process dies mid-claim, before delivery | `deliberate mail.claim.halt-before-deliver crash exit=77`; `crash before delivery leaves inventory empty and gift claimable`; `pre-delivery recovery delivers exactly once` | `Runtime.halt(77)` abruptly terminates the actual JVM without shutdown hooks. After restart the unreceipted claim is released; claiming gives eight items exactly once. |
| Process dies mid-claim, after delivery | `deliberate mail.claim.halt-after-deliver crash exit=77`; `crash after delivery finishes DB from receipt without redelivery`; `post-crash repeated claim cannot duplicate` | The actual JVM terminates after the inventory and receipt are saved. Stopped native playerdata and the exact claim token are captured before restart. Recovery acknowledges the eight already delivered items, and another claim cannot duplicate them. |

The claim crashes are real abrupt process termination, not an in-memory state-machine simulation.
They use JVM halt, not an external SIGKILL; the separate pre-escrow send crash uses SIGKILL.
All four lanes passed both claim boundaries and the sender-disconnect case. No additional live
run was needed for this close-out.

Frozen artifact hash(es): `ac8d49a7a7fbf9273c8b6686a105b89c018e0a6778f0e8bebb722a7dc0ab0df4`.

Executed scope results:

- sqlite-spigot: sqlite-spigot text lifecycle/notifications — **PASS**.
- sqlite-spigot: sqlite-spigot policy — **PASS**.
- sqlite-spigot: sqlite-spigot completion — **PASS**.
- sqlite-spigot: sqlite-spigot gift/partial/reload/signed — **PASS**.
- sqlite-spigot: sqlite-spigot write-error inventory safety and cost policy — **PASS**.
- sqlite-spigot: sqlite-spigot session reconnect — **PASS**.
- sqlite-spigot: sqlite-spigot crash recovery — **PASS**.
- sqlite-paper: sqlite-paper text lifecycle/notifications — **PASS**.
- sqlite-paper: sqlite-paper policy — **PASS**.
- sqlite-paper: sqlite-paper completion — **PASS**.
- sqlite-paper: sqlite-paper gift/partial/reload/signed — **PASS**.
- sqlite-paper: sqlite-paper write-error inventory safety and cost policy — **PASS**.
- sqlite-paper: sqlite-paper session reconnect — **PASS**.
- sqlite-paper: sqlite-paper crash recovery — **PASS**.
- mysql-spigot: mysql-spigot text lifecycle/notifications — **PASS**.
- mysql-spigot: mysql-spigot policy — **PASS**.
- mysql-spigot: mysql-spigot completion — **PASS**.
- mysql-spigot: mysql-spigot gift/partial/reload/signed — **PASS**.
- mysql-spigot: mysql-spigot write-error inventory safety and cost policy — **PASS**.
- mysql-spigot: mysql-spigot session reconnect — **PASS**.
- mysql-spigot: mysql-spigot crash recovery — **PASS**.
- mysql-paper: mysql-paper text lifecycle/notifications — **PASS**.
- mysql-paper: mysql-paper policy — **PASS**.
- mysql-paper: mysql-paper completion — **PASS**.
- mysql-paper: mysql-paper gift/partial/reload/signed — **PASS**.
- mysql-paper: mysql-paper write-error inventory safety and cost policy — **PASS**.
- mysql-paper: mysql-paper session reconnect — **PASS**.
- mysql-paper: mysql-paper crash recovery — **PASS**.
- shared-mysql: shared-mysql protocol/codec/concurrency — **PASS**.

Scope limits:

- Mail body spying is outside scope.
- Pre-escrow crash is during slow-persist before row insert; not the PENDING-insert/before-take subwindow.
- Real protocol command/completion/chat/sound clients; console helper prepares inventory and observes DB/inventory.
- Shared MySQL concurrency uses same offline UUID on two separate playerdata directories.
- Write error is injected SQLException, not a physical MySQL outage.

Signed transfer assertions actually reached:

- sqlite-spigot: sqlite-spigot: signed tool identity/MAC/state survive actual gift escrow and claim — **PASS**.
- sqlite-paper: sqlite-paper: signed tool identity/MAC/state survive actual gift escrow and claim — **PASS**.
- mysql-spigot: mysql-spigot: signed tool identity/MAC/state survive actual gift escrow and claim — **PASS**.
- mysql-paper: mysql-paper: signed tool identity/MAC/state survive actual gift escrow and claim — **PASS**.
- shared-mysql: shared signed codec: 1.20.1 to 26.3 preserves verified tool identity/MAC/state with shared disposable keyring — **PASS**.
- Runtime configs and MySQL credentials are excluded from repository evidence.

Compatibility fixes established by the gate:

- Paper 26.3 stores player data separately from dimension folders. One guarded native accessor obtains the actual player-data directory; failed lookup disables mail.
- Native 1.20.1-to-26.3 item conversion reinterpreted a numeric-looking signed UUID as a number. Envelope format 2 retains a typed PDC sidecar and restores the original UUID/state/MAC without re-signing.
- Superseded failures remain under `/mnt/storage/.allium-validation/20261113/s12-mail/runs/`; they are not counted as final passes.

[Full assertion details and artifact manifest](evidence/s12-mail-manifest.json).
