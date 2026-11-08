# S09: minimal versioned addon API gate

**PASS on 2026-11-08: 38 live checks, 0 failures**, 19 on each of Spigot 1.20.1 and Paper 26.3,
with Allium.jar `70474bc6…` and `Allium-API-1.0.0.jar` from the same build. Unit tests: 142
plugin, 67 core and 4 API, 0 failures. Every check and the evidence are in the
[manifest](evidence/s09-manifest.json). Run logs and summaries are kept in
`/mnt/storage/.allium-validation/20261108/s09-addon-api/`. The developer documentation is
[docs/addon-api.md](../../../docs/addon-api.md).

| Lane | Server | Client | Checks |
| --- | --- | --- | --- |
| Spigot 1.20.1 (3871-d2eba2c), Java 17 | with Allium, then without | minecraft-protocol 1.68.0, protocol 763 | 19 |
| Paper 26.3-141, Java 25 | with Allium, then without | owned raw client, protocol 777 | 19 |

## The test addons

All three are compiled against `Allium-API-1.0.0.jar` and spigot-api 1.20.1 only, never against
`Allium.jar`, so they can reach nothing but the API.

| Addon | `plugin.yml` | What it does |
| --- | --- | --- |
| AddonGate | `depend: [Allium]`, `allium-api: "^1.0"` | It defines `allium:gate_wand`: a stick with a gradient name, model data 7700001, signed state `uses=3`. It registers `/addongate` from a command spec, which declares no permission in `plugin.yml`, so Allium enforces `addongate.use`. It also schedules a repeating server task that stops itself after 5 runs, and one delayed async task. |
| FutureAddon | `allium-api: "^2.0"` | Tries to connect. |
| ShadedAddon | `allium-api: "^1.0"` | Its jar bundles the 26 API classes, as `implementation`/shadow instead of `compileOnly` would. |

## What each lane showed

- **Jars:**
  - `Allium-API-1.0.0.jar` holds only API types: 26 entries, all from the api package, the
    command specs, `TaskScheduler` and `TaskHandle`. Its manifest carries
    `Allium-API-Version: 1.0.0`.
  - `Allium.jar` contains no addon classes, and it provides the API classes at runtime.
- **Startup:** Allium logged "Allium API 1.0.0 ready; addons: AddonGate (^1.0)".
- **Refused addons:** each was refused before it enabled, with its diagnostic:
  - FutureAddon: "needs Allium API ^2.0 (>=2.0.0 <3.0.0), but this Allium provides API 1.0.0…".
  - ShadedAddon: "bundles its own copy of the Allium API (…AlliumApi, …CommandSpec,
    …TaskScheduler). … rebuild it with the Allium API as compileOnly, never shaded."
- **Refusal in practice:**
  - FutureAddon's `connect` threw `AddonRefusedException` with the same message.
  - ShadedAddon's own copy of `AlliumApi.get()` found no service.
  - Allium disabled both right after they enabled.
- **Allium and AddonGate kept running.** AddonGate's plugin listing showed `Allium: true`,
  `AddonGate: true`, `FutureAddon: false`, `ShadedAddon: false`.
- **Scheduler:** the addon's server task ran 5 times on the server thread and then stopped itself.
  Its async task ran on `Allium-Timer`.
- **Text:** the addon's reply went through Allium's text API.
- **Items:**
  - `addongate mint` created `allium:gate_wand` through the API. Allium's own `/give` gave one too.
  - Both identified as `allium:gate_wand`, with behavior `addongate:gate_wand`, state `uses=3`,
    model data 7700001 and the gradient name "Gate Wand".
  - A plain stick has no behavior, and an undefined `allium:` id is `UNKNOWN_ID`, with no air
    fallback.
- **Commands:** a non-op player typing `/addongate status` got Allium's "You don't have
  permission to do that.", and the handler never ran.
- **Shutdown:** Allium released AddonGate's connection (1 item, 1 command, its scheduled work).
- **Without Allium:** with only AddonGate installed, Bukkit refused it with its own message.
  - Spigot 1.20.1: `UnknownDependencyException: Unknown dependency Allium. Please download and
    install Allium to run this plugin.`
  - Paper 26.3: `Unknown/missing dependency plugins: [Allium]. Please download and install these
    plugins to run 'AddonGate'.`

**Found and fixed by this gate:** an addon item given through Allium's `/give` arrived without its
default state. `ItemService` looked definitions up by the `allium:` behavior prefix, and an addon's
behavior is `<addon>:<id>`. It now looks them up by behavior. A unit test covers both ways of
creating the item, and also unregistering one. On this jar, the item-library gate passed again
(31, 43 and 31 checks on Spigot 1.20.1, Paper 1.21.8 with all providers, and Paper 26.3), and so
did the baseline gate on Paper 26.3 (31 checks).

## Scope and limits

- **Surface:** the API is what Fun Items needs: item definitions with create/identify, command
  specs, the scheduler boundary and text. It adds nothing else until a consumer needs it.
- **Enabling:** Bukkit cannot veto another plugin's enable. A refused addon's `onEnable` runs, its
  `connect` fails, and Allium disables it immediately afterwards.
- **Restart-only:** installing, updating or removing an addon needs a restart, as documented.
  Allium checks plugins once, when it enables.

## Rerun

```sh
./gradlew build
python3 tests/live/addons/run.py --lane spigot-1.20.1 --run-name <name>
python3 tests/live/addons/run.py --lane paper-26.3 --run-name <name>
```

The runs use owned loopback ports 25665–25666. They boot a lane with Allium and then one without
it, delete their copied server files and exit nonzero on any failed check. Pass `--allium-jar`
and `--api-jar` to test pinned copies.
