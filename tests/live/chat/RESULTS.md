# S23 results

Completed on disposable loopback Spigot 1.20.1 and Paper 26.3 (build 141),
SQLite WAL FULL. Production processes and port 25565 were not used.

Frozen jar SHA256: `1fdaa5588eaa0af6c9eaca3d39d5cb28b62584ec0af1b0eb82bb9bdc600c736b`.

| Gate | Checks |
| --- | ---: |
| s23-final-spigot | 124/124 |
| s23-recovered-paper | 124/124 |
| s23-recovered-spigot-papi | 129/129 |
| s23-recovered-paper-papi | 129/129 |
| s23-recovered-r2-signs-spigot | 375/375 |
| s23-recovered-r2-signs-paper | 375/375 |

All six final-artifact manifests pass, including integrity and post-shutdown log
scans and owned process cleanup. Full paths, manifest hashes, helper hashes and
source freezes are in [machine evidence](evidence.json). These six lanes are the
S23 change gates, not a new pass of the full 24-suite regression sweep. S21 sign
gates remain mandatory in that sweep.

## Coverage and limits

Native clients exercise public input, safe rendering, nickname permission/color/
others/offline/reset/restart persistence, channel membership and staff/local
audiences, mute/vanish, exact-ID duplicate deletion, overlapping replay with live
arrivals, private replay isolation, reload/disable behavior and every gamemode
alias with self/others/family permissions. Sign lanes retain all eleven types,
front/back/waxed/hanging, permissions/protection, disabled type/command/module,
paid click spam, chunk/restart persistence, explosion and piston destruction.

The PAPI lanes use a test-only ABI host with controlled GradientPlus palette
responses, including white/unset fallback; they do not establish compatibility
with the operator's full GradientPlus installation. Discord live coverage uses
a controlled relay seam (incoming IDs/audiences, duplicate admission and late
acknowledgement tombstones). Pinned DiscordSRV 1.30.3 SDK tests exercise bot exact
returned identities, webhook `wait=true`, empty allowed mentions, returned-ID
delete and readiness hooks using mocked transports. No external Discord messages
were sent. PacketEvents 2.12.1 API tests use mocked packet transport; InteractiveChat
uses a minimal public event ABI test plus real Adventure compaction. Full native
optional-peer installations and MySQL chat acceptance are not claimed.

## Unit deltas and commits

| Change | Delta | Total |
| --- | ---: | ---: |
| `c9ebd06` S21 service matrix | +55 | 722 |
| `33758b3` S22 design revisions | 0 | 722 |
| `1e8065f` fixed gamemode aliases | +8 | 730 |
| `046982e` importer design | 0 | 730 |
| S23 implementation | +51 | 781 |

Gradle `check shadowJar` passed before interruption; recovered sources and frozen
jar match exactly, and a fresh `check --rerun-tasks --offline` passes after recovery. Zero test failures,
errors or skips. S23 adds storage/history, service admission/replay/persistence,
rendering, Vault fallback/decorations, Discord SDK, packet mirror and identity
regressions. No shop/importer code or S24 work is included. Vault provider remains
disabled by default.

## Preserved unsuccessful attempts

Earlier run directories remain under the same run root. `s23-spigot-r1` expected
a permission message where Spigot hides a denied alias as unknown; `s23-paper-r1`
compared Paper's normalized trailing color code literally. Assertions were fixed.
`s23-paper-papi-r1` was intentionally superseded during restart and preserves an
early-stop native Paper command-source exception. `s23-final-paper` expected a
system packet for module-off native player chat; the corrected check verifies
the actual raw player-chat wire payload. `s23-final-paper-r2` was interrupted by
ext4 emergency read-only shutdown and EIO, so has no completed final manifest.
Its partial logs are not treated as a pass. `s23-recovered-signs-paper` was
372/373: a flat-world slime killed idle observer MoneyB during the strict
anvil no-chat assertion; server state confirmed no anvil action occurred.
The harness now makes both idle sign observers creative, preserving actor damage
probes and strict assertions, and both sign lanes were rerun. All remaining gates were rerun after
storage recovery; failed attempts were not relabelled or deleted.

See [operator contract](../../../docs/chat.md) and
[importer formats](../../../docs/importers-design.md).
