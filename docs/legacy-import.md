# Importing legacy Allium homes, warps and logout positions

`/allium import legacy` reads a legacy Allium database (`plugins/Allium/database/sfcore.mv.db`) and
imports its homes, warps and last logout positions into this Allium. It follows the
[importer contract](importers-design.md): a dry run first, a reviewed plan, an empty server for
apply, and a ledger that makes reruns harmless. Permission: `allium.import` (operators).

## What you need

- **The legacy database**, where legacy Allium left it. Stop the legacy server first: the import
  refuses a database another process holds open. Another path can be set in
  `plugins/Allium/import/legacy-allium.yml` (`source:`, relative to `plugins/Allium`).
- **The H2 driver that wrote it**: put the legacy Allium jar you ran (the full build, about 6 MB,
  which contains `codes/castled/allium/libs/h2/`) in `plugins/Allium/import/`. Allium does not bundle
  H2 (it would push the plugin past Hangar's 5 MB limit). H2 stores its own class names inside the
  database file, and legacy Allium's copy of H2 renamed them, so **a plain `h2` jar cannot read a
  legacy database**. Use the plain `h2-2.1.214.jar` only for a file a plain H2 wrote. A mismatched
  driver is refused with that explanation (H2 error 90030). With several jars in `import/`, name
  the one to use in `legacy-allium.yml` (`driver: import/<file>.jar`).
- **The same worlds.** Legacy stored world names. Each resolves to a loaded world of the same name,
  or to the world you map it to in `legacy-allium.yml`:

  ```yaml
  worlds:
    old_survival: world
  ```

  A home or warp in a world that is neither loaded nor mapped is **blocked**, never moved to
  another world.

## Dry run

```text
/allium import legacy
```

The database is copied to `plugins/Allium/import/legacy/<run>/source.mv.db` and only the copy is
opened, read-only. Nothing else changes. The run folder holds:

| File | Contents |
| --- | --- |
| `summary.txt` | Counts per domain, notes, and every record that needs a decision |
| `records.jsonl` | One line per record: status, reason, legacy row, destination and payload hash |
| `plan.json` | Source path, SHA-256 and size, the driver and H2 version, the world map, counts and the plan hash |
| `staff-overrides.yml` | Legacy staff-set home limits as a `homes.staff-overrides` snippet (only when there are any) |

Run the dry run **after the last player has left**: a player quitting saves a logout position,
which changes the plan and makes apply refuse it.

## Apply

```text
/allium import legacy apply <run>
```

Apply is refused while any player is online, and logins are refused while it runs. It copies the
database again and refuses unless the source has the same SHA-256 and the plan the same hash as
the reviewed dry run. Each destination row and its ledger row are written in one transaction, in
chunks of 200. Afterwards warps are reloaded. A run is applied once; a later dry run marks
everything already imported as `replayed`. Results: `apply-summary.txt` and `apply.jsonl`.

## What is imported, and how

| Legacy data | Destination | Rules |
| --- | --- | --- |
| `player_locations` HOME rows | Homes | Legacy added a row each time a home was re-set; the newest (timestamp, then row id) wins and the others are reported as superseded. Names are lowercased; a name outside 1-32 of `A-Z a-z 0-9 _ -`, or two legacy names of one player that become the same name (`Base` and `base`), are blocked. Rows without a name (legacy never used them) are counted only. The current rank limit does not apply; the hard cap of 1000 does. |
| `warps` | Warps | Same name rules. Access stays `allium.warps.<name>`, which is also what legacy checked (`is_public` was never used for access). The creator is kept in the report only. |
| `player_locations` LOGOUT rows | Logout positions (`/tpoffline`) | The newest row per player. A position this server already saved is newer and is kept. |
| `player_max_homes` | `staff-overrides.yml` snippet | Never applied: legacy's value was the limit, Allium's override is a minimum. `-1` meant "use permissions" and is left out. |
| DEATH, LOGIN, TELEPORT, COMMAND rows | Nothing | Counted in the report. |

A destination that already holds the **same** location is `present` and is recorded in the ledger.
A **different** home or warp of the same name is a `conflict`: it is kept, and the legacy one is
not imported. The ledger key is the player and name (or the warp name, or the player for logout
positions); the same key with different data later is a conflict too.

Everything else in the legacy database (balances, mail, inventories, preferences, nicknames) is
outside this importer; see the [importer contract](importers-design.md).

## Verification

**Unit (real H2 2.1.214 and SQLite):** the reader on legacy's own table definitions, read-only
access to a copy, planning rules, and apply, replay and adopt through the ledger.

**Live:** the [legacy import gate](../tests/live/legacy-import/RESULTS.md) on Spigot 1.20.1, with a
database written by the legacy jar's relocated H2, and on Paper 26.3, with a plain H2. It covers the
dry run, the reports, both apply refusals, apply, replay, a mismatched driver, and real clients
using `/homes`, `/home` and `/warp` on the imported data. Not run live: MySQL as the destination,
the login fence while apply runs (apply finishes before a client could connect), and a real
production database.
