# S12 live mail gate

Requires a **parent-confirmed frozen Allium jar and SHA-256**. This harness never runs Gradle,
changes production code, commits, or pushes. It uses only `127.0.0.1:25720–25725`, the owned
`allium-gate-mysql` container on port 25733, and disposable runtime directories. No 25565 access.

The Java 17 console-only helper sets up inventories, permissions, vanish, mute, fault hooks,
a counting non-debiting CostPolicy, and delivery-event cancellation. It observes inventories, receipts and raw
mail rows. Tested mail commands and completions come from real offline protocol clients copied
from S11/S07 infrastructure. Paper 26.3 uses the existing protocol-777 client and a server-generated
packet catalog; Spigot uses the persistent `minecraft-protocol` dependency.

```sh
python3 tests/live/mail/run.py --suite sqlite-spigot --run-name sqlite-spigot-01 \
  --allium-jar /absolute/frozen.jar --sha256 <parent-confirmed-sha256> --check
# --prepare-only compiles the helper, without starting a server.
# Remove --check for the live gate, only after the parent confirms the freeze.
python3 tests/live/mail/run.py --suite sqlite-spigot --run-name sqlite-spigot-01 \
  --allium-jar /absolute/frozen.jar --sha256 <parent-confirmed-sha256>
python3 tests/live/mail/run.py --suite sqlite-paper --run-name sqlite-paper-01 \
  --allium-jar /absolute/frozen.jar --sha256 <parent-confirmed-sha256>
python3 tests/live/mail/run.py --suite mysql-spigot --run-name mysql-spigot-01 \
  --allium-jar /absolute/frozen.jar --sha256 <parent-confirmed-sha256>
python3 tests/live/mail/run.py --suite mysql-paper --run-name mysql-paper-01 \
  --allium-jar /absolute/frozen.jar --sha256 <parent-confirmed-sha256>
python3 tests/live/mail/run.py --suite shared-mysql --run-name shared-mysql-01 \
  --allium-jar /absolute/frozen.jar --sha256 <parent-confirmed-sha256>
```

SQLite uses 25720/25721. Full single-server MySQL suites use 25724/25725 with the same cases,
including all four crash boundaries. The shared MySQL test uses Spigot 25722 and Paper 25723, two
independent playerdata directories, and one unique disposable database. Shared-server checks capture a winning CLAIMING token and
owner while paused and require the losing client to refuse before the winner delivers. The same offline UUID
claims simultaneously on both servers. Full logs and per-suite manifests stay outside the repo.
The gate takes an exclusive file lock for its owned port range and checks every port before use.

Normal server/client nonzero exit and forced termination fail the run. Deliberate server crashes
are accepted only in the explicit crash case, with the expected exit (-9 before persistence or 77
for an armed crash hook) and a stopped-server DB snapshot. Client disconnects from those deliberate
crashes are recorded. Cleanup errors also fail the run. No unrun case is a pass.

Scope boundaries: the pre-escrow crash covers the slow-persist window **before insertion**, not
the PENDING-insert/before-take subwindow. Write errors use the public injected SQLException hook,
not physical database outages. Signed-tool transfer is tested within each storage/server suite and across shared MySQL from
1.20.1 to 26.3 using a shared disposable signing keyring; reverse-version refusal uses vanilla gifts. Mail-body spying
is outside scope. Cross-server online notifications are not claimed.

The harness copies helper/client sources per run and verifies source hashes remain unchanged.
`playerdata.cjs` independently reads stopped native inventory and receipts before restart;
these are matched to the exact target database ID/token, not inferred only from post-restart totals.

`manifest.json` records check details, failures, executed scopes, jar/helper/client/server hashes,
client dependency version, and crash snapshots. The disposable MySQL config contains its test
password and is mode 0600; do not copy runtime configs into the repository or evidence.

After live runs, generate the proportional report and aggregate manifest:

```sh
python3 tests/live/mail/report.py --unit-summary /absolute/unit/summary.json \
  /absolute/sqlite-spigot/manifest.json \
  /absolute/sqlite-paper/manifest.json /absolute/mysql-spigot/manifest.json \
  /absolute/mysql-paper/manifest.json /absolute/shared-mysql/manifest.json
```

Empty completion responses are validated with transaction-ID-correlated positive-control requests; Spigot omits packets for empty suggestions. Offline assertions wait for the server to observe quit. Commands wait for read-only mail lock/recovery readiness. Temporary limit-bypass permissions are restored per connection; rate/mailbox limits are not covered by this gate.
