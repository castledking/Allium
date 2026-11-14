# S19 discipline live results

**PASS: 918/918 live checks** on identical frozen Allium SHA256 `d86686d3ee72ce578f0f8830bacb9dffdb5e3b22bd80c7f79b5120472623b016`.

- Paper 26.3 / Java 25 / SQLite: **280/280**.
- Spigot 1.20.1 / Java 17 / SQLite: **280/280**.
- Spigot 1.20.1 / Java 17 / shared MariaDB container, unique disposable S19 database: **358/358**. Container remains running; S17 database untouched.

Full isolated check/shadowJar: **589 unit tests, 0 failures, 0 skipped**. Startup diagnostic adds 2 (552 → 554); S19 adds 35 (554 → 589); harness delta 0. Commit deltas are recorded in the linked unit ledger.

The gates cover command ownership, effective permissions/defaults, hierarchy/exemptions, rate limiting, invalid input, hostile text, kick packets, timed/permanent/offline bans and login refusal/expiry, history persistence, freeze/jail movement and teleport escape prevention, looking while restrained, reassignment/original-location recovery, restart durability, fail-closed storage loading, and backend audit rollback. All owned subprocess exits and final logs are checked after shutdown.

Allium owns bare `/ban`, `/kick`, `/pardon` when enabled; native `minecraft:` commands retain independent native ownership, permissions and ban storage. The gate observes those CommandMap owners. See [contract](../../../docs/moderation.md).

Live Paper checks exposed a production correction bug: `setTo(from)` initiated a plugin teleport which the restraint listener blocked. Cancelling native translation instead corrects the client position and preserves subsequent looking/movement. A unit regression and the live controls verify the fix. Earlier failed snapshots remain in evidence. Harness corrections cover relative teleport acknowledgements, login disconnect packets and precisely identified normal INFO scheduler shutdown lines.

Limits: no cross-server cache invalidation, Folia, or authoritative offline permission provider. Offline staff actions require console or explicit hierarchy bypass. Native ban files are not imported or synchronized. Jails are safe anchored confinement, not regions. Sign/book/anvil text is outside mute chat/command enforcement.

[Exact checks, source hashes and failed earlier runs](evidence/s19-manifest.json). [Unit ledger](../regression/UNIT-DELTAS.md).
