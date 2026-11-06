# Native provider lane checks

`LaneGate` validates native item creation/identity, HMAC/state preservation through
Bukkit YAML serialization, actual provider reload events, and restoring that YAML
after a real server restart. It uses no player proxy. It does **not** establish
client completion, player inventory retention, native updater retention, or click,
drop and pickup behavior; the other live fixtures cover those separately.

The fixture has no gameplay effects. Commands accept only the console. Never
install it on a production server.

## Inputs and execution

Compile `plugin/gate/LaneGate.java` with JDK 17, `--release 17`, the Spigot 1.20 API
and `build/libs/Allium.jar`. Package the classes with `plugin/plugin.yml` into
`LaneGate.jar`. It has no native provider compile dependency.

`run.py` takes a JSON manifest, for example:

```json
{
  "name": "paper-1.21.8-nexo-ce-exact",
  "directory": "/tmp/allium-s05-20261106/lanes/runs/paper-1.21.8-nexo-ce-exact",
  "port": 25630,
  "java": "/usr/lib/jvm/java-21-openjdk/bin/java",
  "server": "/absolute/path/paper-1.21.8-60.jar",
  "allium": "/absolute/path/Allium.jar",
  "fixture": "/absolute/path/LaneGate.jar",
  "template": "/absolute/path/stopped-disposable-provider-fixture",
  "providers": ["nexo", "craftengine"],
  "nexo": "/absolute/path/full-nexo-plugin.jar",
  "craftengine": "/absolute/path/craft-engine-paper-plugin-26.9.1.jar"
}
```

Use a fresh directory below `/tmp/allium-s05-YYYYMMDD/lanes/runs/`. The runner
refuses an existing directory and binds only `127.0.0.1` on reserved ports
25630–25649. Check that other local tasks are not using the chosen port.

The template is a **disposable** fixture whose Nexo pack generation/dispatch is
disabled before boot. The runner copies only provider settings and cached native
libraries. It generates minimal item definitions, a flat world and a fresh Allium
keyring; it never copies worlds, player data, native databases or keyrings.
Optional `library_cache` points to an immutable Maven `libraries/` directory;
only dependency files are copied for download reuse, not plugin/player state.
An owned `/mnt/storage/.allium-validation/YYYYMMDD/SUITE/runs/CASE` directory is
also accepted, avoiding temporary-filesystem quotas for larger matrices.

```sh
python3 tests/live/providers/lanes/run.py /absolute/path/manifest.json
```

The Nexo/CE driver starts the server, waits for native readiness, saves signed
items with `charges=2`, rereads and verifies them, changes fixture definitions,
runs native reload commands and waits for both real events and readiness. It
verifies the old serialized items, stops the server, boots again, and restores
and verifies the checkpoint. Fresh native definitions change carrier material;
there is no Allium refresh or re-sign operation.

The runner prepares minimal Nexo, CE and Oraxen fixture definitions. Oraxen
uses an explicit `oraxen_settings` path to its verified settings. Migration is
disabled before boot when both Oraxen and Nexo are present.

Each run records input SHA-256 values, scope and pass/failure in `result.json`,
full raw output in `complete-server.log`, and filtered review evidence in
`evidence.log`. The driver stops its child JVM on failure. A forced stop prevents
a PASS. Keep failures as failures; a server reaching READY alone is insufficient.

Event assertion failures are sticky: callbacks log `S05-LANE-FAIL`, never advance
the successful-event counter, and block every later PASS. The native failure-path
check sets `test.force-event-failure: true` only in a disposable LaneGate config;
after the actual CE callback fails, a subsequent console probe must refuse PASS.
See `../evidence/lane-event-failure-latch-20261106.log`. All published positive
lane traces also contain the individual native readiness/signature assertions.

Published results are summarized in `../evidence/paper-lanes-20261106.json` and
the corresponding filtered logs. Paper artifacts came from the
[official downloads API](https://docs.papermc.io/misc/downloads-service/), with
SHA-256 checked against its metadata. Paper 1.21.5 build 114 is labelled ALPHA by
that API; its test result is not a stable-server recommendation.
