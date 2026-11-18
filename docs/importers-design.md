# Importers: inventory, mappings and cutover contract

Design only, before further feature implementation. No importer, migration, live
data read or cutover is performed here. Core maps are based on the current
repositories; Curios/Harvest destinations below are explicitly proposed addon
schemas, not tables already shipped in core. Never invent support for an unknown
source table or silently substitute empty data when a reader fails.

## Source evidence and versions

Read-only source audit:

- Legacy Allium `3f86b2b3101814043d45732470e3ea6c0afc7cd5`:
  `managers/DB/Database.java`, `reliques/RelicStorage.java`,
  `reliques/LegacyItemReader.java`, `harvest/storage/HarvestDatabase.java`,
  `harvest/HarvestBranding.java`, `managers/NicknameManager.java`, and its
  `docs/CUSTOM_ITEM_STORAGE.md`, `docs/RELIQUES.md`, `docs/HARVEST.md`.
- Essentials `50f833e67d2c9b78f94099b3e594842b821b4efb`:
  `Essentials/src/main/java/com/earth2me/essentials/UserData.java`,
  `config/holders/UserConfigHolder.java`, `config/entities/LazyLocation.java`,
  `config/serializers/MailMessageSerializer.java`, and `EssentialsUserConfiguration`.
- Relique `701c2519678a5df1f19fe196a0b97b75ea3de7ec`, plus the legacy Allium
  compatibility reader. Source schemas must still be probed against the operator's
  actual snapshot/version; these revisions do not establish an installed schema.
