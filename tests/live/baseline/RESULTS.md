# Baseline gate: text formatter and item contract on five lanes

**PASS on 2026-11-07: 155 live checks, 0 failures**, on Spigot 1.20.1, Paper 1.20, Paper 1.20.6,
Spigot 26.3 and Paper 26.3, all with Allium.jar SHA-256 `57ff304f…`. The gate re-records two
earlier gates whose lane runs were kept only in commit messages:
- **Static text formatter (S01.5a):** 55 checks; see the
  [text manifest](evidence/s015a-text-manifest.json).
- **Item contract (S04):** 100 checks; see the [contract manifest](evidence/s04-contract-manifest.json).

Run logs and summaries are kept on persistent storage in
`/mnt/storage/.allium-validation/20261107/baseline-gate/`. The server jars are in
`/mnt/storage/.allium-validation/server-jars/`.

| Lane | Server | Java | Client | Checks |
| --- | --- | --- | --- | --- |
| Spigot 1.20.1 | 3871-Spigot-d2eba2c (BuildTools) | 17 | minecraft-protocol 1.68.0, protocol 763 | 31 |
| Paper 1.20 | git-Paper-17 | 17 | minecraft-protocol 1.68.0, protocol 763 | 31 |
| Paper 1.20.6 | 1.20.6-151 | 21 | minecraft-protocol 1.68.0, protocol 766 | 31 |
| Spigot 26.3 | 4664-Spigot-bed90fc-e89124d (BuildTools #201) | 25 | owned raw client, protocol 777 | 31 |
| Paper 26.3 | 26.3-141 | 25 | owned raw client, protocol 777 | 31 |

## How it runs

- **Server:** Allium runs with `settings.locale: es_ES`.
- **Test lang entry:** each lane's own copy of `lang/es_ES.yml` is the shipped file with one test
  entry. `player.heal` becomes the text
  `{prefix} <gradient:#ff0000:#0000ff>Curado por completo</gradient> &#00ff88ok`, with the sound
  `minecraft:entity.experience_orb.pickup` at volume 0.5.
- **Client:**
  - A real protocol client joins and decodes every chat packet: JSON components on 1.20.1, and
    network NBT from 1.20.3.
  - It records the visible text and one resolved colour per character, so Paper's Adventure path
    and Spigot's legacy `§` path can be compared exactly. It also records every sound packet.
- **Fixture:** the test-only `BaseGate` fixture runs Allium's item contract on the server's own
  item, metadata and PDC implementation. It never changes Allium's configuration.
  - It registers a fixture-only item provider, `basegate`, so appearance swaps can be exercised
    on lanes without Nexo, CraftEngine or Oraxen.
  - It stores signed items in a chest. The harness restarts the server and reads them back.

## Text formatter (S01.5a)

On every lane:
- **Boot:** Allium and the fixture enabled, and the `es_ES` locale loaded, both on the first boot
  and after the restart.
- **Jar:** `Allium.jar` contains no Takion (`me.croabeast`) and no Adventure (`net.kyori`)
  classes.
- **Shipped translation:** `feed BaseBot` arrived as "[Allium] Has sido alimentado.". Every
  visible character had the colour of its `&7` / `&d` / `&a` code.
- **Formatting:** `heal BaseBot` arrived as "[Allium] Curado por completo ok".
  - Each of the 17 gradient letters had its own colour, from `#ff0000` through to `#0000ff`, with
    red falling and blue rising at every step.
  - "ok" had the hex colour `#00ff88`.
- **Sound:** Allium played the message's sound itself: `minecraft:entity.experience_orb.pickup`
  at volume 0.5 and pitch 1.0. The pitch was omitted, so it came from `sounds.defaults.pitch`.
- **Identical results:** the decoded text and every visible character's colour were identical on
  all five lanes, Adventure (Paper) and legacy (Spigot) alike, for both messages.

## Item contract (S04)

Each lane ran 15 contract checks on fresh stacks and 5 persistence checks across a restart.

- **Create:** `allium:mob_disarmer` carries schema version 1, its behavior, an instance UUID and
  a `v2:` signature.
- **Round trips:**
  - `ItemRef` serialize/deserialize round-trips, and rebuilds the same identity.
  - Bukkit's YAML serialization keeps the signed identity: the same UUID and MAC, still verified.
  - `clone()` and `cloneItem` keep a verified identity.
- **State:**
  - `updateState` re-signs: the new state verifies, with the same UUID and a new MAC.
  - A state edited in PDC without re-signing is rejected, and resolves as a plain golden hoe.
  - `updateState` refuses to launder the rejected stack.
- **Signatures (`allium:handcuffs`, privileged):**
  - Minted signed.
  - Rejected without a signature, with a changed instance UUID (tampered), or when signed with an
    unknown key ID.
  - Rejected when forged under the active key ID with another secret.
- **Appearance swap:** swapping to `basegate:cuffs` (an iron shovel) keeps the behavior and the
  instance UUID, and re-signs.
- **Strict carriers:**
  - A disarmer is not `minecraft:golden_hoe`.
  - A plain golden hoe is not a disarmer, and neither is one with the disarmer's name and model
    data.
- **No AIR fallback:**
  - `no_such_item_xyz` and `allium:no_such_item` resolve to nothing; the second reports
    `UNKNOWN_ID`.
  - The bare name `stone` resolves to `minecraft:stone`.
- **Restart:** five stored stacks read back field for field identical: material, appearance,
  behavior, state, UUID, MAC and schema version.
  - The full disarmer, the spent (re-signed) disarmer, the handcuffs and the swapped handcuffs
    still verify.
  - The disarmer whose state was edited without re-signing is still rejected.
  - On 1.20.6 and 26.3 this crosses the NBT-to-data-component storage boundary.

## Scope and limits

- **Providers:** this gate uses no item providers. Real Nexo, CraftEngine and Oraxen items are
  covered by the [S05 matrix](../../../docs/s05-provider-matrix.md), the
  [S06a gate](../tools/disarmer/RESULTS.md) and the [item-library gate](../items/RESULTS.md).
- **Message sounds:** the shipped lang files declare none, so the sound case uses the test entry.

## Rerun

```sh
./gradlew build
python3 tests/live/baseline/run.py --lane <spigot-1.20.1|paper-1.20|paper-1.20.6|spigot-26.3|paper-26.3> --run-name <name>
```

- **Ports and cleanup:** the runs use owned loopback ports 25660–25664, delete their copied server
  files afterwards and exit nonzero on any failed check.
- **Server jars:** they come from `/tmp/allium-server-cache/`. Restore them from
  `/mnt/storage/.allium-validation/server-jars/` after a reboot. The Paper 26.3 lane uses the
  provider-gate lane template.
- **Pinned jar:** pass `--allium-jar <path>` to test a pinned copy.
