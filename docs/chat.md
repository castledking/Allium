# S23 chat, nicknames and channels

Chat uses the existing moderation, command, player-name, storage, scheduler and
text services. The default SQLite profile stores nicknames and channel choices.
No import code runs at startup. Vault economy remains disabled by default.

## Commands and permissions

| Command | Permission / behavior |
| --- | --- |
| `/nick <name\|off>` (`/nickname`) | `allium.nick`; persistent self nickname/reset |
| `/nick <player> <name\|off>` | Also `allium.nick.others`; resolves known account UUIDs, including offline accounts |
| `/channel join <id> [read\|write]` | `allium.channel` and `allium.channel.join.<id>.read` or `.write` |
| `/channel leave <id>` | Removes subscription; leaving the write channel selects the configured default |
| `/g`, `/global`, `/l`, `/local`, `/a`, `/staffchat` | Select/toggle write channel; with text, send once without changing the selection |
| `#text` in public chat | Send once to configured staff channel; hex color tokens are not staff shortcuts |
| `/delmsg <id>` (`/deletemsg`) | `allium.delmsg`, `allium.deletemsg` or `allium.staff` |
| `/delmsg <player> <amount>` | Same, latest 1–200 retained messages for the resolved UUID |

Global read/write and local write are public defaults. Staff channel read/write
requires its specific nodes or `allium.staffchat`, plus read membership. The
initial default subscription is global; `allium.channel.autojoin.<id>.read` adds
initial subscriptions. Permission is checked again for every send and recipient.
Local recipients must share the world and be within the configured radius.
Local traffic never goes to Discord. Native event audience restrictions remain
in force, and mute/load readiness and vanish rules are checked on the server
thread immediately before delivery.

Nickname legacy colors, hex colors and gradients require `allium.nick.color`,
`.hex`, `.gradient`; public body formatting uses `allium.chat.color`, `.hex`,
`.gradient`. Nicknames are limited to 32 visible Unicode letters/numbers, spaces,
underscore, dot and hyphen by default. Controls and unsupported markup are
rejected. `allium.nick.impersonate` defaults false: another known real account's
name cannot be adopted without it. Real account identity and target lookup never
use display names. Losing a style permission strips that style from rendering.
Reset preserves a versioned row with SQL NULL raw text.

The gamemode family also accepts `/gmc` and `/creative`, `/gms` and `/survival`,
`/gma` and `/adventure`, `/gmsp` and `/spectator`. Each accepts an optional player
and uses the existing `allium.gamemode` / `allium.gamemode.others` checks.
Disabling `gamemode` disables its fixed aliases.

## Formatting and optional decorations

`chat-format.default`, `.groups.<primary-group>` and per-channel `format` accept
trusted style text and these slots: `{prefix}`, `{name}`, `{gradient_name}`,
`{suffix}`, `{player}`, `{message}`. Legacy `<prefix>`, `<display_name>`, `<suffix>`,
`<message>`, `%allium_nickname%`, `%allium_gradientdisplayname%` and LuckPerms
prefix/suffix slots map to the same fields. Player values are parsed separately;
they cannot introduce template slots, links, hover or command actions.
`chat-hover.groups.<group>.<name|prefix|suffix>` configures trusted hover lines,
click text and `action` (`suggest-command`, `run-command`, `open-url`,
`copy-to-clipboard`). Staff deletion controls use `chat-hover.delete-hover-mode`
(`name`, `prefix`, `suffix`, `none`) and suggest an exact-ID command.

Vault Chat/Permission supplies cached prefix/suffix/primary group when present.
PlaceholderAPI supplies fixed LuckPerms prefix/suffix fallbacks and GradientPlus's
`%gradient_displayname%` palette. No player message is evaluated by PAPI. Gradient
names use the first and last palette colors on the current nickname; absent,
unresolved or all-white palettes leave the template color intact. The registered
`%allium_nickname%` and `%allium_gradientdisplayname%` return cached values.
`allium.gradientname` enables phase animation; each chat message freezes its
rendering. Optional `nickname.animate-tab` defaults false. A shared bounded ticker
refreshes up to `nickname.updates-per-tick` sessions (default 16) per tick, querying
decorations at most once per second per selected session and applying only changed
names. No per-player repeating task or SQL lookup occurs on the chat hot path.

