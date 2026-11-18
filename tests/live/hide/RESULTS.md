# Hide port gate

## 2026-11-18: suggestion ledger and resolver follow-up

Spigot 1.20.1 and Paper 26.3 passed **29/29 each** on jar `81af1a08…` (r8; runs `hide-spigot-r8`,
`hide-paper-r8` under `/mnt/storage/.allium-validation/20261118/s18-playerinfo/runs`). New checks:
40 completion requests the server never answers (Spigot and Paper send nothing for an empty
completion) are followed by a normal request that is still answered; `/i` item names pass the
permission resolver only with `allium.give`; `/i` completes the entry after a comma. The previous
jar (`23823e09…`, r7) fails those checks on both lanes (25/29, `hide-*-r7-repro`): after the burst,
the next requests got no answer, which is the "suggestions only come back after typing and
deleting" report.


Unit delta **+42**, 835 → **877**. Full `check shadowJar` passes with zero
failures/errors/skips. Tests cover policy paths, aliases, namespace boundaries,
blacklist priority, independent inheritance, malformed config, packet snapshots,
request correlation/bounds, compacted trees/redirects, resolver metadata and
fallbacks, and deterministic/owned hint-pack exports.

Spigot 1.20.1 and Paper 26.3, SQLite, real protocol clients: **18/18 each**.
Each lane runs with official PacketEvents 2.14.0, then restarts without it.
Gate source and jar hashes, per-check results and manifest hashes are in
`results.json`; retained manifests/logs live under the paths recorded there.
The helper translation ZIP is inspected, not visually rendered in a Minecraft GUI.
Framework metadata tests use API-compatible shapes; these lanes do not install
LuckPerms, Vault permission providers or NightCore and are not certification of
those providers. Native Bukkit permissions and real PacketEvents packets run in
both lanes.

Frozen jar SHA256:
`7e240591584ac89d6ef438f62a772fae4f43c4cc2a861c06e1a023c852567bf4`.
The frozen jar, build log and production source hash inventory are in
`/mnt/storage/.allium-validation/20261116/hide-final`.

Earlier attempts are retained: r1 failed before launching a server (shared
harness path); r2 failed helper compilation against an accidentally selected old
plain jar. Neither is counted as a live pass. r3 used the correct shadow jar and
passed Spigot 18/18 before the stricter configuration/framework changes. Both
final lanes use the same final jar and final harness.
