# Measure on SF's host against a disposable copy

This harness starts its own loopback-only server, never attaches to a running server. Do not copy production player data or secrets. Use an empty `runs` directory on the same local filesystem intended for SF. The Allium provider exists only inside this test copy; these measurements do not authorize release. No alternate economy database is created. Python 3 + PyYAML, Node with minecraft-protocol, Java 17 and Java 25 are needed. The harness installs its test-only Vault API host, not a separate Vault distribution.

Provide `--infra DIR` containing `api/spigot-api-1.20.1-R0.1-SNAPSHOT.jar`, `servers/spigot-1.20.1.jar`, `servers/paper-26.3-141.jar` (Paper bootstrap library catalogue), and `client/node_modules/minecraft-protocol`. `--paper-template DIR` contains the matching Paper `libraries/` and `versions/26.3/paper-26.3.jar`. Copy the supplied compatible infrastructure or prepare the matching dependencies locally. `--vault-api` points at the unmodified VaultAPI 1.7.1 jar. Ports 25800–25802 must be unused; no production port is touched. All generated server configuration uses SQLite FULL and offline-mode for disposable test identities.

```sh
tools/measure-economy-latency --suite spigot \
  --allium-jar /absolute/copy/Allium.jar --sha256 SHA256_OF_THAT_JAR \
  --infra /absolute/infra --paper-template /absolute/paper-template \
  --java17 /absolute/jdk17 --java25 /absolute/jdk25 \
  --vault-api /absolute/VaultAPI-1.7.1.jar \
  --run-root /absolute/disposable/runs --run-name sf-spigot-full --duration 600
```

Then run `--suite paper` with a new run-name, sequentially, with the same jar/hash. Keep the generated `manifest.json`, `realistic-raw.json`, server/client logs, frozen helper sources and hash manifest. `manifest.json` includes machine, disk, mount/filesystem, call percentiles (p999 means p99.9), wall/CPU economy cost per tick and count of economy ticks exceeding 5ms. Source and artifact hashes are checked before boot and after shutdown. The recorded `elapsedSeconds` includes background-worker drain after the final measured tick; raw samples and tick counts describe the active workload. No healthy-call errors should be accepted.

The foreground schedule is 5 writes/20 ticks (3 together, then 1 at tick 10 and 1 at tick 15), 50 reads/20 ticks, for at least 12,000 ticks and 600 elapsed seconds. This is nominal 5 writes and 50 reads per second at 20 TPS; lag reduces achieved rates, which must be reported. There is no warm-up exclusion. Reads are real synchronous Vault getBalance calls; writes alternate one-cent deposit/withdraw on one existing account. Read and write latency are reported separately so reads cannot hide write tails. Background defaults are preference changes every 2s, moderation audits every 10s, mail every 15s; these are assumed representative low-traffic rates, not observed SF traffic. Repository call time is additional to each interval. To tune the fixture's rates, supply JVM system properties `allium.bench.preference-ms`, `allium.bench.moderation-ms`, `allium.bench.mail-ms` using JAVA_TOOL_OPTIONS (minimum 100ms); preserve those settings with the evidence. Avoid unrelated JVM flags and never include secrets in that variable.

Run stress separately with `python3 tests/live/economy/run.py --stress-only --host-copy` plus the same explicit infrastructure/artifact/run-root arguments and a new run-name. It performs setup and the labelled 10-writes/tick stress workload. Omitting both workload switches retains the existing correctness/crash gates followed by stress. Stress is diagnostic, not a release gate. SF's actual host needs its own result and an explicit user-approved realistic release budget; development-host timings are not a substitute.
