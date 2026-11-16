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
Every allowed pre-login reads the authoritative UUID/IP ban state from storage; cached bans never short-circuit a healthy read. Authoritative lookups must
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

At startup Allium reads native `banned-players.json` and warns with the count of UUIDs absent from its active ban snapshot. This is a divergence warning only: it never imports, deletes, or synchronizes native entries. The native server can therefore reject a player independently of Allium, and Allium `/pardon` does not clear a native ban. On a failed or timed-out authoritative read, the configured DENY/ALLOW policy applies; already-known active bans remain enforced during an outage.

Follow-up verification: 591 unit tests (+2); SQLite Paper live 293/293 including a ban and unban written through a second connection, native-file divergence count and zero imports. Permanent moderation controls include bare/native ban, kick and pardon ownership on Spigot and Paper. [Evidence](../tests/live/discipline/evidence/followup-authoritative.json).

---

# Inventory and Ender Chest Inspection (S24)

Read-only by default, hierarchy- and vanish-aware inspection for online and offline targets via `/invsee <player>` (`allium.invsee`, default `op`) and `/enderchest [player]` (`/echest`, `/ec`; `allium.enderchest`, default `op`; targeting others requires `allium.enderchest.others`, default `op`).

## Permission model and wildcards

- `allium.*` grants the base inspection commands (`allium.invsee`, `allium.enderchest`, `allium.enderchest.others`), all **read-only** by default.
- Privileged inspection nodes are explicitly **false by default** and **excluded from `allium.*`**:
  - `allium.invsee.edit` — mutate an inspected player's inventory (online or offline).
  - `allium.invsee.offline` — open offline `<uuid>.dat` snapshots with `/invsee`.
  - `allium.enderchest.edit` — mutate an inspected player's ender chest (online or offline).
  - `allium.enderchest.offline` — open offline `<uuid>.dat` snapshots with `/enderchest`.
- Console cannot open chest GUIs (`PLAYER_ONLY`). Self-`/invsee` is refused; self-`/enderchest` (with no argument or own name) opens the caller's native Bukkit ender chest without `.others`.

## Hierarchy, exemptions, and vanish parity

- Inspection uses the same discipline hierarchy and `allium.moderation.exempt` policy as S19. Exempt or equal/higher-rank staff targets are refused unless the viewer explicitly holds `allium.moderation.bypass` (default `false`).
- Because offline permission providers do not exist in core, offline inspection (`/invsee` or `/enderchest`) requires **both** `allium.<cmd>.offline` **and** `allium.moderation.bypass` (both execution and tab completion enforce this).
- Hidden vanished targets (`PlayerNameVisibility#canSeeTarget`) obey **vanish blindness parity**: without `allium.vanish.see`, callers receive the exact same response as an unknown/offline player (requiring `.offline`, then `.offline` + `bypass`, then refusing as unavailable without ever opening live state or `<uuid>.dat`).

## GUI layout, confinement, and live guards

- **`/invsee` (45 slots):**
  - `0..26`: target storage slots `9..35`
  - `27..35`: target hotbar slots `0..8`
  - `36..39`: target armor in top-down order — Helmet (`39`), Chestplate (`38`), Leggings (`37`), Boots (`36`)
  - `40`: target offhand (`40`)
  - `41..44`: locked gray stained glass panes (never editable, even with `.edit`; collect-to-cursor and drag touches cancelled)
- **`/enderchest` (27 slots):** direct `0..26` identity mapping to target ender chest slots.
- **Title preservation (`InspectionLayout.formatGuiTitle`):** Titles stay `<= 32` characters while preserving `[Offline]`/`[Off]` and `(Read-Only)`/`(RO)`/`(Edit)` suffixes for all usernames up to 16 characters.
- **Read-only confinement:** Top-inventory clicks, shift-clicks into top, double-click collect-to-cursor, and top drags are cancelled; read-only viewers can interact freely inside their own bottom inventory and share read-only views concurrently (live target changes sync every 150 ms).
- **Single-editor mutex and guards:** At most one mutable viewer per `(targetUuid, type)` at a time (concurrent read-only viewers are still permitted). Target mail/kit inventory locks (`MailService#busy` / `KitService#busy`) block mutable opens (read-only allowed). Editable top interactions activate a 1-tick target guard cancelling `PlayerDropItemEvent`, `PlayerSwapHandItemsEvent`, `EntityPickupItemEvent`, `PlayerItemConsumeEvent`, `PlayerItemHeldEvent`, and target top-slot clicks/drags until the next-tick flush completes, plus `InventoryCloseEvent`, `PlayerQuitEvent` (`LOWEST`), and `PlayerDeathEvent` (deduplicating same-tick pre-flush drops so nothing duplicates). Mid-session loss of `.edit` permission aborts cleanly on the next interaction.

