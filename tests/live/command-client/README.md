# Command-map client gate

Validation-only Bukkit plugin and protocol clients; never install the fixture on a
production server. Use an offline, loopback-only disposable server and ports
25590–25592. Core Allium is the only production plugin needed.

Compile `plugin/**/*.java` with Java 17, `--release 17`, Spigot API 1.20 and the
built `Allium.jar`; package the classes with `plugin/plugin.yml` as `ClientGate.jar`.
Install it and Allium into a stopped disposable lane. Disable the old
`SmokeTestHarness`: it replaces the player list and shuts down automatically.
Disable whitelist/enforce-secure-profile in the disposable lane. Do not copy
production player data or secrets.

Install the pinned Node dependency with `npm install` in this directory, then:

```sh
node run.cjs 1.20.1 /absolute/disposable/spigot-lane /path/to/java17 /path/to/spigot-1.20.1.jar 25590
node run.cjs 1.20.6 /absolute/disposable/paper-lane /path/to/java21 /path/to/paper-1.20.6.jar 25591
```

`MCPROTOCOL_MODULE` can identify an already installed `minecraft-protocol` package.
The runner starts/stops its own server, connects `GateBot`, makes the test player
operator, checks `gm` and `allium:gm` suggestions, registers a competing plugin
command, calls Allium's real bridge, and verifies a new wire command tree plus the
competitor's suggestions while still connected. It writes structured evidence and
server logs in the disposable lane. The fixture records the actual guarded map
accessor and Paper's wrapped Brigadier command owner.

For Paper 26.3, the pinned library has no schema. Start a loopback/offline server
manually with Java 25 on 25592 and run these commands in its console:

```text
whitelist off
op GateBot
clientgate wireids ids
```

Then `node raw-26.cjs /absolute/disposable/paper26-lane 25592`. This minimal client
uses protocol 777 and packet IDs read from the exact server's `ProtocolInfo`
metadata by the fixture. It performs login/configuration, observes real command
packets and sends actual suggestion requests; it does not render a world or
validate graphical UI behavior. Stop the server after the check.

The JSON files in `evidence/` record the successful checks on Spigot 1.20.1,
Paper 1.20.6 and Paper 26.3 (141/54024d3). They are protocol observations, not
mock-player claims. These checks deliberately yield commands for the remainder
of a server session: restart the disposable lane before repeating a run.
