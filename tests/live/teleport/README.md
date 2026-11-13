# S13 live teleport gate

Preparation compiles a Java 17 fixture and checks the owned loopback ports without starting a
server. Live execution requires the parent's explicit freeze confirmation and exact JAR SHA-256.
This harness never runs Gradle, changes production source, commits, pushes, downloads a server,
or uses port 25565. Only `127.0.0.1:25740` (Spigot 1.20.1 / Java 17) and `25741`
(Paper 26.3-141 / Java 25) are permitted, under an exclusive owned-range lock.

```sh
python3 tests/live/teleport/run.py --suite spigot --run-name prepare-01 --prepare-only
python3 tests/live/teleport/run.py --suite spigot --run-name spigot-01 \
  --freeze-confirmed --allium-jar /absolute/parent-frozen.jar --sha256 <confirmed-sha256>
python3 tests/live/teleport/run.py --suite paper --run-name paper-01 \
  --freeze-confirmed --allium-jar /absolute/parent-frozen.jar --sha256 <confirmed-sha256>
# Optional focused supplement; its manifest explicitly contains one case, not fifteen.
python3 tests/live/teleport/run.py --suite paper --focus death-back --run-name paper-death-supplement \
  --freeze-confirmed --allium-jar /absolute/parent-frozen.jar --sha256 <confirmed-sha256>
```

Three real offline protocol clients (`TeleA`, `TeleB`, `TeleStaff`) and server console exercise the
commands. The console-only helper prepares terrain, player positions, permissions, vanish,
worlds, and fault hooks. API damage produces actual `EntityDamageEvent` and `PlayerDeathEvent`;
respawn uses client packets. Invulnerable flying fixture targets remain stable over hazards.
The helper observes coordinates, world UUIDs, block safety, command/move/teleport/damage/death
events, service readiness, and read-only SQL rows. It never initiates tested service operations.

Spigot uses the cached `minecraft-protocol` package. Paper uses the mail gate's raw protocol-777
client and server-exported packet catalog, with additional movement, respawn and relative-position
support. Native `STREAM_CODEC` output proves serverbound movement, teleport acknowledgement,
clientbound position layout, and respawn encoding; movement packets themselves use fixture-exported
native bytes. No guessed movement IDs or action enum ordinals are used on Paper. Runtime
libraries must match the exact Paper bundler library catalog, not a wildcard classpath. The test config enables the
explicit bounded destination-chunk load policy for saved locations after restart. Cached
Paper terrain is copied without player data; relocated 26.3 `world/players` is cleared explicitly.

Every tested command and completion travels through the client connection; console commands
travel through server stdin. A server-side `PlayerCommandPreprocessEvent` confirms command
arrival. Each successful teleport requires a fresh non-fixture `PLUGIN` event with matching destination
world UUID and coordinates, as well as the actual current position. Monotonic event sequence
numbers survive observation clears. Staff and console force checks require fresh completed
audits with exact actor, mover, target, command and reason fields. Complete bounded privacy response arrays retain raw components and exact resolved unavailable
messages. Pending-toggle cancellation is observed before accept with a 30-second expiry. Both
health scans latch the actual `An Allium ... task failed:` scheduler format.
A positive-control completion fences an omitted empty Spigot response.

Fifteen named cases cover requests/accept/tpahere/back, deny/expiry/cancel, actual movement and
damage cancellation, toggle cancellation, disconnect during warmup, lava/void/headspace/fall
refusal or bounded safe adjustment, tab/vanish, unloaded preferences, staff permissions/explicit
operator toggle bypass/audits/console, crossworld spawn/back, both death-back switch settings,
history-write retry, and restart persistence of back/spawn/toggle/cooldown. Write-fault recovery
must trip once, settle, preserve back, and never repeat the physical teleport. Restart evidence
includes an independent stopped SQLite snapshot as well as post-restart behavior.

A manifest records each case and assertion, hashes of the JAR/server/helper/source/client inputs,
exact Paper libraries, reload acknowledgements and observed settings, events, SQL snapshots,
and every process exit. Helper/client copies execute from the frozen run tree; original production
and helper/client source hashes are checked again at exit. All normal server/client exits must be
zero without forced termination. Unexpected fixture/task/event/callback errors and shutdown
storage failures remain failures even when subsequent cases pass. No unrun case passes.

Runtime evidence stays under `/mnt/storage/.allium-validation/YYYYMMDD/s13-teleport/runs/`.
`RESULTS.md` and `evidence/s13-teleport-manifest.json` contain the proportional aggregate.
This gate covers the two supplied server versions with SQLite. It does not claim MySQL,
Folia, selector syntax, economy charging, provider-plugin behavior, or cross-server teleportation.


## Recorded S13 evidence and explicit closure

The final full baselines are `final-spigot-02` and `final-paper-01`. Spigot's original
manifest passes. Paper's original manifest remains **FAIL**: its native hidden-command error
bundled the error and context into one component, and the second death setup occurred during
respawn protection. The latter did not exercise death-enabled behavior.

`paper-death-supplement-01` executes exactly one named live case, testing both death settings
with natural five-second waits, actual uncancelled damage/health zero/death events, real packet
respawns, and fresh matching back events. It never resets an invulnerability timer.

The artifact-specific `post_audit.py` reads the original manifests and frozen evidence. It checks
complete raw-component/NBT metadata and interactivity for privacy; aligns each terrain fixture
with server command-event sequence and a fresh requester wire-command window; validates the exact
Paper bundled-denial component with effective false permission, stationary position and no plugin
teleport; and requires the independent one-case death supplement. Only the precise recorded
Paper denial/death failures and their derived summary may be resolved. Unknown failures, missing
cases, duplicate names and any other status block the audit. Original manifest bytes and original
source/helper/client freeze checks are retained.

```sh
python3 tests/live/teleport/post_audit.py \
  --spigot /absolute/final-spigot-02/manifest.json \
  --paper /absolute/final-paper-01/manifest.json \
  --supplement /absolute/paper-death-supplement-01/manifest.json \
  --output /absolute/post-audit/final.json
python3 tests/live/teleport/test_post_audit.py \
  --spigot /absolute/final-spigot-02/manifest.json \
  --paper /absolute/final-paper-01/manifest.json \
  --supplement /absolute/paper-death-supplement-01/manifest.json \
  --output-root /absolute/post-audit/negative-controls
python3 tests/live/teleport/report.py \
  /absolute/final-spigot-02/manifest.json /absolute/final-paper-01/manifest.json \
  --supplement /absolute/paper-death-supplement-01/manifest.json \
  --post-audit /absolute/post-audit/final.json \
  --negative-controls /absolute/post-audit/negative-controls/negative-controls.json \
  --superseded /absolute/final-spigot-01/manifest.json
```

Negative controls mutate disposable copies only. Removing a fresh requester refusal, injecting
hover metadata coherently into both wire and captured privacy data, or changing Paper's denial
translation coherently must each fail at the relevant proof. The aggregate embeds original
manifests unchanged, the separate audit and supplement, and explicit effective case resolutions.
It never claims the original Paper assertions all passed.

Current runner fixes for future runs include natural death waits, raw privacy comparison, fresh
requester unsafe-response assertions, and Paper's exact bundled native denial. Those corrections
were made after the recorded full servers stopped; their hashes are separate from the original
executed snapshots. Paper clients can still cause `moved too quickly` warnings. This gate proves
actual coordinates/worlds and native codec/event behavior, not a warning-free client or performance.
