# Recovered regression history

These are historical results, not acceptance evidence for the fixed artifact. All 20 suites are being rerun on frozen `ea3ea166d87acd19afb50a742bbdfa52f2add115be2f8b319d8d1364ad4631e2`.

On `5ab6b1820b4e67cad32b7a77066f7eff9a0c83697b15c581af2645a308675260`:

- Mail: Spigot/Paper 128/128 each.
- Teleport: Spigot/Paper 123/123 each.
- Travel: Spigot/Paper 406/406 each.
- Kits: Spigot/Paper 65/65 each; two-server shared MySQL 11/11.
- Moderation: Spigot 184/184; MariaDB 73/73; Paper **FAIL 133/134**.

On older `8ca5c87cb40012548f77bfdec909ee4a0aee39f865d32c64ded535cd73328ed8`:

- Preferences: Spigot/Paper 54/54 each.
- Messaging: Spigot/Paper 24/24 each.
- SocialSpy: Spigot/Paper 367/367 each.
- Player info: Spigot/Paper 33/33 each.

Paper's failure was a real muted-command escape. In `reg-060009-paper/paper-26.3-server.log`, the server applied `mute ModNorm 25s Spamming global chat` at 06:01:02. `/me waves silently` was blocked with 22 seconds remaining at 06:01:05. `/minecraft:me namespaced me attempt` was issued and publicly delivered at 06:01:06. Its client log contains `BASEG-RAWCHAT` with the escaped text. The gate then timed out waiting for `You are muted (`. Original evidence root: `/mnt/storage/.allium-validation/20261114/s17-moderation/runs/reg-060009-paper`.

The parent identified Paper's copied Brigadier literal roots and fixed command identity using the shared native executable. Regression work changed no production code. Private focused probes `identity-repro-1457` and `identity-repro-1459` did not establish node identities: the first had a fixture compilation error; the second expected the wrong temporary-mute acknowledgement. Neither is reported as a passing gate.

Unit totals corrected by parent: 549 before the mail regression, 550 at `506c0dd`, and 552 after the two bridge regressions; harness-only unit delta **0**.

[Exact paths, hashes, counts, and preserved failure](evidence/recovered-history.json).