## Offline player data (`.dat`), CAS, and login fence

- `OfflinePlayerDataStore` + `CraftBukkitPlayerDataCodec` support both Spigot 1.20.x (`LegacyBackend` via `NBTCompressedStreamTools` + `PlayerInventory` / `InventoryEnderChest`) and Paper 26.3+ (`CodecBackend` via `NbtIo` + `TagValueInput` / `TagValueOutput` + `EntityEquipment`), wrapping NMS inventories in plain `CraftInventory` so `player == null` never touches `getHolder()`.
- **Startup codec self-test:** During module enable, `InspectionService` runs `CraftBukkitPlayerDataCodec#selfTest()` (a synthetic compressed NBT round trip covering inventory, ender chest, `DataVersion`, and `BukkitValues`). On any reflection or serialization mismatch, offline editing is automatically disabled (`INSPECT_OFFLINE_EDIT_DISABLED`), logged loudly at `SEVERE` (`Offline inspection editing disabled because the reflective playerdata codec self-test failed`), while online inspection stays operational.
- Root NBT fields (`BukkitValues` including `allium:mail_receipts`, `allium:kit_receipts`, `FUTURE` tokens, and unknown third-party plugin markers), attributes, health, location, and unrelated inventories stay intact verbatim when updating `Inventory`/`equipment` or `EnderItems`.
- Loads reject corrupt files (`INSPECT_OFFLINE_CORRUPT`) and newer-version files where `DataVersion > currentDataVersion` (`INSPECT_OFFLINE_NEWER_VERSION`) before opening.
- **Exact `DataVersion` & pending workflow hold guards for `.edit`:** Offline `.edit` sessions refuse writes if opening or saving would rewrite an older/mismatched `DataVersion` (`loaded.dataVersion() != currentDataVersion` or codec output `proof.dataVersion() != expectedDataVersion` -> `INSPECT_OFFLINE_VERSION_REWRITE`), or if `ModerationRepository#hasPendingWorkflowHold` finds an unreleased hold (`allium_economy_reservations` `HELD`, `allium_mail` `PENDING`/`CLAIMING`, or `allium_kit_deliveries`) — failing closed on storage read error (`INSPECT_OFFLINE_HOLD`). Read-only offline inspection remains permitted in both cases without touching `<uuid>.dat`.
- Saves use SHA-256 Compare-And-Swap (`CasConflictException`) and temp-file + `StandardCopyOption.REPLACE_EXISTING` atomic rename; concurrent writes aborts the session cleanly without clobbering disk changes.
- `AsyncPlayerPreLoginEvent` fences logins while an offline session aborts and awaits any in-flight async save up to `moderation.preload-wait-millis` before Minecraft reads `<uuid>.dat`.

## Audit log & verification

- `INVSEE_OPEN`, `INVSEE_EDIT`, `INVSEE_ABORT`, `ENDERCHEST_OPEN`, and `ENDERCHEST_EDIT` write sanitized (`<= 512` chars) slot-delta records to `allium_moderation_audit` via `ModerationRepository`.
- Verified with **196/196** live checks on Spigot 1.20.1 (`98/98`, `spigot-r14`) and Paper 26.3 (`98/98`, `paper-r2`) with SQLite and **799 unit tests** (`allium-api`: 4, `allium-core`: 278, root: 517; **+18** from S23 baseline 781). See [S24 live results](../tests/live/inspection/RESULTS.md).

