# S15 homes and warps

Homes and warps have no economy dependency. Every move goes through
[`TeleportService`](teleport.md): destination safety/adjustment, warmup cancellation,
command cooldowns, current connections and travel history. `home` and `warp` have
separate entries in `teleport.cooldowns`. Deleted/unloaded worlds are refused by
UUID; a different world with the same name is never substituted or created.

## Commands and permissions

| Command | Permission | Default |
| --- | --- | --- |
| `/sethome [name] [confirm]` | `allium.sethome` | true |
| `/home [name]` | `allium.home` | true |
| `/delhome [name]` | `allium.delhome` | true |
| `/homes` | `allium.homes` | true |
| `/setwarp <name> [confirm]` | `allium.setwarp` | op |
| `/warp <name>` | `allium.warp` **and** `allium.warps.<name>` | base true; named access false |
| `/delwarp <name>` | `allium.delwarp` | op |
| `/warps` | `allium.warps`, plus access to each listed warp | base true; named access false |

Player-only commands never enumerate another player's homes. Omitted home names
mean `home`. Names contain 1–32 ASCII letters, digits, underscores or hyphens and
are normalized to lowercase. Spaces, slashes, selectors and arbitrary text fail
validation. Unknown and inaccessible warps have exactly the same not-found reply.
Tab completion uses only loaded memory, filters access and prefix before its
50-result cap, and never suggests online or vanished player names in these slots.
Home lists cover the persisted hard cap; exceptionally large warp lists explicitly
label their first-1000 display limit.

Each warp gets a registered **false-default** permission node. Operators do not
receive access merely because they can create/delete warps. Grant a named node or
an explicit permission-plugin wildcard. A conflicting pre-existing node with a
non-false default blocks that warp and produces a diagnostic; Allium does not
rewrite another plugin's permission. Permission and destination revision/location
are checked again when the warmup commits. Deleting/changing the destination or
revoking access cancels that operation.

## Limits and confirmation

The home limit is the maximum of `homes.default-limit`, the highest effective
granted `allium.sethome.multiple.<n>` or legacy `allium.sethome.<n>`, and the
UUID's `homes.staff-overrides` value. Grants do not add together; a staff override
does not downgrade a higher rank. `allium.sethome.unlimited` explicitly permits
the hard cap of 1000; it defaults false. Values above the hard cap are bounded.
Rank downgrades retain existing homes and allow replacing one without increasing
the count, but cannot create more until the count is below the new limit.

An existing name requires repeating `/sethome <name> confirm` or
`/setwarp <name> confirm` within the configured confirmation window (default
30 seconds). The token binds the name, original stored revision, captured new
location and current connection. It confirms the location from the **first**
command, not a later position. Quit, config reload and expiry clear tokens.
Another update or delete/recreate invalidates the stored revision; it cannot
silently overwrite a different destination.

`modules.travel` needs Allium's teleport module. `modules.homes` and
`modules.warps` independently block their command families and completions;
module toggles apply on restart. Home limits, UUID overrides and confirmation
windows reload without replacing the service or reloading every player's data.
Invalid settings retain the previous validated configuration.

## Storage and follow-ups

The append-only `travel` schema stores UUID-owned homes, server warps and durable
revision counters. A home-owner row serializes count/insert; a warp-name row
serializes changes. Counts, revisions and mutations share a transaction with
conditional updates on both SQLite and MySQL. Counters survive deletion to avoid
stale confirmations becoming valid after recreation. Work uses one bounded async
repository lane; current-session callbacks publish caches only after acknowledgement.
Failed/ambiguous storage results trigger authoritative refresh rather than an
assumption that a write failed or a blind repeat. Rejected queues change no cached
destination. Restart loads homes/warps, not confirmation tokens or warmups.

This slice owns one server's destinations. Independent servers sharing these
tables are not a cache-coherence or cross-server travel feature.

**Explicit follow-ups:** pet teleport, offline home/restore administration,
legacy destination import and selector/coordinate syntax. None is claimed by S15.

**Unit only:** 27 S15 tests pass within the 518-test full build, covering real
SQLite limits/concurrency/rollback/CAS, name policy and deterministic service
callback orderings. Optional MySQL repository tests were not executed for S15.

The [live gate](../tests/live/travel/RESULTS.md) records the exact tested lanes and
assertions; source and unit coverage alone do not establish live verification.

**Live verified:** 12 named cases and 406 checks passed on each of Spigot 1.20.1
/ Java 17 and Paper 26.3 / Java 25 with SQLite. Both lanes used the same frozen
jar and helper/source inputs, including restart and current-permission checks.
All 18 server/client processes exited normally. No MySQL live, Folia or proxy
transport support is claimed. Paper's raw clients emitted movement warnings;
actual world/coordinates and fresh matching teleport events were verified.
