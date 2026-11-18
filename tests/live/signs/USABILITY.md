# Sign usability regression

Commit `19186ad`: +10 units, 919 → 929; full check/shadowJar passes.

Artifact SHA256: `847bfaa7c0600b5071069971f0e8b8e5270f71670a2c4d0eaad28d96564f82cf`.

- `legacy-fixes-release-signs-spigot`: 381 checks passed; failures: 0. Evidence: `/mnt/storage/.allium-validation/20261117/s20-economy/runs/legacy-fixes-release-signs-spigot/manifest.json`.
- `legacy-fixes-release-signs-paper`: 381 checks passed; failures: 0. Evidence: `/mnt/storage/.allium-validation/20261117/s20-economy/runs/legacy-fixes-release-signs-paper/manifest.json`.

Spigot 1.20.1 and Paper 26.3, SQLite, real clients, owned loopback fixtures.
The sign gate retains the full S21 matrix plus disabled text, native editor suppression, colored repair responses, and the commented config checklist.
Earlier failing probes remain in the validation tree. The initial numeric probe reproduced 1:2 giving a slab. The first sign rerun left protection enabled during disabled-type checks; the corrected fixture checks those independently. No Vault release or deployment.
