# Stall investigation: repeated realistic FULL runs

**Correctness checks pass; candidate budget remains proposed, provider release unapproved.** Unit delta **+4 (627 → 631)**. No production read-source switch, separate economy database or durability change.

Three Spigot 1.20.1/Java17 runs and one Paper 26.3/Java25 run were sequential. Each run contains ten minutes pooled then ten minutes dedicated reads, with the same 5-writes/s burst schedule, 50 reads/s, paced repository traffic and frozen instrumented jar. All phases have at least 600 active seconds. Timing and PASSIVE probe overhead are included; no outlier or warm-up exclusions. Each phase made 3,003 writes and 30,002 reads across 12,001 ticks. Total 264040 calls; 224 live checks.

## Spread across runs

Milliseconds; p99.9 is floor((n−1)×.999), no interpolation. Both read and write tails are retained separately.

| Run / read source | Read p50 | Read p95 | Read p99 | Read p99.9 | Read max | Write p50 | Write p95 | Write p99 | Write p99.9 | Write max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| spigot-1 / pooled | 0.116 | 0.352 | 0.472 | 0.952 | 13.021 | 2.006 | 3.049 | 4.497 | 7.744 | 21.397 |
| spigot-1 / dedicated | 0.125 | 0.370 | 0.448 | 0.639 | 6.943 | 1.605 | 2.566 | 3.679 | 9.323 | 10.289 |
| spigot-2 / pooled | 0.132 | 0.365 | 0.503 | 0.935 | 16.996 | 2.116 | 3.123 | 5.075 | 7.889 | 8.667 |
| spigot-2 / dedicated | 0.131 | 0.376 | 0.456 | 0.578 | 1.879 | 1.903 | 2.828 | 4.260 | 9.420 | 14.666 |
| spigot-3 / pooled | 0.125 | 0.367 | 0.497 | 0.962 | 8.552 | 1.965 | 3.114 | 4.871 | 7.768 | 21.517 |
| spigot-3 / dedicated | 0.132 | 0.375 | 0.454 | 0.686 | 10.427 | 1.887 | 2.905 | 5.213 | 9.854 | 15.407 |
| paper-1 / pooled | 0.094 | 0.356 | 0.514 | 0.932 | 13.675 | 1.690 | 2.952 | 4.403 | 7.131 | 10.331 |
| paper-1 / dedicated | 0.085 | 0.317 | 0.399 | 0.579 | 9.567 | 1.468 | 2.740 | 4.635 | 8.720 | 19.770 |

| Run / source | Economy wall/tick p99 | Wall max | Economy CPU/tick p99 | CPU max | Ticks >5ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| spigot-1 / pooled | 7.036 | 30.344 | 4.085 | 26.114 | 452/12001 (3.766%) |
| spigot-1 / dedicated | 6.345 | 15.392 | 3.546 | 10.527 | 323/12001 (2.691%) |
| spigot-2 / pooled | 7.048 | 21.548 | 4.166 | 8.384 | 503/12001 (4.191%) |
| spigot-2 / dedicated | 6.790 | 17.644 | 3.977 | 6.567 | 427/12001 (3.558%) |
| spigot-3 / pooled | 7.120 | 26.208 | 4.100 | 22.255 | 449/12001 (3.741%) |
| spigot-3 / dedicated | 6.707 | 21.345 | 3.844 | 16.546 | 423/12001 (3.525%) |
| paper-1 / pooled | 6.485 | 13.940 | 3.729 | 10.108 | 328/12001 (2.733%) |
| paper-1 / dedicated | 5.977 | 25.892 | 3.328 | 13.135 | 277/12001 (2.308%) |

Full per-phase wall/CPU p50/p95/p99/p99.9/max and all connection/statement/commit/coordinator/other distributions are in the [exact evidence](evidence/stall-summary.json). Spigot has three repeats per read mode; Paper has only one pair, not an estimate of between-run variance.

## 600ms-class event and attribution

Reporting threshold: >=500ms. **0 such event(s) in these 264040 measured calls.** The original uninstrumented Spigot read of 600.934ms remains in [the earlier report](REALISTIC.md). It had no GC or phase trace, so its cause cannot be assigned retrospectively. Lack of recurrence does not establish a fix.

| Run / source | Maximum read connection acquisition | Maximum write commit | Longest GC pause in active window | Probe total max | Probe SQL max | Probe errors / busy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| spigot-1 / pooled | 2.257 | 14.691 | 12.729 | 6.845 | 5.928 | 0 / 0 |
| spigot-1 / dedicated | 5.291 | 9.503 | 16.867 | 8.542 | 7.789 | 0 / 0 |
| spigot-2 / pooled | 1.425 | 7.470 | 16.584 | 7.922 | 7.007 | 0 / 0 |
| spigot-2 / dedicated | 1.389 | 9.198 | 12.200 | 8.411 | 7.662 | 0 / 0 |
| spigot-3 / pooled | 1.931 | 8.555 | 12.046 | 6.902 | 6.252 | 0 / 1 |
| spigot-3 / dedicated | 7.438 | 14.676 | 15.812 | 7.626 | 6.718 | 0 / 1 |
| paper-1 / pooled | 2.062 | 8.484 | 13.301 | 7.498 | 6.437 | 0 / 0 |
| paper-1 / dedicated | 7.998 | 19.032 | 14.785 | 9.300 | 8.308 | 0 / 0 |

