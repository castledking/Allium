# S12 mail live gate

**Live verified: PASS, 541 checks.** Only the supplied executed runs are included.

**Unit only:** 441 tests, zero failures/errors/skips; `test shadowJar` passed. Selected mail/preference repository tests also passed against disposable MySQL.

- **sqlite-spigot — PASS**: 128/128 assertions passed, 0 failure(s). Runtime: `/mnt/storage/.allium-validation/20261113/s12-mail/runs/pdc-sqlite-spigot-01`.
- **sqlite-paper — PASS**: 128/128 assertions passed, 0 failure(s). Runtime: `/mnt/storage/.allium-validation/20261113/s12-mail/runs/pdc-sqlite-paper-01`.
- **mysql-spigot — PASS**: 128/128 assertions passed, 0 failure(s). Runtime: `/mnt/storage/.allium-validation/20261113/s12-mail/runs/pdc-mysql-spigot-01`.
- **mysql-paper — PASS**: 128/128 assertions passed, 0 failure(s). Runtime: `/mnt/storage/.allium-validation/20261113/s12-mail/runs/pdc-mysql-paper-01`.
- **shared-mysql — PASS**: 29/29 assertions passed, 0 failure(s). Runtime: `/mnt/storage/.allium-validation/20261113/s12-mail/runs/pdc-shared-mysql-01`.

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
