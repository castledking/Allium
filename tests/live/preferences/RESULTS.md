# S03: player preferences gate

**PASS on 2026-11-07: 188 live checks, 0 failures**, on Spigot 1.20.1 and Paper 26.3, each
against SQLite and MySQL (MariaDB 10.11.16). All runs used Allium.jar SHA-256 `a5b6cb02…`.
Unit tests: 184 in total (50 core, 134 plugin), 0 failures. The core preference tests ran on both
SQLite and MariaDB. Versions, every check and the evidence rows are in the
[manifest](evidence/s03-manifest.json).

Run logs, summaries and the SQLite files are kept on persistent storage in
`/mnt/storage/.allium-validation/20261107/preferences-gate/`. The test database password is
redacted there.

| Lane | Backend | Client | Checks |
| --- | --- | --- | --- |
| Spigot 1.20.1 (3871-d2eba2c), Java 17 | SQLite (sqlite-jdbc 3.42.0.0) | minecraft-protocol 1.68.0, protocol 763 | 45 |
| Spigot 1.20.1 | MariaDB (mysql-connector-j 8.0.33), two servers | same | 49 |
| Paper 26.3-141, Java 25 | SQLite (sqlite-jdbc 3.49.1.0) | owned raw client, protocol 777 | 45 |
| Paper 26.3-141 | MariaDB (mysql-connector-j 9.2.0), two servers | same | 49 |

## What S03 adds

- **Storage:**
  - `allium_preferences` holds one row per UUID. Each field is nullable, and NULL means "follow
    the configured default".
  - `allium_schema` records each component's migration version. Allium refuses to start the
    module against a newer schema.
  - Writes are field-level patches, each in one transaction, and every patch increments a
    `version` column. Defaults are never written.
- **Bounded async storage:**
  - Work runs on per-UUID lanes, each a single thread with a bounded queue. A full queue fails
    the call instead of blocking it.
  - SQLite uses one lane, which is its serialized write path. MySQL uses four.
  - On shutdown, queued work drains before the connection pool closes.
- **Sessions:**
  - A load starts at pre-login, off the server thread, and waits there for up to
    `preload-wait-millis` (2000 by default).
  - A slower load leaves the session `LOADING`. A load that fails leaves it `FAILED`. In both
    states there are no effective preferences, so callers must wait or refuse rather than
    enforce defaults.
  - Each connection has its own generation, so a late load or quit from an older connection is
    discarded.
  - A session shows the last row the database confirmed, with any writes still in flight laid
    over it.
- **Fields and rules:**
  - Fields: `accept-messages`, `accept-payments`, `accept-teleport-requests`,
    `message-sound-volume`, plus its restoration state.
  - The sound rules (`/mfx volume|toggle|reset`) are implemented and tested here; the commands
    arrive in S07/S08.
  - The configured defaults are `preferences.defaults.*`, with `sounds.defaults.volume` as the
    sound fallback.

## How it runs

The test-only `PrefGate` fixture drives Allium's sessions from the console. It stands in for the
S07/S08 commands. The bot `PrefBot` connects as a real client. Each database check reads the row
independently: Python's `sqlite3` reads the lane's `allium.db`, or the `mariadb` client runs in
the container. Fault points (`-Dallium.fault-injection=true`) make a load take 6 s or fail.

## What each run showed (identical on all four)

- **Round trip:**
  - A first join was READY at join. It showed the configured defaults, and **no row existed**
    afterwards.
  - The test then stored `accept-messages false` and volume 0.4. NaN, Infinity, 1.5 and -0.1
    were rejected and wrote nothing.
  - A toggle muted and kept 0.4 as the restoration state. The row read back as stored, and a
    quit and rejoin wrote nothing.
- **Restart:**
  - The server restarted with `accept-payments` defaulting to false. The session held the same
    row, restoration state included.
  - The unset `accept-payments` followed the new default, but the database still held NULL. The
    restart and join wrote nothing (the row was identical, `updated_at` included).
  - A toggle restored 0.4 from the persisted restoration state.
  - `reset` cleared the override and the restoration state.
  - Toggling with no override muted and stored nothing to restore. Toggling back returned to the
    configured 1.0.