Selected evidence (core interval correlation with ±2ms wall-clock tolerance):
- spigot-1 / pooled: slowest core read 13.018ms = acquire 0.000, SQL 0.056, other 12.961; GC overlap 12.729ms; probe overlap none.
- spigot-1 / dedicated: slowest core read 6.843ms = acquire 5.291, SQL 1.022, other 0.531; GC overlap none; probe overlap none.
- spigot-2 / pooled: slowest core read 16.990ms = acquire 0.001, SQL 16.911, other 0.078; GC overlap 16.584ms; probe overlap none.
- spigot-2 / dedicated: slowest core read 1.853ms = acquire 1.389, SQL 0.295, other 0.168; GC overlap none; probe overlap none.
- spigot-3 / pooled: slowest core read 8.544ms = acquire 0.002, SQL 8.430, other 0.112; GC overlap 8.018ms; probe overlap none.
- spigot-3 / dedicated: slowest core read 10.292ms = acquire 7.438, SQL 1.910, other 0.943; GC overlap none; probe overlap none.
- paper-1 / pooled: slowest core read 13.672ms = acquire 0.000, SQL 13.595, other 0.076; GC overlap 13.301ms; probe overlap none.
- paper-1 / dedicated: slowest core read 9.470ms = acquire 7.998, SQL 0.957, other 0.515; GC overlap none; probe overlap none.

Timing distinguishes time inside JDBC acquisition, `execute*`, commit, coordinator and other work. Commit includes SQLite sync/checkpoint work but does not isolate the fsync syscall; do not label every commit tail hardware latency. A GC pause can inflate any phase. In the first pooled Spigot run, a 13.018ms core read overlaps a 12.729ms GC pause; two ~15ms writes spend 13.676/14.691ms in commit with no overlapping logged GC or probe. Some largest write tails are in other work, including first instrumented calls; proxy/class initialization is a plausible warm-up contribution, not a proven attribution. These samples are retained. The evidence keeps every >25ms event and all maxima, even when GC-correlated.

The dedicated read experiment mildly reduces some read percentiles, but maxima vary and the third Spigot dedicated maximum exceeds its paired pooled maximum. Writer p99 does not improve uniformly. All pairs use pooled-first ordering, so cache/JIT/heap/time effects are confounded with read mode. No production switch is justified from one favorable pair.

## Noise and burst ticks

A burst of three writes occurs on 5% of ticks and accounts for 60% of writes. With a ~2ms median write, that tick naturally costs ~6ms plus its reads. Thus a substantial share of ticks >5ms is structural burst work; it is not evidence that each call is slow or that GC caused all those ticks. Tick p99 is deliberately inside the burst population. A call budget and a burst-tick budget are different measurements.

GC pauses, JVM warm-up, shared-host scheduling/storage activity and first-call instrumentation contribute measurement noise. Source drafting and one small offline analysis continued on this development host; no parallel server benchmark or Gradle build ran during the matrix, but the host was not isolated. PASSIVE probes themselves checkpoint real pages and may offload automatic checkpoint work. Diagnostic instrumentation also allocates. Consequently these repeats cannot explain the original unprobed 600ms sample or guarantee its absence in production. Neither GC-correlated outliers nor instrumentation warm-up is trimmed from the numbers.

## Connection semantics, machine and decision

Today reads check out the same single Hikari SQLite connection as preference, moderation and mail writers, configure/verify FULL, execute the point SELECT, and return the connection. Connection acquisition can therefore include a pool wait. The experiment uses one separate physical mode=ro/query_only connection to the same allium.db, only in the local measurement adapter. Each call is autocommit and closes its statement/result; no transaction or cached snapshot persists. Unit tests reuse the same physical reader, prove it cannot see an uncommitted update, prove the next call sees the committed update, and reject writes through it. The default registered provider still uses the original pool.

All runs use the same development machine/disk: i7-12700KF, 20 logical CPUs, Extreme 55AE SSD, /dev/sda4 ext4 rw,noatime; full kernel/JVM flags/memory/block-device/mount inventory is retained per manifest. SQL stopped-state checks independently match all three background write counts. Jar SHA256: `4360db01ce3d710efcc8e74b5149f76e22f21c725d43528dfe7f15865fea155b`. Raw samples, GC logs, helper/source manifests and digests are retained by absolute path in the evidence.

The candidate budget remains **proposed, not approved**: write p99 ≤6ms, read p99 ≤1ms, each class p99.9 ≤10ms, every healthy call ≤25ms; economy wall/tick p99 ≤10ms with ≤5% of ticks >5ms. The three durability options retain their measured historical evidence in [the design](../../../docs/economy-design.md); no alternative or separate file is selected. SF needs its own matching-jar/harness run. [Measurement and analysis commands](MEASURE.md). E5 proceeds only through its separately tested async path; this is not a Vault provider release.
