# Legacy import live gate

**PASS on 2026-11-18: 56 live checks, 0 failures.** Spigot 1.20.1 (`spigot-import-i4`, 28 checks) and
Paper 26.3 (`paper-import-i4`, 28 checks), SQLite, both on Allium.jar
`7c6696a555ebf20063fbca14a9d985c712535e148adc74bad380569ae15ec6f0`. Every check ran on both lanes.
The [manifest](evidence/manifest.json) lists them; runs are in
`/mnt/storage/.allium-validation/20261118/staff/runs/`.

- **Source:** `plugins/Allium/database/sfcore.mv.db` built with legacy Allium's own table
  definitions (schema 9) and fixture rows only, no production data, and created as user `sa` with
  no password, as legacy Allium's connection pool did. On Spigot it is written through
  the shaded legacy jar's relocated H2 (`codes.castled.allium.libs.h2`), as a real legacy server
  wrote it; on Paper through a plain `h2-2.1.214.jar`. Each lane reads it with that same driver from
  `plugins/Allium/import/`.
- **Clients and fixture:** the staff gate's real protocol clients and `StaffGate` (which reads the
  travel tables and the ledger; it writes nothing). Everything is driven from the console.

| Step | What ran |
| --- | --- |
| Wrong driver | the other H2 build is named in `legacy-allium.yml`; the dry run is refused with the error 90030 explanation |
| Dry run | 3 homes planned, 2 blocked (a name with a space, a world that is not loaded), 1 superseded row skipped; the newest of two rows wins; `Farm` becomes `farm`; warps: `spawn` planned, `shop` a conflict with this server's warp, `bad warp` blocked; 2 logout positions; unnamed homes, older logouts and DEATH rows counted; driver and H2 2.1.214 recorded; the source hashed and unchanged; the max-homes snippet; the summary lists what needs a decision |
| Apply refused | with players online; then after they left, because PlayerBot's quit saved a logout position and the plan changed |
| Apply | a new dry run keeps this server's newer logout; apply imports the 3 homes, `spawn` and the offline-only player's logout; this server's `shop` and PlayerBot's logout are untouched; the ledger holds 5 records and the run; the legacy file is unchanged |
| Replay | the same run is refused; a later dry run marks everything `replayed` |
| Clients | `/homes` lists `base` and `farm`; `/home base` and `/warp spawn` land on the imported coordinates |

Server logs: no exceptions or SEVERE lines on either lane.

## What the gate found

- **H2 only opens a database with the login that created it.** Legacy Allium connected as `sa`;
  the first import connected with no user and a real legacy database refused it (H2 error 28000,
  reported as "(Message 28000 not found)" by the shaded jar). The earlier fixtures were created
  without a user, so the gate passed on jar `6f63c283…`; rebuilt as `sa`, that jar fails the same
  way (`spigot-import-i3-repro`). The import now logs in as `sa` by default, takes `user`/`password`
  from `legacy-allium.yml`, and explains a refused login.

- **A legacy database opens only with the H2 that wrote it.** H2 2.x writes its data-type class
  names into the file, and legacy Allium's shade step renamed H2's package, so a plain `h2` jar
  cannot read a real legacy database (and the legacy jar cannot read a plain-H2 file). The first
  Spigot run hit this; the import now explains error 90030 instead of the shaded jar's
  "(Message 90030 not found)", and the docs require the legacy jar.
- **Paper 26.3 loads `world_nether` with `allow-nether=false`.** The fixture's "unloaded world"
  therefore resolved on Paper; the importer was right, the fixture now uses a world neither lane has.

## Not covered

- MySQL as the destination (unit tests run SQLite; the SQL is shared with the travel repository).
- The login fence while apply runs: apply finishes before a client could connect.
- A production legacy database.

## Rerun

```sh
./gradlew build
python3 tests/live/legacy-import/run.py --suite spigot --allium-jar <jar> --sha256 <sha256> --run-name <name> \
  --run-root /mnt/storage/.allium-validation/YYYYMMDD/staff/runs
python3 tests/live/legacy-import/run.py --suite paper --allium-jar <jar> --sha256 <sha256> --run-name <name> \
  --run-root /mnt/storage/.allium-validation/YYYYMMDD/staff/runs
```

Inputs (hash-checked by the gate): `/mnt/storage/.allium-validation/infra/tools/h2-2.1.214.jar` and
`legacy-allium-0.2.20a-shaded.jar`. Owned ports 25860 (Spigot) and 25861 (Paper), shared with the
staff gate.
