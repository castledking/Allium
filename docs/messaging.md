# Private messaging (S07)

| Command | Aliases | Permission (default) |
| --- | --- | --- |
| `/msg <player> <message>` | `tell`, `w`, `whisper`, `m`, `t` | `allium.msg` (everyone) |
| `/reply <message>` | `r` | `allium.reply` (everyone) |
| `/ignore [player]` | | `allium.ignore` (everyone); without a name, lists who you ignore |
| `/unignore <player>` | | `allium.ignore` (everyone) |
| `/msgtoggle [on\|off] [player]` | | `allium.msgtoggle` (everyone); others: `allium.msgtoggle.others` (op) |

`/msg console <message>` messages the console, and the console can use `/msg`, `/reply` and
`/msgtoggle <player>`.

## Who you reply to

Your reply target is the last person you messaged or who messaged you, whichever happened most
recently. Only delivered messages count. When that person goes offline, the target is cleared,
and `/reply` says you have nobody to reply to. The console takes part like a player. `/reply`
checks every rule below again, at the moment it is sent.

## When a message is not delivered

Every rule is checked when the message is delivered, for `/msg` and `/reply` alike.

| Situation | What the sender sees | What the recipient gets |
| --- | --- | --- |
| The recipient turned messages off (`/msgtoggle off`) | "*Name* can't receive messages right now." | nothing |
| The recipient ignores the sender | the same "can't receive messages right now" | nothing |
| The recipient is vanished and the sender may not see them | the offline message, word for word: "Player *name* not found." | nothing |
| The recipient's preferences or ignore list have not loaded | "*Name*'s settings are still loading. Try again shortly." | nothing |
| The sender is muted | "You are muted." | nothing |

A blocked message produces nothing at all for the recipient: no chat line, no sound.

**Staff bypass** works only through permissions that must be granted explicitly. They default
to false, and neither ops nor `allium.*` get them:
- `allium.msg.bypass.toggle` reaches players who turned messages off.
- `allium.msg.bypass.ignore` reaches players who ignore the sender.
- `allium.vanish.see` (default op) sees vanished players, in messages and in tab completion.

Unloaded preferences fail closed even with the bypass permissions.

**Hooks:**
- **Vanish:** Allium reads the `vanished` player metadata that common vanish plugins set
  (SuperVanish, PremiumVanish, Essentials and others), plus Bukkit's own `hidePlayer`. Allium's
  own vanish will plug into `MessagingService#setVanishSource`.
- **Mute:** nothing is muted until moderation plugs into `MessagingService#setMuteSource`.

## What a player's message can contain

A message body is untrusted input.
- **Formatting permissions:** without the matching permission, every formatting sequence is shown
  exactly as typed:

  | Permission (default op) | Unlocks |
  | --- | --- |
  | `allium.msg.color` | `&` colour and formatting codes, `<reset>`, and decoration tags such as `<b>` |
  | `allium.msg.hex` | `&#RRGGBB`, `&x&R&R…`, a bare `#RRGGBB`, and `<#RRGGBB>` |
  | `allium.msg.gradient` | `<gradient:…>` and `<rainbow>` |

- **No interactive elements:** Allium's formatter has no click, hover or other interactive tags,
  so text like `<click:run_command:…>` or JSON stays plain text.
- **URLs stay plain:** on Spigot, messages are sent as explicit chat components rather than legacy
  strings. Spigot would turn URLs in a legacy string into clickable links.
- **Stripped characters:** `§` and control characters (including newlines) are always removed.
- **Names:** a typed player name that appears in a reply is reduced to the characters a Minecraft
  name can have.

## Sound

The recipient hears the `messaging.received` sound from the lang file. Its volume is the sound's
own volume times the recipient's message sound volume: their `/mfx` setting, or
`sounds.messages.fallback-volume`. The sender hears `messaging.sent`'s sound, if it has one, as
an ordinary message sound. See [message sounds and `/mfx`](sounds.md).

## /msgtoggle

- **Meaning:** `on` accepts messages and `off` rejects them. A bare `/msgtoggle` flips the setting;
  `on` and `off` set it, and repeating one changes nothing.
- **Other players:** `/msgtoggle [on|off] <player>` needs `allium.msgtoggle.others`. The console
  must always name a player.
- **Invalid input:** syntax such as `/msgtoggle a b c` prints usage, and an unknown player name
  gives "not found"; neither ever toggles the sender.
- **Storage:** the setting is `accept-messages` in the preference store. It persists across
  restarts and fails closed while a player's preferences load.
- **Without the preferences module** (`modules.preferences: false`), everyone accepts messages
  and `/msgtoggle` reports that message preferences are disabled.

## Storage

Ignore lists live in their own table, `allium_ignores (owner, ignored, created_at)`, with a
migration under the component `ignores`.
- **Loading:** a player's list loads at pre-login, like their preferences, and is dropped when
  they quit. Lists are capped at 500 entries.
- **Saving:** changes apply at once and are written on one bounded storage thread. A failed write
  is undone, and the player is told.

## Not yet

- **Social spy:** a small follow-up. It must never reveal vanished or ignored context.
- **Ignoring:** works for online players only, and staff cannot be made exempt from it.
