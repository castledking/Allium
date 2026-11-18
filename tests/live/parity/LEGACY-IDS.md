# Essentials numeric IDs

Commit `19186ad`: +10 units, 919 → 929; full check/shadowJar passes.

Artifact SHA256: `847bfaa7c0600b5071069971f0e8b8e5270f71670a2c4d0eaad28d96564f82cf`.

- `legacy-ids-release-spigot`: 47 checks passed; failures: 0. Evidence: `/mnt/storage/.allium-validation/20261117/s18-playerinfo/runs/legacy-ids-release-spigot/manifest.json`.
- `legacy-ids-release-paper`: 48 checks passed; failures: 0. Evidence: `/mnt/storage/.allium-validation/20261117/s18-playerinfo/runs/legacy-ids-release-paper/manifest.json`.

Spigot 1.20.1 and Paper 26.3, SQLite, real clients, owned loopback fixtures.
Numeric variants, spawners, eggs and native potion flags are verified; numeric IDs remain hidden in both item completion routes. Paper also includes the added grounded there/back round trip.
Earlier failing probes remain in the validation tree. The initial numeric probe reproduced 1:2 giving a slab. The first sign rerun left protection enabled during disabled-type checks; the corrected fixture checks those independently. No Vault release or deployment.
