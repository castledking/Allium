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
| `/home bed`, `/bed` | `allium.home.bed` | true |
| `/home <player>:<home>`, `/home <player>:` (list) | `allium.home` **and** `allium.home.others` | op |
| `/home <player>:bed` | `allium.home` **and** `allium.home.others.bed` | op |
| `/delhome [name]` | `allium.delhome` | true |
| `/delhome <player>:<home>` | `allium.delhome` **and** `allium.delhome.others` | op |
| `/homes` | `allium.homes` | true |
| `/homes <player>` | `allium.homes` **and** `allium.homes.others` | op |
| `/setwarp <name> [confirm]` | `allium.setwarp` | op |
| `/warp <name>` | `allium.warp` **and** `allium.warps.<name>` | base true; named access false |
| `/delwarp <name>` | `allium.delwarp` | op |
| `/warps` | `allium.warps`, plus access to each listed warp | base true; named access false |

Only holders of `allium.homes.others` can list another player's homes. Omitted home names
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

## Beds and other players' homes

With `allium.home.bed`, `/home bed` and `/bed` go to the player's bed or charged respawn
anchor, as Essentials' `/home bed` does. `/homes` lists the bed last: plain (`home.bed-entry`)
when it is set, struck through (`home.bed-missing-entry`, `&7&mbed`) when it is not. A plain
`/home` with no homes set goes to the bed when there is one. `bed` cannot be set as a home
name (`home.bed-reserved`); a home that is already called `bed`, such as an imported one, is
still reached and deleted normally by players without the node. The bed is checked again when
the warmup ends, and the teleport shares the `home` cooldown and warmup rules. Spigot 1.20.1's
`getBedSpawnLocation()` and the newer `getRespawnLocation()` are both supported.

Staff manage other players' homes as legacy Allium did. The name goes through the shared name
index, so offline players work too, and homes are read from storage rather than a session:

- `/home <player>:<home>` (`allium.home.others`) teleports to the home; `/home <player>:` lists
  that player's homes, as `/homes <player>` (`allium.homes.others`) does.
- `/home <player>:bed` (`allium.home.others.bed`) goes to their bed or charged respawn anchor:
  checked as theirs would be when they are online, the respawn point saved in their player file
  when they are not.
- `/delhome <player>:<home>` (`allium.delhome.others`) deletes the home at the revision it just
  read, so a home the owner moved in between is reported as changed instead. An online owner's
  `/homes` and completion follow at once; an offline owner sees it on their next join.
- Tab completion offers `Name:` for each visible online player, then `Name:<home>` from that
  player's loaded homes (`Name:bed` too with the bed node). It reads memory only, so offline
  owners complete nothing; `bed` is only suggested for `/home`.

Each visit or deletion is logged as `Home audit actor=<uuid> owner=<uuid> home=<name>`, with
`action=delete` for deletions.

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
Legacy homes, warps and logout positions now import with
[`/allium import legacy`](legacy-import.md).

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