- **Reconnect during a slow load:**
  - With 6 s loads, the bot joined after the 2 s pre-login wait, still `LOADING` (2.2–2.4 s).
    There were no effective preferences, and a toggle was refused.
  - The bot reconnected 2.4–2.6 s into the first load, and the new session (generation 3) was
    also `LOADING`. A change made then was accepted.
  - When the loads finished:
    - The first load was **discarded as stale**.
    - The new session became READY with the **real row plus that one change**, not defaults.
    - The database changed by exactly that patch (version +1).
- **Failed load:**
  - The session was `FAILED`, with no effective preferences, a refused toggle, one log
    diagnostic and no write.
  - The next join loaded the real row.
- **Concurrent writes from two sessions:** 100 + 100 writes. Each writer flipped its own field
  (A `accept-messages`, B `accept-payments`) and both wrote the shared volume.
  - **MySQL:**
    - A second server ran on the same MariaDB, with the same player online on both.
    - Each server burst through its own session. The bursts overlapped: the two writers' last
      writes landed at versions 198/209 on Paper and 209/196 on Spigot.
  - **SQLite:**
    - Allium's session wrote alongside an independent store on its own connection to the same
      file (like a second process), released together.
    - Those bursts overlapped too: the last writes landed at versions 115/209 and 168/209.
  - **Every run:**
    - Both fields kept their writer's final value, and the shared volume held one writer's last
      value.
    - The version rose by exactly 200, so no update was lost.
    - After rejoining, each server's session matched the database.
- **Shutdown drain:**
  - `/stop` arrived while a slow load held the player's lane and 400 writes were queued behind
    it. 401 tasks were queued when Allium disabled.
  - All 400 writes were saved before the pool closed (version 209 → 609).

## Scope and limits

- **No in-game commands:** `/msgtoggle`, `/paytoggle`, `/tptoggle` and `/mfx` belong to
  S07/S08, which also wire enforcement and sound playback to these sessions.
- **Offline players:** offline lookups (for `/pay` and others) are not built yet. Sessions exist
  for online players only, plus logins that have not yet joined. Those pre-join sessions are
  ended after 60 s if the player never joins.
- **Cross-server freshness:** writes from another server are not pushed to an open session. A
  session sees them through its own next write (each write returns the full row) or on the next
  join. No update is lost either way, because writes are field-level patches.
- **SQLite deployment:** sharing one SQLite file between servers is not a supported deployment.
  The external-writer case only proves that the file's write path tolerates a second writer.

## Rerun

```sh
./gradlew build
python3 tests/live/preferences/run.py --lane spigot-1.20.1 --backend sqlite --run-name <name>
python3 tests/live/preferences/run.py --lane spigot-1.20.1 --backend mysql --run-name <name>
python3 tests/live/preferences/run.py --lane paper-26.3 --backend sqlite --run-name <name>
python3 tests/live/preferences/run.py --lane paper-26.3 --backend mysql --run-name <name>
```

The runs use owned loopback ports 25680–25681. They start servers with
`-Dallium.fault-injection=true`, delete their copied server files afterwards, and exit nonzero
on any failed check. The MySQL runs need a disposable MariaDB on 127.0.0.1:33306, and they
empty its `allium_s03` database first:

```sh
openssl rand -hex 16 > /tmp/allium-s03-mariadb.pw
docker run -d --rm --name allium-s03-mariadb -p 127.0.0.1:33306:3306 --tmpfs /var/lib/mysql:rw \
  -e MARIADB_RANDOM_ROOT_PASSWORD=1 -e MARIADB_DATABASE=allium_s03 -e MARIADB_USER=allium \
  -e MARIADB_PASSWORD="$(cat /tmp/allium-s03-mariadb.pw)" mariadb:10.11
```

Core tests run against it with
`ALLIUM_TEST_MYSQL_URL=jdbc:mysql://127.0.0.1:33306/allium_s03?user=allium&password=…`.
