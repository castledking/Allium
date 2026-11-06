S05 disposable Spigot validation, 2026-11-06, source HEAD
`ddfeeca5a05508291b16448e96c101578b9bff2a`. Java `25.0.5.0.3`.

Spigot 26.3 / `4660-Spigot-766de51-bd6d9dc`: **PASS**, all optional
providers absent. Allium enabled; LaneGate `expected: []` confirmed all three
adapters ABSENT; native plugin listing contained only Allium and LaneGate.
Console probe passed and normal stop exited 0. This repeats the previously
unrepeated S05 baseline. Evidence: [spigot-26.3-absent-20261106.log](../evidence/spigot-26.3-absent-20261106.log).

Spigot 26.2 / `4645-Spigot-8db49a2-c4d1107` with exact **CraftEngine Bukkit
26.9.1**: **PASS** on fresh attempt `r2`. Real CE native loading event returned
PROVIDER_LOADING, followed by READY with one fixture item. Creation, identity and
privileged signature verified. Real `ce reload` repeated loading/ready checks;
serialized ItemStack signature, UUID and `charges=2` stayed intact. A complete
stop/restart reread the checkpoint and verified the same values and native
identity. Both JVMs disabled Allium/CE normally and exited 0. No quota/save or
provider errors in the passing log. Nexo/Oraxen were absent. Evidence:
[spigot-26.2-ce-26.9.1-20261106-r2.log](../evidence/spigot-26.2-ce-26.9.1-20261106-r2.log).

These are headless checks. The checkpoint is a YAML-serialized ItemStack, not an
actual player inventory. The fixture initializes `charges=2`; its log wording
“spent state unchanged” does not establish a player spending charges. No actual
player joined, no core-five player commands were exercised, and no inventory
updater triggers, stack quantities, resource-pack delivery or visuals were
validated. Exact CE 26.9.1 support on Spigot 26.3 is **not** claimed. S05's broader
gate remains open; this work does not begin S06.

The initial CE 26.2 attempt was invalidated by `/tmp` disk quota errors before
any LaneGate/provider result. It is an infrastructure failure, not a provider
failure. Its filtered evidence retains input hashes, the first error, and the
failed harness result; the complete raw trace is retained under the owned
disposable root. [Infrastructure evidence](../evidence/spigot-26.2-ce-26.9.1-20261106.log).
An earlier Python preparation failure (missing plugins directory, before Java
started) is recorded in [preparation evidence](../evidence/spigot-preparation-20261106.log).

Input SHA-256:

- Final `build/libs/Allium.jar`: `4e90e660141964643ac32ee8580b74082ffec2460e3ca0a85428db4e88c1c14e`.
- Spigot 26.3 launcher: `bba50623f55ed5feb2f527220126ad1e0eb72cce4140819cced96df4552ebe4f`.
- Spigot 26.3 extracted server: `9581f58262b6097c84f3eeef3dde8d482bb5bc6ba93222ba86d6ba0f89601a18`.
- Spigot 26.2 launcher: `0dc80f98d5d2eec4782d2f351c39ad4bb44cc382954be6e3a16676df880ee605`.
- Spigot 26.2 extracted server: `8381d6eec7a79ae2e4aede0c49c4a77f043c8c46ba2bb95abe058fc1c0c5fd00`.
- CE Bukkit 26.9.1 full plugin: `389da875c3c4a0667d5914ffb58478c67a8ecc06670d88f36b6ff58b99c1c1c1`.
- Supplied LaneGate fixture: `f2f0380864231d872cda30346501e3985a3ac6fe5ab4f4df4747576c10d1c4e7`.

Server provenance is the user-supplied local BuildTools launchers. The 26.3
copied immutable extracted bundle matches its launcher's manifest; every copied
binary was hashed. The cached 26.2 BuildTools log records BuildTools #200,
`--rev 26.2 --remapped`, successful July 30 2026. All packaged 26.2 binaries match
the bundle manifests. CE comes from official Modrinth version `prX5nH7G` at
`https://cdn.modrinth.com/data/tRX6FMfQ/versions/prX5nH7G/craft-engine-bukkit-plugin-26.9.1.jar`;
the full jar matches its recorded SHA-512. That version lists Bukkit/Spigot and
Minecraft 26.1–26.2, including the tested 26.2. Exact argv, Java version, plugin
hashes, configuration hash and every server dependency hash are in the logs.

Harnesses are [run_absent.py](run_absent.py) and [run_ce.py](run_ce.py), with the
minimal native item in [ce-item.yml](ce-item.yml). Commands used:

```sh
python3 tests/live/providers/spigot/run_absent.py
python3 tests/live/providers/spigot/run_ce.py --attempt r2
```

They use task-specific paths and refuse existing run directories. For a further
CE retry, select a new alphanumeric `--attempt`. Only disposable fresh worlds and
fresh locally generated signing keys were used. No world/player/secret was
copied. CE workflows/packs/delivery were disabled for this S05 check. Servers
bound loopback ports 25625/25626; 25627 was reserved for CE if needed. All owned
JVMs are stopped and all reserved ports 25625–25629 were checked closed.
No adapters, shared fixtures, build files, root Gradle files or project docs were
edited.
