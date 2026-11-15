# E4 economy gate

**Correctness PASS — 330/330 live checks. Latency gate FAIL on both server lanes.**

Frozen Allium SHA256: `9f8616aa5c26637174c91ba0d933b252cd2fa7721dc11114f00bec1d1f2f24bc`. Full isolated check/shadowJar: **627 unit tests, zero failures/skips**, E4 delta **+9** from 618.

- Spigot 1.20.1 / Java 17: **165/165** correctness checks.
- Paper 26.3 / Java 25: **165/165** correctness checks.

Real clients cover online/offline payments, permission/toggle/ignore/vanish/mute refusals, minimum pay, exact cents, caps, insufficient funds, command spam, ordinary restart persistence and policy reload. Real JVM halts before commit (77) and after commit (78) prove stopped journal state and exact same-ID recovery without duplicate effects. A test plugin calls the actual registered Allium Economy provider through unmodified VaultAPI 1.7.1, including concurrent withdrawals and an explicit unknown-acknowledgement simulation. The test plugin hosts the API; this is not a separate Vault distribution compatibility claim.

Each workload below contains **1,000 synchronous Vault calls over 100 ticks**, five deposit/withdraw pairs per tick, while real preference, moderation and mail repositories write. Each writer round invokes all three repositories. All layouts retain WAL and synchronous=FULL. Call and per-tick values are milliseconds.

- **spigot / shared-allium.db**: calls p50 **1.043**, p95 **2.609**, p99 **3.903**, max **5.887**; writer rounds **392**. Economy wall time/tick p50/p95/p99/max: 12.218/16.135/17.506/20.266. Whole server-thread CPU sampled per tick: 4.888/6.657/8.045/11.444.

- **spigot / separate-economy.db prototype**: calls p50 **1.040**, p95 **1.558**, p99 **3.420**, max **5.198**; writer rounds **401**. Economy wall time/tick p50/p95/p99/max: 10.452/13.816/15.235/16.758. Whole server-thread CPU sampled per tick: 4.041/5.869/6.573/6.687.

- **paper / shared-allium.db**: calls p50 **1.040**, p95 **3.130**, p99 **5.176**, max **6.603**; writer rounds **393**. Economy wall time/tick p50/p95/p99/max: 12.085/26.054/40.512/40.522. Whole server-thread CPU sampled per tick: 3.778/6.642/7.822/8.267.

- **paper / separate-economy.db prototype**: calls p50 **1.041**, p95 **3.506**, p99 **4.644**, max **14.670**; writer rounds **382**. Economy wall time/tick p50/p95/p99/max: 10.374/33.814/40.469/44.041. Whole server-thread CPU sampled per tick: 3.340/5.189/6.344/7.462.

CPU samples use ThreadMXBean between tick callbacks; they measure server-thread CPU, not total elapsed tick duration. Economy wall samples include the ten synchronous calls in that tick. No healthy call exceeded 25 ms, but neither layout met p99 <= 2 ms.

## Separate economy.db option

A separate SQLite file, retaining FULL and reusing a physical connection, modestly reduced measured p99: Spigot 3.903 → 3.420 ms and Paper 5.176 → 4.644 ms. **It still fails the release target**; file separation alone is not a proven fix on this storage. The prototype exercises Vault-only money, not cross-file PAY policy. A production option must place authoritative payment acceptance/ignore policy alongside the journal and preserve single-process writer ordering; it cannot rely on atomic WAL commits across two files. Connection setup and underlying shared-storage contention need further measurement before choosing this option. Durability has not been weakened.

The scaffold writer and provider flag are retired. The provider checks schema and recovery readiness before registration; this branch is not claimed ready for a production release because the measured latency gate failed. No deployment was performed. E5 reservations have not started: paid kits remain denied, and signs remain S21.

Earlier attempts are preserved: initial fixture path compilation failure, then a missing shared timing helper and a log-scan false positive for the error_prone library filename. Final helpers, jar hashes and subprocess exits were checked after shutdown; all owned server ports are free.

[Exact checks, process exits, hashes and measurements](evidence/e4-summary.json).
