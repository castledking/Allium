# Mail and gifts (S12)

`/mail` (or `/mail read`) lists your mailbox. `/mail send <player> <message>` sends text;
`/mail gift <player> [amount] [note]` sends the held stack, or the specified positive amount.
Use `/mail claim <id|all>` to collect gifts, `/mail delete <id>` to delete text, and
`/mail clear` to remove read text. Gifts remain until collected; clear, delete and expiry never
throw away their contents. A full inventory leaves the entire gift; a partial fit leaves an exact
remnant. Nothing overflows onto the ground.

`allium.mail` permits reading, claiming and deleting your own mail. Sending additionally requires
`allium.mail.send` or `allium.mail.gift`. Console can send text. `allium.mail.bypass.limits`
skips mailbox and rate limits. `/msgtoggle`, ignore and mute apply to mail too; explicit message
bypass permissions have the same meanings as private messages. A toggle refusal and an ignore
refusal use the same generic response. Unloaded or failed preference/ignore state refuses delivery
with “try again shortly.” Acceptance and ignores are read from authoritative storage as well as pending local refusals;
mail does not infer permissions or acceptance from the name cache. Preferences storage must be
available. Being offline or vanished does not prevent addressed mail or disclose online status.

`mail.*` in config.yml controls mailbox size, body length, the sender's sliding rate window,
text expiry and gift amount. Each operation captures its limits. `/allium reload` keeps the same
service, executor and pending transactions; storage type and server identity require a restart.
The default is free mail. The reserved M3 `CostPolicy` is a side-effect-free preflight veto, not a
money debit/refund implementation. A future cost implementation must join a durable economy
transaction; callbacks that debit a balance are outside this contract.

Name completion reads a bounded in-memory directory populated by joins and one startup query.
It does not enumerate offline players or perform SQL, disk or network access per keystroke.
Actual sends resolve online exact names or query the authoritative name index. Suggestions filter
current online visibility for the requesting sender, including cached names that become hidden.
Name ownership outside the server network is not checked against Mojang. Claim/delete IDs come
from the last loaded mailbox snapshot; `/mail read` refreshes them.

Unread-on-join and new-mail notices use the same `NotificationService` as private messages and
social spy. The recipient's `/mfx` setting scales the language entry's sound once. Failed or
blocked sends produce no recipient notification. Mail bodies are rendered literally. Social spy
observes delivered private messages; it does not copy mail bodies. New-mail notices are local to
the recipient's current server, not a network notification bus.

## Escrow, claims and recovery

Mail storage has an independent append-only schema history (`allium_schema`, component `mail`).
Version 1 creates `allium_mail`, the send-rate log and mailbox lock rows. Version 2 adds immutable
activation and claim-acknowledgement records. Do not delete these records independently: old
player-data receipts may still need them after reconnect or an interrupted acknowledgement.

A gift starts PENDING and is invisible to its recipient. On the server thread Allium rechecks the
same player connection and held slot, removes the exact stack amount, and writes a unique send
receipt into the player's PDC. It calls the native player-data save, then reads back the full
receipt from the native player-data directory's compressed `<uuid>.dat` on the bounded mail worker. A single
guarded platform accessor obtains this directory from CraftBukkit's player storage; it never
guesses from a dimension folder. The verified layouts include `world/playerdata` on 1.20.1 and
`world/players/data` on Paper 26.3. If the native accessor is unavailable, mail is disabled.
Only a matching receipt permits PENDING → READY. The inventory and receipt are in that same
player-data document. A missing, mismatched or unreadable write never activates the gift.

A claim conditionally acquires READY → CLAIMING with a random token and server owner. Only one
server can win. Delivery reads the won token's current row, rather than an earlier mailbox view.
`ItemDeliveryService` fires its cancellable `ItemGiveEvent`, plans the exact fit, and rechecks the
inventory before applying it. The delivered amounts and token are saved as a player-data receipt
and read back before the database removes the gift or stores its remnant. Replaying an already
acknowledged token cannot consume another remnant or recreate a deleted gift.

Player inventory interactions are locked during escrow/delivery and readback. Empty the cursor
before beginning. Player identity and connection generations fence callbacks after disconnect;
a stale CraftPlayer object cannot update a newer connection's inventory or receipts. Plugins
that directly rewrite inventories or playerdata must coordinate with mail rather than bypassing
these locks. This does not claim support for concurrent external inventory synchronizers.

