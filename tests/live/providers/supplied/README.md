# Supplied native runtime checks

These S05 checks use the user's **full** Nexo `1.30-dev-36f4ce686` and Oraxen
`1.220.1` distributions. Their versions are explicitly accepted; other 1.30/1.220
versions remain unverified. The compile-only API pins remain Nexo 1.29.1 and
Oraxen 1.211.0, whose build/identity/event methods were checked against the supplied
bytecode. Oraxen's changed readiness representation has its own version-specific
probe. Provider jars are not committed or bundled.

| Runtime | SHA-256 |
| --- | --- |
| Nexo 1.30-dev-36f4ce686 | `5f180b43dd464eea7d5f2a41833c194a5e4da6fe00bb09084bf32e059fb71f6b` |
| Oraxen 1.220.1 | `9994da9b78f2e86bea657eeaff5bff01865709d46a3b5828fae62bbc5aa17df2` |
| CE Paper 26.9.1 | `021260c87e3546730d321f4360b1744e6e33caa1d68e98ae30dcdbbdc347ae0f` |

## Actual player inventory

`run.cjs` uses a real offline protocol 772 client on loopback, Paper 1.21.8 build60
and Java 21. The Oraxen-only case checks case-sensitive native IDs, fresh instances,
signed charges changed from 3 to 2 before the checkpoint, reload, native update,
actual inventory reread, restart, and runtime disable. In the combined case,
Nexo, CE, Oraxen and RPM 2.4.6 coexist. Oraxen uses player slot 2, and the shared
ProviderGate uses slots 0/1. All three actual inventory items keep their exact
signatures, UUIDs and charges through reload/native updates and restart.

The combined case requires disabling Nexo's automatic Oraxen migration in the
**disposable fixture** before boot:

```yaml
# plugins/Nexo/converter.yml
oraxenConverter:
  convertItems: false
  convertResourcePack: false
  convertSettings: false
  convertFurnitureOnLoad: false
```

An initial run with the generated default converter enabled failed native Oraxen
identity after restart. Allium PDC/signatures survived, but the converter changed
provider ownership. A fresh run differing in that migration policy passes. This
configuration is a coexistence prerequisite, not an Allium state re-sign or a
silent edit to production settings. Pack generation/delivery/hosting remain off;
RPM presence proves coexistence, not pack visuals.

Only single-item native resets are used here. The independent CE trigger suite
finds loss of multi-item quantities; these passes do not override that failure.

## Reproduction

Build `../oraxen/plugin/gate/OraxenGate.java` against the built Allium jar and
Spigot 1.20 using JDK 17 `--release 17`; package with its plugin.yml. Also build the
existing ProviderGate fixture. The Node runner requires `minecraft-protocol@1.68.0`
through `MC_PROTOCOL_MODULE`, defaulting to the retained disposable tool cache.
Python preparation requires PyYAML. No production data or secrets are used.

Write a manifest with `name`, `directory`, `port` (25621 or 25622), `java`,
`server`, `combination`, `allium`, `oraxen`, `oraxen_fixture`, `oraxen_settings`
and optional immutable `library_cache`. Combined runs also need `nexo`,
`craftengine`, `rpm`, `provider_fixture` and an owned stopped `native_template`
whose Nexo generation/delivery and RPM hosting are already disabled. The Oraxen
settings input is the verified 1.220.1 settings file; preparation disables its pack
generation/delivery and default assets before boot. No native databases, keyrings,
worlds or player files are copied.

```sh
python3 tests/live/providers/supplied/prepare.py /absolute/path/manifest.json
node tests/live/providers/supplied/run.cjs /absolute/path/manifest.json
```

The lane must be new and below `/tmp/allium-s05-YYYYMMDD/supplied/runs/`. The
runner starts/stops both boots itself, saves complete logs and a result, and
requires clean JVM exit. It does not require operator permissions for GateBot.
Before each Nexo/CE action it waits for all three providers to be READY and requires
the individual signature/state/UUID/count assertions for both providers. A DONE
marker alone cannot pass a skipped check. Four negative/positive guard tests run
with `node --test tests/live/providers/supplied/verification.test.cjs`.

The separate `../lanes/` fixture performs native creation/YAML/reload/restart
checks across server versions. Those are headless checks, distinct from the
actual inventory cases above. Input hashes, exact scope and filtered logs are
retained under `../evidence/`.
