# S13 teleport contract

`modules.teleport` enables the commands and one server-thread-owned `TeleportService`.
Allium teleport features call this pipeline; future homes and warps must use it too.
This slice handles local server worlds, not proxy travel or Folia regions.

## Commands and permissions

| Command | Behavior | Permission |
| --- | --- | --- |
| `/tpa <player>` | Ask to travel to that player | `allium.tpa` |
| `/tpahere <player>` | Ask that player to travel to you | `allium.tpahere` |
| `/tpaccept [player]` | Accept the named request, or latest incoming request | `allium.tpaccept` |
| `/tpdeny [player]` | Deny the named request, or latest incoming request | `allium.tpdeny` |
| `/tpacancel` | Cancel your outgoing request or active warmup | `allium.tpacancel` |
| `/tptoggle [on\|off]` | Enable/disable receiving requests | `allium.tptoggle` |
| `/tptoggle on\|off <player>` | Change another online player's preference | Also `allium.tptoggle.others` |
| `/back` | Return to your previous successful Allium teleport origin | `allium.back` |
| `/spawn`, `/setspawn` | Travel to or persist the server spawn | `allium.spawn`, `allium.setspawn` |
| `/tp <player>` | Staff travel to a player | `allium.tp` |
| `/tp <mover> <destination>` | Staff move an online player to another | Also `allium.tp.others` |
| `/tphere <player>` | Staff bring a player to you | `allium.tphere` |

Console uses `/tp <mover> <destination>` or an explicit toggle target. Base travel
commands default to players; staff permissions default to operators. Completion uses
the shared command renderer and current visibility. Coordinate and selector syntax
remain outside this slice.

`allium.teleport.bypass.warmup` and `.cooldown` are separate explicit grants.
Neither operator status nor a force command implicitly bypasses recipient privacy:
`.bypass.toggle` and `.bypass.ignore` are explicit, default-false permissions.
Staff authorization/refusal and completed force moves are logged with participants,
command, result and reason. An issuing player disconnecting cancels their operation.

## Requests, safety and warmup

`teleport.request-expiry-seconds` bounds pending requests. Each requester has one
outgoing request; latest acceptance means latest insertion, not longest expiry.
Turning reception off cancels incoming requests and accepted incoming warmups.
Acceptance and final commit recheck current connections, visibility, permissions,
recipient preference and ignores. Unloaded preferences or an unavailable ignore
service refuse with a temporary-unavailability response. Ignore and toggle refusal
use the same response; vanished players look offline.

`teleport.warmup-seconds` applies to the player being moved. Movement beyond
`teleport.movement-tolerance`, actual damage, death, another teleport or a participant
disconnect cancels it. Rotation alone does not. Per-command seconds in
`teleport.cooldowns` begin only after a successful Bukkit teleport. Cancelled events
do not spend a cooldown or change history. Operations retain their validated config
snapshot; reload affects new operations.

`teleport.safety` validates the full player footprint and body, world border/build
height, hazards, sturdy floor and landing evidence. Its lava/void/suffocation/fall
policies are independently configurable. Avoiding falls requires immediate stable
support. With falls allowed but void avoidance enabled, a landing must be proven
within a bounded 16-block downward scan. An uncertain landing is refused.

Adjustment searches nearest candidates within `search-radius` and `vertical-search`;
both have validated upper bounds. Unloaded neighboring chunks are never loaded by
the search. `load-destination-chunk` defaults false; enabling it permits synchronous
loading of the requested chunk, which can cost a server tick. Disabling safety still
enforces finite coordinates, build height, border and chunk availability.

## Persistence and failures

UUID locations and command deadlines live in `allium_teleport_locations` and
`allium_teleport_cooldowns`. Server spawn is stored under the zero UUID. Back plus
the current cooldown snapshot commit in one transaction on SQLite/MySQL. Setspawn
publishes its new location only after SQL acknowledgement. Restart clears requests
and warmups; back, spawn, cooldowns and the S03 toggle persist.

`teleport.back.include-death` defaults false. When enabled, death becomes the newest
back location, queued after any outstanding teleport history; existing command
deadlines are preserved. Ordinary external plugin teleports cancel warmups but do
not replace Allium history.

Database work uses one bounded worker. A successful physical teleport is never
repeated to retry a write. Writes retry four times with bounded backoff; exhaustion
or executor rejection retains dirty history and blocks further travel/stale reloads
for that UUID. Rejoining retries the retained write. Shutdown retries dirty records,
drains for three seconds and logs every unresolved owner, location and deadline.
A replacement module refuses startup while its predecessor has unfinished work.

The world teleport and SQL commit cannot be one atomic transaction: an abrupt
process crash or an unresolved shutdown write can lose the latest back/cooldown.
No physical teleport rollback or crash-atomic history guarantee is claimed. These
tables describe this server's worlds; a shared database alone is not cross-server
enforcement or transport.

## Evidence

**Unit only:** 47 new core repository/request and Bukkit safety/settings/service
tests pass; the complete build passes 488 tests with no failures or skips plus
`shadowJar`. Repository tests used real SQLite; optional MySQL execution is not
claimed by this run. The real-client gate is maintained in
[`tests/live/teleport`](../tests/live/teleport/README.md). Only its recorded final
results establish live verification; compiling the fixture alone does not.

**Live verified:** the [results](../tests/live/teleport/RESULTS.md) cover the 15
named cases on Spigot 1.20.1 and Paper 26.3 with SQLite, three real clients and
console. Paper's original command-format assertion and unexercised second death
remain recorded: a retained-packet audit closes the former and a focused live
death-back supplement closes the latter. The same frozen production jar was used
throughout. No MySQL live, Folia, selector or proxy-travel support follows from
this gate.
