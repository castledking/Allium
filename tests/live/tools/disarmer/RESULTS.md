# S06a Mob Disarmer live gate

**PASS: 58 cases, 862 checks, 0 failures** on three lanes with a real protocol client, on
2026-11-06, including the S06a follow-up (drop rule and one fault-injection case). Allium.jar SHA-256
`13685eee1f22746edc13c0f003e5e965f982491ff6f4d49c68e4b1c37743af0f`. Hashes, exact versions and run paths are in
the [manifest](evidence/s06a-manifest.json). Each run rebuilds the fixture jar from the same source,
so the jar hashes differ only by archive timestamps; the manifest pins the source hashes. The run directories, with full server/client logs
and per-case JSON, are `/tmp/allium-s06a-20261106/runs/*-followup`. They are not committed; the
earlier `*-final` / `paper-263-gp-r1` runs (55 cases, 793 checks) are superseded.

| Lane | Runtime | Client | Appearances | Cases | Checks |
| --- | --- | --- | --- | --- | --- |
| Spigot 1.20.1 (3871-d2eba2c) | Java 17.0.21 | minecraft-protocol 1.68.0, protocol 763 | built-in | 10 | 149 |
| Paper 1.21.8-60 | Java 21.0.13 | minecraft-protocol 1.68.0, protocol 772 | built-in, Nexo 1.30-dev-36f4ce686, CE 26.9.1, Oraxen 1.220.1 | 37 | 548 |
| Paper 26.3-141 + GriefPrevention3D 18.4.5 | Java 25.0.5 | owned raw client, protocol 777 | built-in | 11 | 165 |

## What each case asserts

The fixture never disarms anything. It sets up a case from the console, and the bot sends genuine
interaction packets, the same ones a vanilla client sends when the main hand passes:
main hand first, then off hand. The fixture records the server-side events and the resulting state.
Every mob wears six marked pieces with drop chances HAND 1.0, CHEST 2.0, LEGS 1.0 and
OFF_HAND/HEAD/FEET 0.0, so the drop rule has an exact expected result. Lanes run the shipped
default `drop-rule: slot-chance`.

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
- **Rejected write (built-in appearance, once per lane):** the fixture arms one
  `item.state-write` fault, so `ItemService.updateState` rejects the disarm's write after
  protection and the recheck, with every piece already off the mob. The fault trips exactly
  once and Allium logs the rejected write. The tool is unchanged (state, UUID, MAC, all
  `allium:*` PDC), the six slots and drop chances equal the baseline field by field, and nothing
  drops or reaches the player. Killing the mob afterwards drops exactly HAND, CHEST and LEGS
  once each, so the restored chances are live and nothing was duplicated.
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

Fault injection exists only for disposable lanes: the runner starts each server with
`-Dallium.fault-injection=true`, Allium logs a warning at startup, and the point is inert unless
the fixture arms it. Nothing in config or in-game can enable it.

## S10: data-driven definitions, the gaps closed (2026-11-09)

**PASS: 486 live checks, 0 failures**, all with Allium.jar `4bd39274…`. Every case above ran
again with the Mob Disarmer defined by `tools/mob_disarmer.yml`, the shipped default. The details
are in the [S10 manifest](evidence/s10-gaps-manifest.json). Runs are kept in
`/mnt/storage/.allium-validation/20261109/s10-disarmer/`.

| Run | Lane | Cases | Checks |
| --- | --- | --- | --- |
| `gaps-spigot` | Spigot 1.20.1 | 13 | 191 |
| `gaps-paper26` | Paper 26.3 + GriefPrevention3D 19.0.5 | 14 | 207 |
| `upgrade-spigot` | Spigot 1.20.1: the S07 jar, then this jar | 3 | 33 |
| `quick-paper1218` | Paper 1.21.8, Nexo, CraftEngine and Oraxen (one authorized disarm each) | 4 | 55 |

- **A second definition, used live:** `allium:wand_disarmer` (5 charges, a 3-block radius,
  `drop-rule: always`, built-in item `stick`).
  - **Identity:** its stack is its own signed identity on its own item.
  - **Charges:** one disarm leaves 4 charges.
  - **Drops:** all six pieces exist exactly once (its own drop rule).
  - **Radius:** a mob 4.5 blocks away is refused by its own 3-block radius before protection is
    asked, while the Mob Disarmer's 6-block radius reaches the same mob. The wand's radius sits
    inside server reach, so the difference is observable; the server never accepts an
    interaction beyond about 6 blocks.
