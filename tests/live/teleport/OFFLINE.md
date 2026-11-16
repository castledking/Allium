# Offline teleport gate

Unit delta **+10**, 877 → **887**, full `check shadowJar` passes with zero failures,
errors or skips. Service regressions cover worker-only reads, history/dedicated
acknowledgement, absent/unloaded destinations, target incarnation changes,
warmup permission checks, cancel/reconnect fences, queue/storage failure,
service admission, overlapping operations and quit/shutdown saves. The command
spec regression verifies known-name completion and alias disable-family policy.

Native Spigot 1.20.1 and Paper 26.3, SQLite, real protocol clients:
**16/16 named cases and 156/156 checks per lane**. The full existing teleport
suite runs alongside the new offline-command case. That case exercises denied
permission, ordinary online `/tp` delegation, vanished-vs-unknown refusal,
offline known-name completion, `/tpoffline`, `/otp`, `/offlinetp`, `/allium:otp`,
exactly one physical move, dedicated acknowledgement, `/back`, target rejoin,
permission revocation and restart while the target remains offline.

Both manifests confirm unchanged production/helper/client hashes, unchanged jar
and cached client dependencies, no callback/fixture/decoder errors, and zero-exit
cleanup without forced termination. All owned loopback ports were released.
`offline-results.json` records full manifest paths and hashes.

Frozen final jar SHA256:
`942df7dd441f68857c1fc9aa49deeec9eea9923525bfa1e148570f7483fe2d05`.
Jar, build log and production source inventory:
`/mnt/storage/.allium-validation/20261116/offline-teleport`.
The native runs passed on their first execution. Earlier unit iterations caught
and corrected test matcher/stubbing setup errors; they are not reported as passes.
