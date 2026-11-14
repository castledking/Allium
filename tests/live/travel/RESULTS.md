# S15 homes and warps live results

**PASS**.

- Spigot 1.20.1 / Java 17: 12/12 cases and 406/406 checks passed; 0 unrun; all 9 server/client processes exited zero. Original manifest: `/mnt/storage/.allium-validation/20261113/s15-travel/runs/final-spigot-02/manifest.json` (SHA-256 `fbdfac7a715964ef8d77dd10869f12db93ea36a9fa0a517be941f86dd3a8075d`).
- Paper 26.3-141 / Java 25: 12/12 cases and 406/406 checks passed; 0 unrun; all 9 server/client processes exited zero. Original manifest: `/mnt/storage/.allium-validation/20261113/s15-travel/runs/final-paper-01/manifest.json` (SHA-256 `9a365904fad0a6c9012ad31fd16c2c4e7e55fe922ca81b7b6f0c0e20315c2ec2`).

Frozen jar SHA-256: `03b3ddb26127abd0ecb1cc58c7849f4eb38d161a5e7d593300f3f8c44b20337c`.

Both final lanes have identical hashes for all 265 frozen production/helper/client inputs, and identical compiled helper class bytes. Ports 25750/25751 are released.

Commands and completions travel through real protocol clients. Successful travel requires a fresh non-fixture PLUGIN event matching the current world UUID and XYZ. Refusals require bounded valid wire responses, stationary coordinates, and no physical teleport. Reloads retain the same ready service; restart checks include stopped SQLite rows. Final scans include scheduler, callback, client and shutdown failures.

Cases:

- default-and-multiple-home-crud
- highest-permission-limit-and-staff-max
- snapshot-bound-home-confirmation
- named-warps-permission-list-delete
- snapshot-and-revision-bound-warp-confirmation
- warp-list-tab-privacy-and-cap
- home-warp-shared-warmup-movement-cooldown
- warmup-warp-permission-and-revision-recheck
- saved-destination-unsafe-and-unloaded-world
- permissions-vanish-generic-refusal
- restart-home-warp-cooldown-persistence
- final-client-service-health

Retained failed attempts:

- `/mnt/storage/.allium-validation/20261113/s15-travel/runs/final-spigot-01/manifest.json` — original FAIL preserved (SHA-256 `3fa7d15eb4f0abe2fe77f3bb5639cfd598929dbcb79fec8ee5cb80e4c4483e51`).
  The first Spigot harness incorrectly required Bukkit isPermissionSet=false for default-true permissions. Raw effective permissions were true, all six frozen plugin.yml defaults were true, commands succeeded, and all processes exited zero. The corrected full runs require registered PermissionDefault.TRUE, effective permission=true, non-operator status and no explicit fixture attachment for that node. No original assertion or case was relabelled.

Paper native clients emitted 12 “moved too quickly” warnings. Actual world/XYZ and fresh matching PLUGIN events passed, with no decoder/transport errors. This is not a warning-free-client or performance claim.

Limits: SQLite; two supplied server versions only; no MySQL, Folia, provider plugins, pet teleport, offline restore, or cross-server claims.

[Original lane manifests and input hashes](evidence/s15-travel-manifest.json).