- **The invalid definition's diagnostics,** each checked word for word, at startup and after the
  restart. `tools/invalid_tool.yml` has six problems, and the log names the file and each
  setting:
  - `schema: must be 1; this Allium reads tool definition schema 1 (got 99)`
  - `item: is required: allium:invalid_tool is not a core pack item…`
  - `charges.max: must be between 1 and 1000 (got 0)`
  - `targets.excluded-mobs[0]: 'zombi' is not an entity type`
  - `targets.radious: unknown setting; expected one of radius, excluded-mobs, exclude-npcs`

  A probe shows it registered nothing: no built-in item, no binding for its identity or its alias
  `nexo:invalid_tool_alias`, no signed behavior, no permission node, and nothing can be created
  for it.
- **The claim case on 26.3,** with GriefPrevention3D 19.0.5 (a binary copy of SF's jar; S06a's
  18.4.5 is no longer retained).
  - **The claim:** GriefPrevention's public `createClaim` made an admin claim.
  - **Inside it:** the disarm changed nothing.
  - **Which layer denied it:** the generic check event never fired, so Allium's GriefPrevention
    hook denied it.
- **A live upgrade:**
  - **The setup:** the S07 jar (`c877b783…`, from before tool definitions) ran with its own
    `config.yml`, customized to `recharge-seconds: 90`, and made a spent keeper. Then only
    `Allium.jar` was replaced.
  - **The migration:** this jar moved 17 settings from `tools.mob-disarmer` into
    `tools/mob_disarmer.yml`, with `recharge-seconds: 90`.
  - **The keeper:** it was field-for-field the same tool: behavior, appearance, state, UUID, MAC
    and all `allium:*` PDC. It verified and disarmed again (`charges=1, uses=2`).
  - **The moved setting:** spending its last charge set a recharge about 90 s away (88.4 s
    measured), so the migrated value is live.
- **Provider appearances on 1.21.8:** a fresh tool of each appearance is a signed
  `allium:mob_disarmer` with its provider's native ID, and each disarm spent exactly one charge:
  - Nexo: `mob_disarmer`, swapped to `_2-3`.
  - CraftEngine: `allium_gate:mob_disarmer`.
  - Oraxen: `Gate_Disarmer`.

## S10b: the same gate on the S10b jar (2026-11-12)

**PASS: 486 live checks, 0 failures**, all with Allium.jar `28cca311…`, the jar the Lazy Axe,
suppression and Obliterator gates ran on. The cases, lanes and check counts are the same as
the 2026-11-09 runs above. The details are in the
[rerun manifest](evidence/s10b-rerun-manifest.json), and the runs are kept in
`/mnt/storage/.allium-validation/20261112/s10-disarmer/`.

| Run | Lane | Checks |
| --- | --- | --- |
| `gaps-spigot` | Spigot 1.20.1 | 191 |
| `gaps-paper26` | Paper 26.3 + SF's GriefPrevention3D 19.0.5 | 207 |
| `upgrade-spigot` | Spigot 1.20.1: the S07 jar, then this jar | 33 |
| `quick-paper1218` | Paper 1.21.8-60, Nexo, CraftEngine and Oraxen | 55 |

- **What these runs cover:** the definition's own charges and radius (`allium:wand_disarmer`),
  the path-specific diagnostic text, the claim on 26.3, and the live upgrade.
- **The upgrade's S07 jar** was rebuilt from commit `daf11a8`. It is byte-identical to the
  `c877b783…` jar the first run used.
- **Paper 1.21.8:** it now runs from the official paperclip (build 60, checksum verified),
  seeded with the S05 lane's caches.

## Scope and limits

- Providers were exercised on 1.21.8 only, with the supplied Nexo/Oraxen builds and official CE
  26.9.1. CE items here have no reset updater, so the CE count defect is not involved.
- The click guard, legacy import, break chances, recharge expiry, bypass, `drop-rule: always`
  and the villager guarantee are covered by unit tests, not by live cases. In live double-clicks, the second click
  found nothing left to remove.
- Only upstream-compatible GriefPrevention API was used. GP3D 18.4.5 (S06a) and 19.0.5 (S10)
  were run; no separate upstream GriefPrevention run was made.
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
python3 tests/live/tools/disarmer/run.py --lane spigot-1.20.1 --run-name <name> \
  --upgrade-from /path/to/Allium-before-definitions.jar     # the upgrade scenario
python3 tests/live/tools/disarmer/run.py --lane paper-1.21.8 --run-name <name> --quick   # one disarm per appearance
```

The runner builds the fixture with `javac --release 17`. It copies only binaries, immutable
library caches and the retained provider settings, and never copies worlds, player data or keys.
It always stops its own client and server, and exits nonzero on any failed check.
