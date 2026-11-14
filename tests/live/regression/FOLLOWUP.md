# Requested live follow-up

Both supplements use frozen Allium SHA256 `ea3ea166d87acd19afb50a742bbdfa52f2add115be2f8b319d8d1364ad4631e2`, excluding S19 edits. Unit-test delta: **0**.

- **Paper 26.3: PASS 54/54**, including ten individually named muted refusals with recipient silence and ten unmuted actual-recipient deliveries: `/me`, `/minecraft:me`, `/say`, `/minecraft:say`, `/tell`, `/minecraft:tell`, `/w`, `/tm`, `/teammsg`, `/minecraft:teammsg`. Team membership is established and acknowledged separately for each player. Evidence: `/mnt/storage/.allium-validation/20261114/s17-moderation/runs/paired-controls-paper-r3/manifest.json`.
- **Paper 1.21.8-60, Java 21, real supplied Nexo: PASS 66/66**. Nexo `1.30-dev-36f4ce686`, SHA256 `5f180b43dd464eea7d5f2a41833c194a5e4da6fe00bb09084bf32e059fb71f6b`. Stub registration is disabled. Native `NexoItems.idFromItem` identifies the kit-delivered `ruby_sword`. The full kit gate includes native capture, signed items, reload, permissions, inventory refusal, disconnect and both crash-recovery boundaries. Evidence: `/mnt/storage/.allium-validation/20261114/s16-kits/runs/real-nexo-paper1218-r2/summary.json`.

Muted-command rules resolve through Bukkit's CommandMap and compare canonical native executable objects by reference. Vanilla wrappers expose Brigadier nodes; redirects are followed, then the executable callback is selected directly or through a deterministic child branch. Paper's copied bare/namespaced literal roots retain that callback or their argument children by reference, so both resolve to the same identity. Ordinary plugin commands retain command-object identity. Labels select commands and a deterministic branch; label-string equality does not establish equivalence. Production fix: `1e92082` (two unit regressions; 550 → 552).

Earlier failed fixture attempts remain preserved: the first control run hit a dead player's chat restriction; the second exposed invalid two-target team syntax; the initial Nexo attempt hit the protocol client's old version allowlist. None is counted as passing evidence.

Reproduction: moderation run.py accepts `--suite paper --paired-controls-only`; kits run.py accepts `--suite paper-nexo`. Supply the frozen jar, SHA256 and fresh run name. The Nexo lane defaults to the retained supplied template and rejects a different provider hash. Python/Node syntax and diff whitespace checks passed.

[Exact checks, hashes and preserved failures](evidence/followup-controls-nexo.json). [Accepted 20-suite sweep](RESULTS.md).

Spigot 1.20.1 paired controls: **PASS 58/58** including all ten muted refusals and ten actual-recipient unmuted deliveries. Its namespaced vanilla commands additionally check literal permissions such as `minecraft.command.minecraft:say`; the fixture grants and records those explicitly. Default CommandMap permission metadata still advertises `minecraft.command.say`. The first two Spigot attempts correctly failed the positive control before those native permission grants. Evidence is included in the linked JSON.

Remote history audit: all 30 rewritten hashes are reachable from remote `feat/recode`; none of the 30 old hashes is reachable. [Audit](evidence/remote-history.json).
