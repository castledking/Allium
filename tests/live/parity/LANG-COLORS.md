# Language feedback color gate

Full check/shadowJar: **+4 units**, 936 → 940, zero failures/errors/skips.
The balance regression first failed on the prior raw-send implementation and
passes when the runtime uses the configured balance key and available funds.

Artifact SHA256: `25a838f06b6b36d8185833f7576b5345cb1d12a8889e76ee7d30b57e0efce33d`.

Native Spigot 1.20.1 and Paper 26.3, SQLite, real protocol clients:

- spigot: **61/61**; `/mnt/storage/.allium-validation/20261117/s18-playerinfo/runs/message-audit-release-spigot/manifest.json`.
- paper: **61/61**; `/mnt/storage/.allium-validation/20261117/s18-playerinfo/runs/message-audit-release-paper/manifest.json`.

New checks verify RGB colors for balance, economy errors, chat validation, /info
and /itemdb. Balance includes an operator-defined marker, proving the installed
language template is used. An installed former plain stock AFK notice is upgraded
with an original-file backup. Existing numeric/enchant/teleport/flight regressions
remain in the same gate. No production server, default port, push or deployment.

The preceding Spigot probe passed 61/61 on the pre-rename message keys; final
runs above use the reviewed semantic key names on the same frozen artifact.