- Current contracts: [mail](mail.md), [kits](kits.md), [travel](homes-warps.md),
  [economy](economy-design.md), [reservations](economy-reservations.md),
  [roadmap migration rules](roadmap.md#migration-and-acceptance),
  [Harvest extraction](harvest-addon-plan.md), and current repository DDL.

Source roots are `/mnt/storage/repos/Allium`, `/mnt/storage/devops/Essentials`
and `/mnt/storage/devops/Relique`. Only source code was inspected; no production
userdata, credentials, HMAC secrets or database contents were exposed.

## Essentials userdata and companion files

Root: `plugins/Essentials/userdata/<uuid>.yml`. Old name-named files need a
trusted usermap/UUID reconciliation step; never query Mojang or guess UUIDs from
nicknames. Preserve source UUIDs and the distinction between missing and zero.
LazyLocation stores world/world-name plus x/y/z/yaw/pitch; older versions may
identify worlds by name rather than UUID. Resolve using an explicit world map;
unresolved worlds are blocked records, not teleports to another spawn.

| Domain / source fields | Allium destination | Loss, skip and validation policy |
| --- | --- | --- |
| `money` decimal | `allium_economy_accounts`, `allium_economy_operations`, `allium_economy_entries` | Exact decimal-to-minor conversion, bounds and ledgered import operation. No double conversion, rounding, starting-grant addition or missing-as-zero. Reject existing holds/conflicting account ownership. |
| `homes.<name>` LazyLocation; `sethomes` override | `allium_travel_homes`, owner revision rows; explicit home-limit override configuration | Preserve coordinates/rotation and original spelling in audit; normalized-name collisions and missing worlds block. A configured home limit is not inferred from count. |
| `mail` legacy strings or structured MailMessage | `allium_mail` TEXT/READY with `(source,source_ref)` | Preserve read flag, message, available sender UUID/name, sent/expiry times. Legacy strings may lack identity/times; keep unknown/null provenance, never extract UUID from display text. Expired text is separately counted/skipped. No text-to-gift inference. |
| `nickname` | S23 `allium_nicknames(uuid,raw_text,format_version=1,version,updated_at)` | Preserve exact original text in audit; revalidate safe formatting/length on activation. No interpretation as permissions, commands or real account identity. Unsupported interactions/animation remain inert and reported. |
| `ignore` UUID list | `allium_ignores` owner/ignored pairs | Deduplicate identical pairs; skip self-ignore/invalid UUID with reason, no nickname lookup. |
| `muted`, mute reason, `timestamps.mute` | `allium_mutes`, `allium_moderation_audit` | Preserve expiry semantics (0/permanent versus expired), original reason and source actor/provenance. Expired rows become audit-only. Missing reason/actor is unknown, not attributed to a staff member. |
| `jailed`, `jail`, `timestamps.jail`; companion `jails.yml` | `allium_jails`, `allium_jailed`, moderation audit | Import jail definitions first. Require valid return location; online-only jail timers cannot become wall-clock expiry without an explicit lossy policy. Block unresolved jail references; never move players during import. |
| Bans | `allium_bans`, moderation audit | Userdata alone is not authoritative ban storage. Reconcile server `banned-players.json`/`banned-ips.json` and installed Essentials/native ban implementation; preserve UUID/IP, expiry, source/reason. Never infer a ban from mute/jail flags. Ambiguous overlapping bans require a decision. |
| `teleportenabled` and other acceptance flags | Nullable overrides in `allium_preferences` | Map positive/inverted fields explicitly; missing uses configured default, not a written override. Unsupported flags remain report-only. |

Source mute/jail/ban restrictions must be reconciled before opening chat/admission.
Do not import private IP/history into public reports. Importing mail does not replay
notifications, charge send costs or deliver gifts into inventories.

## Legacy Allium H2 and configuration

Primary location: `plugins/Allium/database/sfcore.mv.db` (JDBC base `database/sfcore`,
H2 MySQL mode); version table `database_version`. Inspect metadata and migrations,
not a guessed file extension or an assumed latest column set. Use the matching H2
reader on a consistent stopped copy/export; never let a newer H2 auto-upgrade the
only source. Optional columns and externally created tables need explicit detection.

| Source table / layout | Destination | Loss and skip rules |
| --- | --- | --- |
| `player_balances(uuid, balance DECIMAL(20,2), last_updated)` | Economy accounts + journaled import operation/entries | Preserve exact minor units. No silent cap/clamp or addition to an already opened starting balance; conflicts require per-account decision. |
| `player_locations(id, player_uuid, location_type, home_name, world, x/y/z/yaw/pitch, timestamp)` | HOME → `allium_travel_homes` + owner revisions | Only HOME rows enter homes. Duplicate historical snapshots require deterministic latest-timestamp selection with all discarded candidates reported. LOGOUT → `allium_teleport_locations` slot `logout` (offline teleport); select the latest proven source timestamp and resolve the exact world UUID before admission. DEATH/BACK/other types map only to explicitly supported travel history or remain skipped, never relabelled homes. |
| `warps(name, world, coordinates/rotation, creator_uuid/name, optional is_public/permission columns)` | `allium_travel_warps`, `allium_travel_warp_names` + explicit access configuration | Preserve destination/revisions; normalize name collisions explicitly. Missing access fields mean restricted, not public. Creator metadata has no direct destination field: retain provenance. |
| Kits | Definitions → `plugins/Allium/kits/<name>.yml`; usage → `allium_kit_usages` only with proven source | No kit table is created by the audited Database.java. Do not claim H2 kit support or manufacture usage. Inventory actual kit/config/plugin source and version before enabling this reader; unsupported definitions/usage are blocked/skipped. No kit items are issued during import. |
| `player_teleport_toggle(player_uuid,state)` | `allium_preferences.accept_teleport_requests` | Read the installed writer/consumer semantics; convert explicitly and test true/false. Never guess whether state means enabled or disabled. |
| `player_data` including `player_displayname`, `staff_chat`, `pvp_enabled`; `player_slowfall_toggle.disabled` | Nickname → S23 `allium_nicknames`; known chat choice → S23 preferences; supported preferences only | Preserve other flags in report. No equivalent preference exists for every old flight/PVP/slowfall field; unsupported ones are skipped, not stored under unrelated accept flags. |
| `mail_messages(id,recipient,sender,sender_name,message,sent_time,is_read)` | `allium_mail` TEXT/READY | Preserve body/time/UUIDs; invert is_read → unread. Resolve console/unknown sender explicitly; no fabricated player UUID. |
| `mail_gifts(id,sender,sender_name,recipient,items,sent_time,is_claimed)` | Unclaimed → `allium_mail` GIFT/READY | Decode original item format on a pinned converter, then encode the new envelope. Claimed rows are skipped/audited. Ambiguous old in-flight claims block cutover until reconciled. |
| `player_inventories` BLOBs; `inventory_snapshots` LONGTEXT payloads; `offline_inventory_state/overrides` | Future restore/inventory importer, **not** active inventory writes | Retain all bytes, gamemode/slot/XP/position metadata. No restore feature destination is silently invented; unsupported snapshots stay in backup/report. |
| `player-channels.yml` → `channels.<UUID>` scalar write channel (legacy `AlliumChannelManager`) | `allium_chat_preferences` v1 | Validate each source ID against configured destination channels; map legacy aliases explicitly. Unknown/private channels stay quarantined until operator mapping, never silently become global. The source does not persist read subscriptions; add only the mapped write channel and operator-approved initial reads to sorted CSV, and report that reconstruction. Preserve original choices in report. |
| `player_data.uuid/name`, trusted name rows | `allium_player_names`, `allium_player_name_history`, supported `allium_player_info` timestamps | Never use nickname as account name. Name collisions/stale aliases are reported; do not overwrite newer trusted names. |

Message-sound, ignore or kit tables absent from the audited DDL require a separately
identified source (possibly YAML or another plugin). Absence is not consent/default
data. If Essentials and legacy Allium both own a domain, choose one authoritative
source per field/player; balances/items are never summed or merged automatically.

## Reliques equipment and Harvest

Reliques native Allium files: `plugins/Allium/reliques/data/<uuid>.yml`,
`version: 1`, `inventory.<slot>` lists of Base64 `ItemStack.serializeAsBytes()`;
empty string represents empty slot. Preserve list indices and duplicates. Original
Relique files default to the configured `../../config/relique/entities/<uuid>.yml`
relative to the plugin folder; resolve the configured path, not a hardcoded runtime
root. Their `inventory.<slot>` entries use the legacy item/component-map reader.
The existing migration-backup folder is evidence, not an additional item source.

Destination is the **Curios addon**, proposed versioned equipment/receipt storage
(`allium_curios_equipment` and `allium_curios_import_records` if SQL is chosen).
Those tables do not yet exist and core must not create them. Keep slot ID, ordinal,
UUID, full item bytes and source hash; unknown slot/item types quarantine the whole
player's equipment rather than dropping one entry. Preserve `allium:relic_id`,
legacy component backups and `alliumcards` identity. Applying effects is post-commit
runtime admission, never an import side effect. Equipment and player inventories
must not both receive the same item. Acceptance includes unequip/place while the
menu stays open, with cursor/hotbar/shift/drag/disconnect coverage from the roadmap.

Harvest owns separate `plugins/Allium/harvest/harvest.mv.db` (H2) or `harvest.db`
(SQLite), or configured MySQL. Physical table names are configured prefix + base
name, with `harvest_schema_version`; audited migrations include:

- `harvest_crops`: instance/crop/path/world IDs, position, stage/state, planted and
  stage-start timestamps, next-growth/remaining duration, planter and visual UUIDs,
  fertilizer, data_json and later speed_multiplier.
- `harvest_crop_cells`: world/block coordinates and instance association.
- `harvest_tracked_spawners`: world/position, entity type, visual UUID, updated time.
- `harvest_soil`: established/exhausted times, fertilizer and position.
- `harvest_sprinklers`: world/position, sprinkler ID, visual UUID, placed time/owner.

Destination is the standalone Harvest addon's namespaced equivalents, proposed
`allium_harvest_*` tables with explicit migrations, not core travel/item tables.
Preserve UUIDs, stage, remaining growth, fertilizer, ownership and timestamps.
Missing crop/path definitions, worlds or provider models block a crop; no stage
reset, offline-growth windfall or automatic respawn/duplicate visual entity.
Validate SQL against chunk/entity PDC and preserve `alliumharvest` keys and visual
UUIDs; live chunk scans/rewrites are a separate bounded, checkpointed apply step.
Kitchen station/in-progress cooking/bag data is a separately gated addon mapping;
it is not silently folded into Harvest. Unknown JSON/PDC fields are retained in
raw provenance, marked unsupported and never discarded by normalization.

## Serialized ItemStacks and PDC boundary

Legacy Database.serializeItemStacks writes an array length followed by serialized
ItemStack maps through BukkitObjectOutputStream, often Base64 in mail_gifts; other
BLOB/restore encodings must be identified individually, not assumed interchangeable.
Native Reliques bytes, component maps, inventory YAML, modern components and new
MailItemCodec envelopes have different format/data-version headers. Never copy
one blob into another column merely because both are Base64. Decode on a matching
pinned server/JDK/data-fixer adapter, compare exact counts/metadata, then encode the
new version. Newer-to-older data downgrades are blocked. Bound bytes/depth/counts
and apply a deserialization allowlist in an isolated converter, with no arbitrary
class loading or network access. Preserve undecodable bytes and reason codes.

Retain provider IDs/model variants, stack counts, enchantments, lore, custom data,
container contents and unknown tags byte-for-byte where supported. Preserve
`allium:custom_item_id`, `alliumcards:*`, `allium:relic_id`, `alliumharvest:*`, and
new Allium schema/provider/item/behavior/instance/state/signature fields. Legacy
PDC is read-only evidence, never proof of a privileged signed item. No automatic
re-signing/granting powers from a display name or copied legacy tag. A separately
audited privileged-item migration must explicitly verify original provenance.

## Dry run, reports, idempotency and apply

Default command/design mode is dry run. Open only immutable input snapshots;
never mutate sources or start either live writer. Manifest records source-install
UUID, source revision/schema, snapshot hashes, world/provider/ID maps, converter
version, destination schema/checkpoint and selected authority/conflict policy.
The install UUID is stable across moved paths/backups; do not derive ownership
from paths or issue a new install UUID to evade a duplicate/conflict.

Emit machine-readable JSON/JSONL plus a human summary: one record per natural key
with source file/table/key, original hash, destination/key, planned conversion,
loss/skip/block reason, before/after hash, and applied/replayed/conflict status.
Summary reconciles counts and exact money/item/slot totals; include no passwords,
HMAC secrets, private messages or raw IPs in ordinary logs. Sensitive full reports
are access-restricted artifacts. Unknown fields, lossy formatting, expired records
and unsupported features remain explicitly counted. Block apply if an unapproved
loss, missing authority or source/hash/schema change appears after review.

Proposed durable `allium_import_runs`/`allium_import_records` ledger (not created
here): unique `(source_install_uuid, domain, source_record_key)`, frozen payload
hash, destination IDs, before/after versions and terminal status. Mark applied in
the same SQL transaction as the destination mutation. Replay with identical hash
is a no-op; changed data under the same key conflicts. Partial batches resume only
from committed record checkpoints, never by replaying a balance addition or item
payout. Balance operations get deterministic import operation IDs and journal
entries; mail also uses its existing unique `(source,source_ref)` constraint.

Natural keys: account UUID for money, owner+home name/row ID for homes, original
warp name/row ID for warps, owner+ignored UUID for ignores, original gift/mail ID,
equipment owner+slot+ordinal, crop instance UUID and world-position for soil or
sprinklers. Sources with no stable mail ID use a frozen snapshot identity plus
entry ordinal, with a stable install-level record crosswalk. A changed list is a
conflict requiring reconciliation, not permission to import all entries again.
Nickname keys are UUID; restriction keys include source restriction ID/type.

Apply requires reviewed manifest and backup IDs, closed player admission and old
writers stopped. No notifications, teleports, inventory grants or addon effects
while rows are imported. Revalidate money/R/journal and item/stock liabilities,
then perform a staging rehearsal, restart and runtime readiness checks before
cutover. Import schemas and source readers need their own fixtures/integration
and crash gates before any production apply exists.

## Backup and rollback

Before dry-run/apply retain original H2 files/export on a stopped consistent copy,
all YAML/definitions, world/player/entity/chunk data and plugin/provider versions.
Back up `.server-id`, the item HMAC keyring and `.item-hmac-secret` with original
0600 permissions without printing contents. For destination SQLite use online
backup or VACUUM INTO, not a raw live DB copy missing WAL. For MySQL use a consistent
transactional dump. Preserve `allium_schema` and per-addon schema histories.

Rollback is a coordinated pre-cutover restore while admission and both writers
remain stopped, then verify checksums, row counts, schema versions, money/item
liabilities and keys. It is not DELETE imported rows while players keep delivered
items or journalled purchases. After gameplay resumes, use audited reconciliation
or a full coordinated restore including worlds/playerdata, never an automatic
inverse mutation against changed versions. Rehearse failure halfway through a
batch and after commit/lost acknowledgement; the import ledger and source hashes
must make resume and rollback distinguishable.

## Formats frozen until verified importers exist

Do not rename/drop/reinterpret legacy H2 tables/columns, UUIDs, home/warp names,
mail IDs/read/claim flags, decimal units, timestamp units/time zones, world/crop/
path/slot/provider IDs, Relique slot order, native equipment version/Base64,
serialized map/component encodings, or Harvest state/data_json/PDC semantics.
Preserve original H2 versions and converter fixtures. Retain original native
playerdata and unknown PDC fields; do not bulk rewrite or re-sign inventory data.

For the recode, freeze existing schema migration checksums/history, integer minor
units, account/reservation/journal identities and HELD terminal semantics, mail
format/data-version envelope and source_ref, K/S/P receipt encodings, server IDs,
item HMAC canonicalization/key IDs/state tags, provider references, equipment slot
identity, and S21 PDC version/face identity. S23 freezes nickname raw text (nullable `VARCHAR(512)`, `format_version=1`, row version and epoch-ms update) and channel preferences (`write_channel`, sorted unique comma-separated `read_channels`, IDs `[a-z0-9_-]{1,32}`, maximum 64, row version and epoch-ms update). Missing rows use initial defaults; NULL nickname means reset. See [chat contract](chat.md). No importer may write a newer format without a versioned migration.
Addon schemas proposed above need explicit ADRs and fixture-backed migrations
before final names are committed; no existing storage may be rewritten in
anticipation. Any necessary change adds a versioned reader/migration retaining
old fixtures, not a destructive cleanup before importers can read them.

Flight restoration adds version-1 native playerdata PDC byte keys
`allium:slowfall_disabled_v1`, `allium:flight_allowed_v1`, and
`allium:flight_flying_v1`. Preserve their names and boolean meanings; a future
legacy importer must map the corresponding stored preferences explicitly.
