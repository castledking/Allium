# Persisted discipline (S19)

The moderation module adds bans, kicks, freezes, jails, warnings and staff notes.
It reuses the S17 repository, duration parser, staff policy and audit log. No
additional plugin is required. Freezes, bans, jail locations/assignments, warnings
and notes survive restart. Kicks persist an audit entry rather than an ongoing
restriction.

## Commands

Every command below requires `allium.<command>` (operator by default). The
`allium.*` aggregate includes those command permissions. It grants neither the
exempt permission nor the hierarchy bypass.

| Command | Behavior |
| --- | --- |
| `/kick <player> [reason]` | Online target; record the action before requesting disconnect. |
| `/ban <player> [reason]` | Permanent UUID ban; disconnect a current online target after persistence. |
| `/tempban <player> <duration> [reason]` | Timed UUID ban, using the same `5m`, `1h30m`, `7d` syntax as mute. |
| `/unban <player>` (`/pardon`) | Remove this UUID's ban, including any IP restriction carried by that entry. |
| `/banlist` | Loaded active bans; expired entries are excluded. |
| `/ipban <player> [reason]` | Disabled by default; when enabled, ban UUID and its current literal IP. Requires an online target. |
| `/freeze <player> [on\|off] [reason]` | Persist a translation/teleport restriction; omit state to toggle. Looking remains allowed. |
| `/setjail <name>` | Save the caller's exact safe location; names use 1–32 ASCII letters, digits, `_` or `-`, normalized lowercase. |
| `/jail <player> <jail> [reason]` | Save the original return point and move to the jail anchor. Reassignment keeps the original return point. |
| `/unjail <player>` | Return to the saved safe origin, then remove the assignment. A refused return retains confinement. |
| `/warn <player> [reason]` | Add a persisted warning and notify an online target. |
| `/warn <player> history` | Read the latest bounded warning history. |
| `/notes <player> [list\|add <note>]` | Staff-only note history or addition; notes are never sent to the target. |

Target-name completions use the bounded shared name index and current visibility;
jail completions use loaded names only. Completion performs no database I/O.
Reasons and notes are inserted as plain untrusted text, with control characters
removed and bounded length, so formatting, click and hover markup cannot execute.

## Staff policy and offline targets

A player cannot act on themselves. Without an explicitly granted
`allium.moderation.bypass` (default **false**), a target holding
`moderation.discipline.exempt-permission` (default `allium.moderation.exempt`,
default **false**) is protected. Equal or higher staff ranks are protected too.
The default ascending ranks are mute/warn/notes, freeze/kick, jail/tempban, and
ban/ipban, based on effective command permissions. Operators have command access,
but operator status alone does not grant the hierarchy bypass.

`moderation.discipline.rate-limit-millis` defaults to 1000 per player actor and is
shared with mute/unmute. Console is trusted and is not rate limited. Pending writes
are fenced per target. Permissions and player-connection identity are rechecked
before queued actions start. Success is published after persistence, not merely
because a callback was enqueued.

Known offline targets resolve through authoritative asynchronous name storage.
There is no authoritative offline permission provider in core: offline mutations
therefore require console or an explicit hierarchy bypass. This avoids treating an
offline staff member as unprotected. Initial jailing and unjailing require the
current online player to preserve the exact return location. Existing assignments
can be reassigned offline. Kicks and IP bans also require an online target.
Vanished targets use the offline policy and do not disclose their online state.

## Login and storage failures

`moderation.discipline.ban-load-failure-policy: DENY` is the default. Login loading
runs off the server thread at pre-login MONITOR, only while login is still allowed.
Known active bans are checked from loaded state; new authoritative lookups must
finish within `moderation.preload-wait-millis`. If they fail or time out under DENY,
the login is refused with a retry message. The outage cost is that an unbanned
player can also be unable to join during a database failure.

`ALLOW` is an explicit availability trade-off and logs a warning. It admits unknown
ban state during failures; already-loaded active bans remain effective. Freeze and
jail data still fail closed during loading, preventing movement or teleport escape
until a session is ready. Bounded retries use the moderation retry settings.

SQL uses a bounded executor. State changes and their audit entries commit in one
transaction, with no Bukkit access from JDBC workers. SQLite and MySQL/MariaDB use
the same schema component `moderation`, migration 2. Histories are bounded per read
(up to 100 repository rows; commands display at most 50). Active-ban expiry is
checked against the current clock without requiring a deletion task to run.

## Movement and command ownership

Jails are anchored confinement, not configurable prison regions. Translation,
external teleports and vehicle entry are blocked while jailed or frozen; looking
is allowed. Jail respawns use the jail point. Only the service's scoped exact
jail/return moves bypass its restriction. Placement and release require a loaded,
safe destination, without adjustment or forced chunk loading. Missing worlds or
unsafe destinations refuse the action.

Bare `/ban`, `/kick` and `/pardon` belong to Allium when enabled; `allium:` labels
select them explicitly. The native `minecraft:ban`, `minecraft:kick` and
`minecraft:pardon` commands remain native, as `minecraft:give` does, and use
Minecraft's independent permissions and ban storage. Allium does not import,
synchronize, or clear native ban files implicitly. `disabled-commands` yields
Allium's entire command family on restart. Prefer explicit namespaces in console
scripts when ownership matters.

## Verification and limits

[Live gate results](../tests/live/discipline/RESULTS.md) record exact lanes,
backend, jar hash and checks. Repository tests cover SQLite and disposable
MariaDB, including audit failure rollback and jail assignment/delete races.
Runtime and live claims remain limited to that evidence. Cross-server invalidation
of already-loaded discipline state, Folia, and an offline permission resolver are
not provided by this slice. Sign, book and anvil-rename text remain outside mute's
chat/command enforcement.
