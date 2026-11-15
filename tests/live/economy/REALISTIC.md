# Realistic FULL latency follow-up

**No release approved. E5 not started. Unit delta +0; existing total 627.** Production sources and the accepted jar are unchanged. No separate economy database was added and durability was not weakened.

Both server lanes ran sequentially, each with at least 600 seconds and 12,001 measured ticks, three idle real clients, 3,003 synchronous Vault writes and 30,002 reads. This tests a low-traffic disposable flat world, not SF's full gameplay/plugin workload. Five writes per 20 ticks include a burst of three; 50 reads per 20 ticks. Background intervals: preferences 2s, moderation audit 10s, mail 15s. These are explicit assumed realistic rates, not observed SF production rates. Each lane persisted 299 preference versions, 59 audit rows and 39 mail rows, independently checked after shutdown. No call errors, exclusions or warm-up trimming.

Milliseconds below; p99.9 is the floor((n-1)*0.999) sample, with no interpolation. **Writes and reads must be assessed separately.** Elapsed duration includes final background-worker drain; achieved rates calculated with it are conservative.

| Server / measure | n | p50 | p95 | p99 | p99.9 | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| spigot writes | 3003 | 1.993 | 3.051 | 5.054 | 9.459 | 28.996 |
| spigot reads | 30002 | 0.097 | 0.331 | 0.435 | 0.865 | 600.934 |
| spigot all calls | 33005 | 0.115 | 1.814 | 2.636 | 4.914 | 600.934 |
| spigot economy wall/tick | 12001 | 0.417 | 3.524 | 6.651 | 10.746 | 601.096 |
| spigot economy CPU/tick | 12001 | 0.409 | 2.003 | 3.814 | 5.407 | 11.970 |

spigot: **455/12001 ticks (3.791%) exceeded 5ms of economy wall work**. Elapsed including drain 601.100s; conservative achieved rates 4.996 writes/s and 49.912 reads/s.

| Server / measure | n | p50 | p95 | p99 | p99.9 | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| paper writes | 3003 | 1.688 | 2.830 | 4.204 | 7.730 | 8.622 |
| paper reads | 30002 | 0.078 | 0.307 | 0.405 | 0.751 | 5.296 |
| paper all calls | 33005 | 0.089 | 1.621 | 2.449 | 4.023 | 8.622 |
| paper economy wall/tick | 12001 | 0.351 | 3.479 | 6.172 | 8.458 | 13.936 |
| paper economy CPU/tick | 12001 | 0.344 | 1.883 | 3.376 | 4.834 | 6.773 |

paper: **309/12001 ticks (2.575%) exceeded 5ms of economy wall work**. Elapsed including drain 600.466s; conservative achieved rates 5.001 writes/s and 49.965 reads/s.

Economy wall and ThreadMXBean CPU samples bracket only the synchronous calls and measurement bookkeeping in each tick. They are not whole tick duration or whole server-thread CPU. Spigot's worst read occurred near the end (zero-based tick 11,849): 600.934ms read, 601.096ms economy wall, 0.827ms economy CPU. This establishes a large non-CPU stall in the measured section; the run did not instrument its cause. It remains a healthy-call maximum, not a discarded anomaly.

## Labelled diagnostic stress, not a release gate

The retained stress workload is ten writes/tick for 100 ticks, while all three repositories write in a 10ms loop. Fresh runs use the same jar and physical filesystem.

| Server | p50 | p95 | p99 | p99.9 | max |
| --- | ---: | ---: | ---: | ---: | ---: |
| spigot | 1.603 | 4.245 | 5.816 | 12.076 | 17.411 |
| paper | 1.573 | 4.157 | 5.207 | 7.020 | 7.022 |

The earlier stress p99 was 3.903ms Spigot / 5.176ms Paper, missing 2ms; that historical evidence and separate-file prototype remain in [E4 RESULTS](RESULTS.md). No separate-file run was performed in this follow-up. Stress is diagnostic and is not used to select the release budget.

## Proposed budget — user decision

For consideration on SF's actual host: write p99 <=6ms, read p99 <=1ms, p99.9 <=10ms for each call class, every healthy call <=25ms; economy wall/tick p99 <=10ms and <=5% of measured ticks exceeding 5ms. Keep SQLite WAL synchronous=FULL. These percentile proposals reflect the realistic-lane distributions, not the stress result. The existing 2ms target was missed under stress and by realistic writes; pooled read-heavy percentiles cannot establish write acceptance.

**This is a proposal, not an approved replacement. The present combined result fails even this candidate budget because Spigot exceeded the 25ms maximum (write 28.996ms, read 600.934ms).** Do not make those stalls acceptable by raising the maximum. Investigate them and rerun on SF's host before considering release or E5. Paper alone meets this candidate; that does not clear the Spigot lane. The choice of budget remains with the user.

## Machine and reproducibility

Development host: Intel Core i7-12700KF, 20 logical CPUs, Linux x86_64; database copies on /dev/sda4, ext4 rw,noatime, Extreme 55AE SSD (lsblk size 1.8T) (ROTA=0). It is distinct from the Samsung 980 PRO system disk. Both fresh stress and realistic manifests capture CPU/kernel/memory/block-device/mount details. The earlier stress inventory was recorded retrospectively and is marked as such in [machine inventory](evidence/machine-inventory.json). No power-loss guarantees are inferred from device identifiers.

SF's real host requires its own run. See [host command and preparation](MEASURE.md) and [tools/measure-economy-latency](../../../tools/measure-economy-latency). The test hosts unmodified VaultAPI 1.7.1 with a test plugin and calls the actual registered Allium provider; it does not claim separate Vault-distribution compatibility.

The initial Spigot attempt was interrupted to remove artificial console polling; it is retained but excluded. The accepted runs poll the result file without server commands. Their frozen Java fixture hashes match; archived controller sources and exact raw sample hashes are retained per run. Later controller changes label stress explicitly and add its standalone entry point; they do not change the realistic workload.

[Exact results, hashes, paths, process exits and stopped-SQL proof](evidence/realistic-summary.json). Jar SHA256: `9f8616aa5c26637174c91ba0d933b252cd2fa7721dc11114f00bec1d1f2f24bc`. No production release/deployment; no E5 or S21 work.
