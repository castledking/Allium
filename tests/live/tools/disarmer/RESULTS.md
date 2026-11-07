# S06a Mob Disarmer live gate

**PASS: 55 cases, 793 checks, 0 failures** on three lanes with a real protocol client, on
2026-11-06. Allium.jar SHA-256
`56d8a52036f66733153e0acc5c43762e6910b7fcd74b94e54a3e9eb7acfa4040`. Hashes, exact versions and run paths are in
the [manifest](evidence/s06a-manifest.json). Each run rebuilds the fixture jar from the same source,
so the jar hashes differ only by archive timestamps; the manifest pins the source hashes. The run directories, with full server/client logs
and per-case JSON, are under `/tmp/allium-s06a-20261106/runs/`. They are not committed.

| Lane | Runtime | Client | Appearances | Cases | Checks |
| --- | --- | --- | --- | --- | --- |
| Spigot 1.20.1 (3871-d2eba2c) | Java 17.0.21 | minecraft-protocol 1.68.0, protocol 763 | built-in | 9 | 126 |
| Paper 1.21.8-60 | Java 21.0.13 | minecraft-protocol 1.68.0, protocol 772 | built-in, Nexo 1.30-dev-36f4ce686, CE 26.9.1, Oraxen 1.220.1 | 36 | 525 |
| Paper 26.3-141 + GriefPrevention3D 18.4.5 | Java 25.0.5 | owned raw client, protocol 777 | built-in | 10 | 142 |

## What each case asserts

The fixture never disarms anything. It sets up a case from the console, and the bot sends genuine
interaction packets, the same ones a vanilla client sends when the main hand passes:
main hand first, then off hand. The fixture records the server-side events and the resulting state.
Every mob wears six marked pieces with drop chances HAND 1.0, CHEST 2.0, LEGS 1.0 and
OFF_HAND/HEAD/FEET 0.0, so the drop rule has an exact expected result.

Each appearance on each lane runs these cases:

- **Authorized:** the tool goes from full to `charges=2, uses=1`. It keeps its instance UUID and
  is re-signed. All six slots are empty and their drop chances are 0. HAND, CHEST and LEGS each
  exist exactly once, on the ground or picked up by the player; the other three are destroyed.
  The main-hand event is cancelled by Allium, and the protection check event fires once.
- **Protected:** cancelling `AlliumProtectionCheckEvent` changes nothing. The tool, its MAC, the
  equipment and the drop chances are unchanged, and nothing drops.
- **Cancelled:** when another plugin cancels the interaction at LOWEST, Allium never runs and
  nothing changes.
- **Empty target:** nothing is spent or dropped, the click passes through, and protection is not consulted.
- **Rapid double-click:** two back-to-back clicks spend exactly one charge, and every piece exists once.
- **Off-hand only:** the off-hand tool is unchanged and the mob keeps its gear.
- **Both hands:** only the main-hand tool spends; the off-hand tool is byte-identical.
- **Kill after disarm:** a player kill drops no disarmed piece, and no piece is duplicated.
- **Persistence:** a spent keeper of each appearance is compared field by field: behavior,
  appearance, state, UUID, MAC and all `allium:*` PDC. On 1.21.8 this runs after
  `nexo reload items`, `ce reload` and `oraxen reload items`, and on every lane after a restart.
  The persisted tool is then used again and goes to `charges=1, uses=2`.

On 1.21.8 Allium is configured with a Nexo charge-variant set. A Nexo disarmer is rebuilt as
`nexo:mob_disarmer_2-3`, then `_1-3`. Nexo's native ID confirms each swap, and the instance UUID,
MAC and charges are preserved.

On 26.3 the suite runs with GriefPrevention3D installed and no claims, so the hook allows. Then an
admin claim is created over the arena through GriefPrevention's public `createClaim`. A disarm
inside it spends nothing and leaves the gear in place. The interaction stays uncancelled, so
GP3D did not block it itself. The generic check event never fires, so the denial came from
Allium's GriefPrevention hook.

## Scope and limits

- Providers were exercised on 1.21.8 only, with the supplied Nexo/Oraxen builds and official CE
  26.9.1. CE items here have no reset updater, so the CE count defect is not involved.
- The click guard, legacy import, break chances, recharge expiry, bypass and the rejected-write
  rollback are covered by unit tests, not by live cases. In live double-clicks, the second click
  found nothing left to remove.
- Only upstream-compatible GriefPrevention API was used, and only GP3D 18.4.5 was run. No
  separate upstream GriefPrevention run was made.
- No resource pack was generated or sent; S06b owns visuals.

## Rerun

Prerequisites are the retained S05 disposable artifacts named in `run.py` and in the
[provider matrix](../../../../docs/s05-provider-matrix.md). Use owned ports 25650–25652 and a fresh
run name:

```sh
./gradlew build
python3 tests/live/tools/disarmer/run.py --lane spigot-1.20.1 --run-name <name>
python3 tests/live/tools/disarmer/run.py --lane paper-1.21.8 --run-name <name>
python3 tests/live/tools/disarmer/run.py --lane paper-26.3 --run-name <name> \
  --griefprevention /path/to/GriefPrevention3D.jar
```

The runner builds the fixture with `javac --release 17`. It copies only binaries, immutable
library caches and the retained provider settings, and never copies worlds, player data or keys.
It always stops its own client and server, and exits nonzero on any failed check.