`modules.chat: false` at startup leaves native chat ownership to the server.
Disabling it by reload stops Allium admission and restores real display names.
`enable-chat-formatting: false` retains routing/history with a plain name/body
format. Disabling channels or removing an active private channel blocks implicit
speech until explicit selection; it never silently promotes private speech to
public. `/g` explicitly selects global when channels are disabled.

## DiscordSRV relay

DiscordSRV 1.30.3 is an optional pinned API. Each non-local channel needs an
explicit `discord-channel-id`; defaults are empty. `discord-in-format` supports
`{discord_username}`/`{message}`, `discord-out-format` supports
`{player}`/`{message}`. Allium suppresses DiscordSRV's duplicate public-chat relay
while it owns chat. Incoming configured traffic is intercepted, deduplicated by
snowflake, sanitized, then delivered only to channel readers. Bots/webhooks are
ignored. Other destinations remain with their owner. No outbound loop is made.

Bot sends retain the returned Message object. Optional `webhook-delivery: true`
uses DiscordSRV's webhook and linked Discord profile, sends with `wait=true`,
and retains the returned snowflake. Allowed mentions are empty. Deletion acts on
that exact returned identity; a deleted original also deletes a late send
acknowledgement. Discord deletes tombstone the associated local message. A bounded
worker performs webhook discovery and asynchronous HTTP dispatch; failures log
an opaque local ID without token, URL or body, and do not automatically resend.
Links and deduplication are bounded runtime state, not a durable Discord archive.

## Message deletion and optional packet peers

Messages have opaque run-UUID/sequence IDs and a shared tombstone. The bounded
runtime history retains at most 4096 entries globally, 200 per viewer, for 15
minutes. Stored JSON is the exact recipient-specific rendering. It is forgotten
on quit and cleared on shutdown; it is not moderation evidence storage.

Deletion pushes distinct whitespace packets out to each original recipient,
then replays only that recipient's surviving history. The default clear window
is 500 entries (`chat.deletion_resend.clear_lines`, 100–4096, or 0 to disable
clearing); the shared per-tick packet budget defaults 64, bounded 1–256. Live
arrivals are retained during replay; overlapping deletions trigger a follow-up
pass, and each send checks its tombstone. There is no global history fallback.
Audit logs record actor UUID and IDs, without message bodies.

Without PacketEvents, retained history covers Allium-rendered chat. The optional
PacketEvents 2.12.1 adapter additionally mirrors non-overlay system packets per
recipient and stores the component JSON supplied by the packet API rather than
reducing it to plain text. Peer serializer support bounds which fields survive
replay. It does not guess authors by text or
rewrite signed player-chat packets. Foreign packet IDs are per recipient. Empty
translation metadata survives component compaction and lets delayed callbacks
reject deleted/expired IDs. An optional InteractiveChat guard cancels deleted
marked packets and disables its original-packet fallback. Client mods, history
beyond the clear window, earlier sessions, signed foreign chat and external
archives cannot be erased by this replay mechanism.

## Frozen storage and operational limits

Schema component `chat`, version 1:

- `allium_nicknames`: UUID primary key, nullable `raw_text` (max 512 characters),
  `format_version=1`, monotonically incremented row `version`, epoch-ms
  `updated_at`. Raw text is inert supported formatting text, never a component or
  executable template. Unknown format versions fail closed.
- `allium_chat_preferences`: UUID primary key, `write_channel` (1–32 characters),
  `read_channels` sorted comma-separated unique IDs, row `version`, epoch-ms
  `updated_at`. IDs match `[a-z0-9_-]{1,32}`; at most 64 subscriptions. An empty
  CSV means none; missing row means configured initial defaults.

A bounded single storage worker commits mutations before cache/acknowledgement.
Joining profiles fail closed and retry with capped backoff. Current caches have
one server owner; multi-server nickname/channel invalidation is not implemented.
The SQL dialect includes MySQL statements; S23 live acceptance is SQLite only.
See [importer design](importers-design.md) before changing these formats.
