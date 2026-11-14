# Fixed-artifact regression sweep

**PASS — 20/20 suites; 2852/2852 checks.**

Frozen jar SHA256: `ea3ea166d87acd19afb50a742bbdfa52f2add115be2f8b319d8d1364ad4631e2`.

The parent built isolated HEAD `506c0dd` plus the command-identity fix and two regression tests. These runs use that archive; concurrent S19 source edits are excluded.

- preferences: spigot-1.20.1 54/54 PASS; paper-26.3 54/54 PASS.
- messaging: spigot-1.20.1 24/24 PASS; paper-26.3 24/24 PASS.
- socialspy: spigot-1.20.1 367/367 PASS; paper-26.3 367/367 PASS.
- mail: sqlite-spigot 128/128 PASS; sqlite-paper 128/128 PASS.
- teleport: spigot 123/123 PASS; paper 123/123 PASS.
- travel: spigot 406/406 PASS; paper 406/406 PASS.
- kits: spigot 65/65 PASS; paper 65/65 PASS; shared-mysql 11/11 PASS.
- moderation: paper 184/184 PASS; spigot 184/184 PASS; mariadb 73/73 PASS.
- playerinfo: spigot 33/33 PASS; paper 33/33 PASS.

Shared kit ownership: server B leaves server A's `CLAIMING` row untouched and refuses both `releaseClaim` and `finishClaim`; server A recovers and delivers once.

The initial fixed-jar Paper messaging run passed its 24 functional checks but failed cleanup with `ERR_STREAM_WRITE_AFTER_END` when a keep-alive arrived after the client called `socket.end()`. The raw client shutdown guard was fixed, and the same frozen jar was rerun. The failed attempt remains in the evidence.

Parent unit result: **552 tests, 0 failures, 0 skipped**. Regression harness unit delta: **0**. No production edits, root builds, commits, pushes, or production processes were performed by the regression agent.

Limits: both kit lanes use the existing provider-adapter fixture; standard lanes use SQLite, with the two named extra live backend suites.

[Exact gate paths, hashes, source snapshots, and earlier attempts](evidence/fixed-ea3-summary.json). [Historical recovery report](RECOVERED.md).
