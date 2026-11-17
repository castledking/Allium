# Help, legacy messages and YAML formatting

The old port used `hide.unknown`, not the legacy root `unknown-command`; unchanged
files were never saved by resource sync, leaving valid flow lists unexpanded.
Two tests reproduced those symptoms before the fixes.

Unit delta **+9**, 887 → **896**, full check/shadowJar with zero failures/errors/skips.
Coverage includes existing locale insertion/custom text preservation, flow-list
normalization without missing keys, stable backups/invalid YAML preservation,
help intersection and resolver filtering, permission-vs-unknown routing, resolver
delegation, restricted suggestions, and eight-entry pagination/clamping.

Native gate records and frozen jar hash are in `help-results.json`. Each lane
uses real clients, SQLite and PacketEvents, then restarts without PacketEvents.
It checks generated root message keys, flow-list expansion on startup, help path
filtering, typo suggestions, customized no-permission feedback, resolver-off
native refusal, and the existing hide/alias/reload/restart checks.

Spigot 1.20.1 and Paper 26.3: **24/24 each**. All owned gate ports released.
Frozen jar SHA256 `98dd099da19c107e8377a2b34b72b3727742f512a8f8a2533340cbb29b756deb`.
First native attempts are retained as failures: the harness expected the owning
plugin's refusal to contain "permission", but both platforms correctly returned
the native `command.unknown.command` refusal with the resolver disabled. The
corrected harness verifies that response and absence of Allium's custom marker.
Both complete reruns passed on the unchanged jar.
