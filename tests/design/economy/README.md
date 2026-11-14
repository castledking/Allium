# S14 single-commit JDBC prototype — not production code

Question: can one transaction insert a COMMITTED operation, conditionally debit,
credit and record both entries, then resolve a lost caller acknowledgement by
re-inserting the same operation ID? What does each durability option cost here?

Run on the existing disposable validation setup:

```sh
python3 tests/design/economy/run.py
```

The script uses Java 17, the pinned JDBC drivers under
`/mnt/storage/.allium-validation/20261113/s14-revision/deps`, scratch SQLite files
and only the owned `allium-gate-mysql` container on loopback 25733. Credentials are
read privately from the validation environment file, never command arguments or
reports. It creates/drops a unique MySQL database. No plugin or Vault is loaded.
The copied Allium jar supplies SLF4J classes to the SQLite driver, not engine code.

The probe surfaces samples, environment, invariant counts and every recovery
outcome in JSON. FULL, NORMAL and durable MySQL are measured separately. NORMAL
can lose recent acknowledged transactions after OS/power failure; this experiment
does not inject power failure or select an engine default.

Connection faults are deterministic: native connection close for embedded SQLite,
administrative KILL CONNECTION for MySQL. After-commit faults discard a successful
inner JDBC commit return and throw to the caller. This models application-level
lost acknowledgement; it is not a real network packet-loss test. Recovery first
blocks behind the original transaction. After rollback it applies once; after
commit it gets a uniqueness error, validates the original payload and two entries,
and makes no new balance change. SQLite BUSY is explicitly unresolved, not duplicate.

`MoneyBoundary.java` is a design fixture for the Vault-only 1e-6 major-unit snap.
Core/command decimal inputs remain strict. Its zero result still needs the engine's
positive/minimum-amount validation. The prototype is retained at the user's request;
it must not be promoted into the plugin as an engine implementation.

See [the design](../../../docs/economy-design.md) for measured options and phase/release
gates. The compact [evidence](evidence/single-commit.json) preserves raw samples and
provenance; scratch database artifacts stay outside Git.
