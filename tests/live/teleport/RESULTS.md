# S13 teleport live results

**Effective gate: PASS**, using the original live evidence, an explicit retained-evidence audit, and one focused live supplement. Original manifests are unchanged.

- Spigot 1.20.1 / Java 17: original **PASS**, 15/15 cases, 117/117 checks.
- Paper 26.3-141 / Java 25: original **FAIL retained**, 13/15 cases, 112/115 checks. The staff denial message assertion and second death setup failed; the derived all-cases summary also failed.
- Paper death supplement: **PASS**, exactly 1 named case exercising both false/true settings, 22/22 checks.
- Post-audit: **PASS**, 84 retained-evidence checks. All three negative controls reject missing requester denial, injected hover metadata, and altered Paper denial translation.

The original Paper staff check expected a standalone unknown-command message. Paper bundled the exact translation, newline, and suggest-command context into one component. The post-audit validates that complete bounded raw response, effective permission=false, stationary coordinates, and no non-fixture PLUGIN event. It resolves this precise harness assertion only.

The second Paper damage attempt occurred during vanilla respawn protection and left health at 20; no death-enabled behavior was claimed from it. The one-case supplement waited five seconds naturally before each damage attempt, then required uncancelled damage, health 0, actual PlayerDeathEvent, real packet respawn, and fresh destination-matching /back PLUGIN events for both settings. No invulnerability timer was reset.

Both lanes’ post-audits compare complete recursively normalized raw components/NBT, interactive and overlay metadata for vanished/unknown responses. Each of four fresh unsafe refusals is correlated to its actual terrain fixture window, server command event sequence, and client command window. Global message counts alone cannot pass.

All recorded server/client processes exited zero without forced termination. Original source/client/helper/dependency freeze checks and callback/shutdown scans remain intact. The two full baselines have identical harness source hashes; the focused supplement records its separate corrected harness snapshot, with the same production JAR and production source hash set.

Frozen JAR SHA-256: `6ddd2671bf2f8c98fb87aeb58f77f26102bfbdc0a9dc8ff74082f3292cad2c52`.

Original evidence:

- `/mnt/storage/.allium-validation/20261113/s13-teleport/runs/final-spigot-02/manifest.json` — SHA-256 `ec8dd5a517a6b0157a7eaca36fb0d923c04b4fa12d5319a5a416b056e32bc996`.
- `/mnt/storage/.allium-validation/20261113/s13-teleport/runs/final-paper-01/manifest.json` — SHA-256 `943f76781044605ead13364fdea2dfee5c00c51ebaf3afc21f2bbad0addc759f`.
- `/mnt/storage/.allium-validation/20261113/s13-teleport/runs/paper-death-supplement-01/manifest.json` — SHA-256 `01dc7b1444c24f096d4ea6d9cc022fb8d765be969e1aa361738ca846bb090f68`.

Effective case coverage:

- spigot: request-accept-tpahere-back — PASS (original live case).
- spigot: deny-expiry-cancel — PASS (original live case).
- spigot: real-movement-cancels-warmup — PASS (original live case).
- spigot: real-damage-cancels-warmup — PASS (original live case).
- spigot: toggle-off-cancels-pending — PASS (original live case).
- spigot: offline-mid-warmup — PASS (original live case).
- spigot: unsafe-lava-void-headspace-fall — PASS (original live case).
- spigot: tab-vanish — PASS (original live case).
- spigot: unloaded-preferences-generic-refusal — PASS (original live case).
- spigot: staff-distinct-permission-toggle-bypass-audit-console — PASS (original live case).
- spigot: crossworld-back-spawn — PASS (original live case).
- spigot: death-back-config-switch — PASS (original live case).
- spigot: history-write-fault-retry — PASS (original live case).
- spigot: restart-persistence-back-spawn-toggle-cooldown — PASS (original live case).
- spigot: final-callback-client-service-health — PASS (original live case).
- paper: request-accept-tpahere-back — PASS (original live case).
- paper: deny-expiry-cancel — PASS (original live case).
- paper: real-movement-cancels-warmup — PASS (original live case).
- paper: real-damage-cancels-warmup — PASS (original live case).
- paper: toggle-off-cancels-pending — PASS (original live case).
- paper: offline-mid-warmup — PASS (original live case).
- paper: unsafe-lava-void-headspace-fall — PASS (original live case).
- paper: tab-vanish — PASS (original live case).
- paper: unloaded-preferences-generic-refusal — PASS (original live case).
- paper: staff-distinct-permission-toggle-bypass-audit-console — PASS (retained exact Paper bundled-denial audit; original case FAIL preserved).
- paper: crossworld-back-spawn — PASS (original live case).
- paper: death-back-config-switch — PASS (new one-case Paper live supplement; original missing second death preserved).
- paper: history-write-fault-retry — PASS (original live case).
- paper: restart-persistence-back-spawn-toggle-cooldown — PASS (original live case).
- paper: final-callback-client-service-health — PASS (original live case).

Limits:

- SQLite on Spigot 1.20.1 / Java17 and Paper26.3-141 / Java25 only; no MySQL, Folia, selectors, economy, provider plugins, or cross-server teleportation.
- Paper raw clients emitted moved-too-quickly warnings; actual coordinates, world UUIDs, fresh PLUGIN events and native codec proofs were checked. No warning-free-client or performance claim.
- The failed first Spigot attempt is preserved outside the repository and is not counted as a final pass.
- Current runner corrections for future runs are recorded separately; they are not presented as code executed by the original full runs.

[Original manifests, explicit resolutions, audit inputs/hashes, negative controls and effective coverage](evidence/s13-teleport-manifest.json).
