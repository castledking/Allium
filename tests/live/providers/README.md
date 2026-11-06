# Provider gate fixture

This Bukkit plugin is validation-only and implements no tool/gameplay actions.
Compile `plugin/gate/ProviderGate.java` with Java 17, `--release 17`, Spigot API
1.20 and the built Allium jar. Package it with `plugin/plugin.yml` as
`ProviderGate.jar`, installed only in a stopped, loopback/offline disposable server.
Never deploy it to SF production.

Use only owned ports 25589/25594/25595, fresh/minimal provider definitions, and a
local test keyring. Do not copy production player/world data or credentials.
Disable Nexo pack generation/dispatch and RPM hosting before first boot; this
checks item APIs and plugin coexistence, not pack delivery. The raw 26.3 helper in
`../command-client/raw-26.cjs` can maintain a real GateBot connection on 25589 with
`PROVIDER_GATE_MODE=1`; this mode is transport support, not a standalone passing
test. Install the ClientGate fixture to dump that server's exact packet IDs first.

Fixture item inputs:

```yaml
# plugins/Nexo/items/allium_gate.yml
gate_wand:
  material: BLAZE_ROD
  itemname: Allium Gate Wand
```

```yaml
# plugins/CraftEngine/resources/allium_gate/pack.yml
namespace: sf_ores
author: AlliumGate
version: 1.0
```

```yaml
# plugins/CraftEngine/resources/allium_gate/configuration/items.yml
items:
  sf_ores:silver_ingot:
    material: iron_ingot
    data:
      item_name: Allium Gate Silver
```

Commands from the disposable console:

1. `providergate status`: records initial/loading/ready states. Real Nexo pre-load
   and CE completion events assert PROVIDER_LOADING; provider-ready checks are deferred.
2. `providergate smoke`: native creation and signed identity without a player,
   useful for each-provider-alone checks.
3. With actual GateBot connected and operator, `providergate create`: puts signed
   items with charges=2 into real player inventory slots 0 and 1; records UUID/signature.
4. Change the definitions' carrier materials, add a *higher* CE updater version
   with the retention recipe in `docs/s05-provider-matrix.md`, then run
   `nexo reload items` and `ce reload`.
5. `providergate verify`: compares the existing inventory items' exact
   signatures/UUIDs/charges and verifies behavior. Fresh creations must use the new
   definitions. Nexo may already update the held item automatically.
6. `providergate nativeupdate`: invokes each actual provider updater, replaces
   and rereads the player inventory stack, and checks the same invariants. CE must
   report that its updater actually ran. It does not call Allium refresh/re-sign.
7. `providergate checkpoint`: validates both signed items and saves expected
   signatures/UUIDs/amounts in the fixture's local config. Stop the client and
   server, restart with the same test keyring/world, reconnect GateBot, and run
   `providergate verify` to check actual persisted inventory against that baseline.
   Do not run `create` or `checkpoint` between the restart and verification.
8. `providergate disable Nexo`, then `providergate status`, demonstrate ABSENT.
   Do the same for CE only at the end: CE intentionally stops a server when
   disabled at runtime. This is failure containment, not supported hot reloading.
9. Stop the test client and all disposable servers after collecting logs.

Only S05 pass/status lines are retained under `evidence/`; complete raw server
logs remain in `/tmp/allium-s05-20261105/provider-gate/`. The checked-in matrix
separates API pins, actual runtime versions, unit fixtures, native runs and
unverified/blocked cases. A missing full Oraxen artifact is not simulated by
adding a descriptor to an API jar.