Join recovery verifies any live receipts against playerdata, then queries the database before interpreting them. It never treats a failed query
as an empty result and never steals another server's CLAIMING row. A saved send receipt activates
its pending gift; no send receipt cancels the untouched PENDING row. A saved claim receipt finishes
its exact delivery; absence releases an undelivered claim. Durable acknowledgement records prove
completion when the original mail row is gone. Storage failures get bounded background retries;
continuing failure asks the player to reconnect. Unknown receipts are retained for operator
recovery. Queue rejection leaves journalled work recoverable, not permanently excluded. If a post-save verification cannot be queued, the inventory stays locked and the connection is closed; recovery cannot promote an unverified in-memory receipt.

Keep `plugins/Allium/.server-id` stable across restarts. Each server sharing MySQL needs a different
ID. Recovery without a receipt belongs to the originating/claim-owning server and its playerdata;
a claim stranded on a down server is not automatically stolen by a different one. Restore the
original server identity and playerdata to complete recovery. Do not clone an active server's
identity onto a second server. Never regenerate a malformed existing identity to work around it.

The readback proves an OS-acknowledged, readable save; it does not promise survival of disk failure
or power loss without filesystem durability. Native saving must be enabled. Back up playerdata,
mail storage, `.server-id` and the item-signing keyring together. On SQLite use online backup or
`VACUUM INTO`, never a raw copy of an open WAL database. Restoring only an old inventory or only
one side of escrow can invalidate the recovery contract. Plan coordinated rollback in M6.

## Stored items and compatibility

Gifts use envelope format 2, with the writing Minecraft data version and Bukkit's native
versioned ItemStack YAML. The envelope is checked before any item is deserialized. Newer data is
refused on older servers and remains claimable on a compatible server. Older data goes through
the server's data fixer; see the exact recorded live lanes in the results, not a blanket promise
for arbitrary third-party metadata or downgrades. Payloads and stack counts are bounded.

The codec does not rebuild appearances or re-sign items. A typed PDC sidecar restores the original primitive tag types and values
after native item conversion: in the live gate, the native 1.20.1 → 26.3 YAML conversion otherwise
interpreted a numeric-looking UUID string as a floating-point value. This repair restores the
original signed bytes; it never re-signs or legitimizes an edited item. Unsupported persistent
tag types are refused before escrow rather than silently discarded.

Provider IDs, state, instance UUID and MAC travel as stored. Servers exchanging signed items must share the documented verification
keyring and provider definitions. Provider readiness is not permission to discard an unreadable
gift. Tools are ordinary transferable items.

## Future M6 import mapping (no importer implemented)

| Source | Destination | Required handling |
| --- | --- | --- |
| Legacy Allium H2 `mail_messages` | TEXT, READY | Preserve `recipient`, `sender`, `sender_name`, `message`, `sent_time`; map `is_read` to inverse `unread`. |
| Legacy Allium H2 `mail_gifts` | GIFT, READY | Import only `is_claimed = false`; decode legacy `items` with its original serialization on a supported migration server, then encode the S12 envelope. Preserve UUIDs, item/provider PDC and sent time. Never copy the legacy blob straight into the new envelope. |
| Essentials `userdata/<uuid>.yml` `mail` | TEXT, READY | Handle both legacy strings and the installed version's structured `MailMessage` records. Preserve available sender UUID/name, text, timestamp and expiry; retain unknown fields in migration audit. Do not invent timestamps or UUIDs from display text. |

Use `source = legacy-allium` or `essentials` and a stable `source_ref` containing source-install ID,
recipient UUID and original row ID (or source entry index plus a snapshot identifier). The unique
`(source, source_ref)` index makes repeated imports idempotent. Missing sender UUIDs may remain
NULL. Build the name directory from trusted source UUID/name rows; do not scan all offline players
in command completion. Reconcile old pending gift liabilities before switching writers, and retain
an import ledger and coordinated rollback snapshot. Never run old and new gift writers together.

## Verification

The implementation and unit checks are separate from the live release gate. Current live cases,
artifact hashes and limitations belong in [the S12 results](../tests/live/mail/RESULTS.md) and its
manifest. An absent result is an open check, not a pass.
