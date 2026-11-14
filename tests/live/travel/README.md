# S15 live homes and warps gate

PREPARE ONLY until the parent explicitly freezes the S15 jar and supplies its exact SHA-256.
The parent-confirmed command/config/service contract and current source message mapping are in
`contract.json`. Live execution additionally requires `--freeze-confirmed`, the jar path and its SHA-256.
No preparation result counts as a passed live case.

The fixture and clients reuse S13's API, dependencies, native `STREAM_CODEC` proofs, runtime packet
catalog, and process cleanup patterns. Spigot 1.20.1 uses Java 17 and cached minecraft-protocol
1.68.0. Paper 26.3-141 uses Java 25, the exact bundler library catalog, and the supplied template
`/mnt/storage/.allium-validation/20261106/supplied-lanes/runs/paper-26.3`.
No dependencies or server jars are downloaded.

Only `127.0.0.1:25750` and `127.0.0.1:25751` are allowed, checked together under an exclusive
lock before preparation and checked again before each server boot. All external writes are
under `/mnt/storage/.allium-validation/20261113/s15-travel`. Preparation compiles the Java 17
helper, syntax-checks both clients, verifies the exact Paper libraries and Java runtimes, and
snapshots source inputs. It starts no server or bot. It never uses port 25565, invokes Gradle,
changes production files, commits, or delegates nested agents.

Exact preparation commands:

```sh
python3 tests/live/travel/run.py --suite spigot --run-name prepare-api-spigot --prepare-only
python3 tests/live/travel/run.py --suite paper --run-name prepare-api-paper --prepare-only
python3 tests/live/travel/report.py \
  /mnt/storage/.allium-validation/20261113/s15-travel/runs/prepare-api-spigot/manifest.json \
  /mnt/storage/.allium-validation/20261113/s15-travel/runs/prepare-api-paper/manifest.json
```

Exact live commands, **only after parent freeze and message-contract pin** (replace the two
angle-bracket arguments with the parent's concrete values):

```sh
python3 tests/live/travel/run.py --suite spigot --run-name final-spigot-02 \
  --freeze-confirmed --allium-jar <absolute-frozen-S15.jar> --sha256 <parent-frozen-sha256>
python3 tests/live/travel/run.py --suite paper --run-name final-paper-01 \
  --freeze-confirmed --allium-jar <absolute-frozen-S15.jar> --sha256 <parent-frozen-sha256>
python3 tests/live/travel/report.py \
  /mnt/storage/.allium-validation/20261113/s15-travel/runs/final-spigot-02/manifest.json \
  /mnt/storage/.allium-validation/20261113/s15-travel/runs/final-paper-01/manifest.json \
  --failed-attempt /mnt/storage/.allium-validation/20261113/s15-travel/runs/final-spigot-01/manifest.json
```

The runner has twelve named cases: default/multiple home CRUD; maximum permission/staff grants;
home snapshot/expiry/reconnection confirmations; named warp CRUD/permissions; warp snapshot and
row-revision confirmations; filtered and bounded cached completion; shared warmup/movement/
cooldown behavior; warp permission/revision rechecks during warmup; unsafe/unloaded saved worlds;
permissions/vanish/full generic privacy responses; restart persistence; final service/client health.
Every selected case must execute and pass, and every server/client process must exit zero normally.

Commands and completions go through real standalone clients `TravelA`, `TravelB`, `TravelStaff`.
The single console-only helper only prepares terrain/positions/worlds/permissions/vanish and
observes state, effective home limits, executor task counters, events, resolved messages and SQL. It never invokes a tested travel operation API.
Each command is correlated with its wire send and a fresh server command event. Each successful
travel requires current world/XYZ plus a fresh non-fixture uncancelled PLUGIN event matching its
destination. Refusals require a valid bounded wire response and no physical event; privacy compares
complete normalized raw JSON/NBT components and overlay/interactivity flags. Completion snapshots require unchanged travel executor task counts; empty legacy completion
responses use a positive-control fence. Reloads require acknowledgement and the same service;
restart checks include independent stopped SQLite rows. Actual Allium scheduler failures and
shutdown storage failures remain failures.

`RESULTS.md` and `evidence/s15-travel-manifest.json` are the only aggregate results. Original
per-lane logs, frozen helpers/clients, hashes and manifests remain outside the repository. No
supplement or retrospective audit is planned. SQLite and the two supplied lanes are the scope;
pet teleport, offline restore, MySQL, Folia, provider-plugin and cross-server behavior are excluded.

The original `final-spigot-01` remains FAIL: its default-true premise incorrectly treated
Bukkit `isPermissionSet` as an explicit-attachment detector. Commands and effective permissions
worked, and every process exited zero. The corrected full rerun checks registered TRUE defaults,
effective permission, non-operator status and the actual fixture attachment node map. Both final
lanes use that same corrected source snapshot; no retained attempt is relabelled.
