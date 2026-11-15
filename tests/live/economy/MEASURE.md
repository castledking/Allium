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

Then run `--suite paper` with a new run-name, sequentially, with the same jar/hash. Keep the generated `manifest.json`, `realistic-pooled-raw.json`, server/client logs, frozen helper sources and hash manifest. `manifest.json` includes machine, disk, mount/filesystem, call percentiles (p999 means p99.9), wall/CPU economy cost per tick and count of economy ticks exceeding 5ms. Source and artifact hashes are checked before boot and after shutdown. The recorded `elapsedSeconds` includes background-worker drain after the final measured tick; raw samples and tick counts describe the active workload. No healthy-call errors should be accepted.

The foreground schedule is 5 writes/20 ticks (3 together, then 1 at tick 10 and 1 at tick 15), 50 reads/20 ticks, for at least 12,000 ticks and 600 elapsed seconds. This is nominal 5 writes and 50 reads per second at 20 TPS; lag reduces achieved rates, which must be reported. There is no warm-up exclusion. Reads are real synchronous Vault getBalance calls; writes alternate one-cent deposit/withdraw on one existing account. Read and write latency are reported separately so reads cannot hide write tails. Background defaults are preference changes every 2s, moderation audits every 10s, mail every 15s; these are assumed representative low-traffic rates, not observed SF traffic. Repository call time is additional to each interval. To tune the fixture's rates, supply JVM system properties `allium.bench.preference-ms`, `allium.bench.moderation-ms`, `allium.bench.mail-ms` using JAVA_TOOL_OPTIONS (minimum 100ms); preserve those settings with the evidence. Avoid unrelated JVM flags and never include secrets in that variable.

Run stress separately with `python3 tests/live/economy/run.py --stress-only --host-copy` plus the same explicit infrastructure/artifact/run-root arguments and a new run-name. It performs setup and the labelled 10-writes/tick stress workload. Omitting both workload switches retains the existing correctness/crash gates followed by stress. Stress is diagnostic, not a release gate. SF's actual host needs its own result and an explicit user-approved realistic release budget; development-host timings are not a substitute.

## Investigate stalls with phase timings, GC and checkpoint correlation

Append `--diagnostics --paired-reads` to the host command. Each invocation now takes at least **20 minutes**: a ten-minute pooled-reader phase followed by a ten-minute dedicated-reader phase. For repeatability run Spigot three times and Paper once, sequentially, with unique run names and the same frozen instrumented jar. The dedicated phase is a measurement-only experiment against the **same** `plugins/Allium/allium.db`, not a separate database or production reader switch.

Timing is off by default: `EconomyEngine.observeTiming(null)` installs no JDBC proxy or per-call samples. The harness opts in using `-Dallium.economy.timing=true` and a nonblocking in-memory collector. Raw JSON contains epoch milliseconds and monotonic nanoseconds, call kind/thread, connection acquisition (including any Hikari queue wait), JDBC `execute*` time, commit time, coordinator wait and other overhead. SQL preparation/result decoding/close, computations, JVM pauses outside those phases and bookkeeping appear in `otherNanos`. Commit timing includes SQLite's commit/sync path; it does not isolate the physical fsync syscall. Diagnostic observer publication and outer Vault adapter overhead are outside the core total, so retain the outer call samples as well. No SQL text, account identity or monetary payload is recorded in timing samples.

The JVM runs with `-Xlog:gc*:file=<copy>/gc.log:time,uptime,level,tags` (Java 17 and 25). Retain that file. Match GC Pause end timestamps and durations to epoch-based call intervals, allowing for the 1ms wall-clock timestamp resolution; overlap is correlation, not proof that a particular disk operation caused a pause. Concurrent GC-cycle durations are not stop-the-world pause durations.

With diagnostics enabled, a worker opens a short-lived connection every 30 seconds and times `PRAGMA wal_checkpoint(PASSIVE)`, recording start time, total/open cost, SQL duration, busy result, WAL frames and checkpointed frames. It never issues FULL/RESTART/TRUNCATE checkpoints or changes `synchronous`, auto-checkpoint policy or journal mode. PASSIVE probing itself performs real checkpoint work and can perturb storage; compare like-instrumented phases and disclose that noise. Probe errors/busy outcomes remain in raw evidence.

Today all ordinary economy reads use `DatabaseManager.connectionSource()` → Hikari `getConnection()`, sharing its single SQLite connection with preferences, moderation, mail and economy writes. The experiment opens one physical `mode=ro` connection with `query_only=ON`; only the measurement adapter uses it. Each balance query remains autocommit, closes its result/statement, retains no transaction and fetches committed state afresh. Core tests cover uncommitted-write invisibility and visibility after commit, and prove the read-only source rejects writes. The default registered provider is unchanged. Fixed pooled-first ordering, warmed caches/JIT in the second phase and shared-host activity limit causal conclusions: report spread across repeats, not one favorable result.

Raw files are `realistic-pooled-raw.json` and `realistic-dedicated-raw.json`. `activeSeconds` excludes final worker drain; `elapsedSeconds` includes it. Preserve both frozen source manifests and GC files. SF's actual host still requires its own run; the candidate release budget remains proposed, not approved.

After shutdown, correlate a completed pair with:

```sh
tools/analyse-economy-stalls --run /absolute/disposable/runs/sf-spigot-full > sf-spigot-stalls.json
```

Repeat `--run` to combine runs. The default 600ms-class threshold is >=500ms; `--threshold-ms` changes reporting only. The analyser retains all >25ms events, per-phase distributions and maxima, GC overlaps and probe overlaps. It validates read/write sample counts before pairing outer Vault latencies with engine samples. A conservative outer envelope accounts for adapter/observer time that lies outside the core scope; that overlap is less precise than core-scope overlap and is labelled separately. No file or database is changed by analysis. Run the analyser after measurement to avoid competing with the workload for disk/CPU.

The checkpoint probe can move work that would otherwise occur in an automatic checkpoint. Absence of a rare stall with probing enabled does not establish that the original unprobed stall is fixed. Timing proxies also allocate and warm up; first-call and GC outliers remain in the measured distributions. Keep diagnostic findings separate from an approved release budget and from an uninstrumented production-host run.
