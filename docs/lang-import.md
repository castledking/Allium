# Importing a pre-recode `lang.yml`

Pre-recode Allium kept its messages in `plugins/Allium/lang.yml`. The recode keeps them in
`plugins/Allium/lang/<locale>.yml`, with different keys and formatting. The importer moves the
messages that have a recode counterpart.

```text
/allium import lang          # dry run: reports, writes nothing
/allium import lang apply    # writes, after backing the target up
```

Permission: `allium.import` (operators). The console log lists each message with its status and
every note; the command reply gives the totals.

## What it does

- **Source and target:**
  - It reads `plugins/Allium/lang.yml`, which is never changed.
  - It writes the **active** locale's file (`settings.locale`, e.g. `lang/en_US.yml`).
- **Statuses:** each message the importer knows gets one:
  - **WRITE:** the target still has Allium's own default (or nothing) there, so the legacy text
    is written.
  - **SAME:** the target already has exactly the imported value.
  - **KEPT:** you already changed this message in the recode, so it is left alone and reported.
- **Backup:** `apply` first copies the target to `<file>.before-import-<timestamp>`, then reloads
  the lang files.
- **Running it again** writes nothing new (everything is SAME), so it is safe to repeat.
- **Not portable yet:** legacy keys with no recode counterpart are counted and listed. Most
  belong to features the recode has not ported yet.

## How a message is converted

| Pre-recode | Recode |
| --- | --- |
| A string or a multi-line string | The text |
| A list of lines | Text lines joined with line breaks |
| `[sound] <id> [volume] [pitch]` (or `[sound <id> …]`) | `sound`, plus `volume` and `pitch` only where given, so the others fall back separately to `sounds.defaults`. `block.note_block.pling` becomes `minecraft:block.note_block.pling`. |
| `[particle] …`, `[command …]` and other directives | Dropped and reported. A lang entry can never run a command. |
| MiniMessage colours and decorations (`<red>`, `<bold>`, …) | `&` codes. Closing tags are dropped, since `&` codes do not revert: check colours after a closing tag. |
| `<gradient:…>`, `<rainbow>`, `<#RRGGBB>` | Kept; Allium's formatter reads them. |
| `<click>`, `<hover>` and other interactive or unknown tags | Removed and reported. |
| Placeholders | Kept. Renamed where the recode uses another name (`{name}` → `{player}`). A placeholder the recode's own text does not use is reported, since Allium may not fill it. |
| No prefix (pre-recode Allium added prefixes in code) | `{prefix}` is added where Allium's own text starts with it. `core-prefix` becomes `prefix`. |

## Mapped messages

| Pre-recode key | Recode key |
| --- | --- |
| `core-prefix` | `prefix` |
| `no-permission`, `player-only-command`, `console-only`, `player-not-found` | `error.no-permission`, `error.player-only`, `error.console-only`, `error.player-not-found` |
| `reload.reload-success`, `reload.reload-failure` | `core.reload-success`, `core.reload-failed` |
| `heal.self`, `heal.healed-other` | `player.heal`, `player.heal-other` |
| `feed.self`, `feed.other` | `player.feed`, `player.feed-other` |
| `fly.toggle`, `god.toggle` | `player.fly-toggle`, `player.god-toggle` |
| `gamemode.switch`, `gamemode.switch-other` | `player.gamemode-self`, `player.gamemode-other` |
| `msg.sent`, `msg.received`, `msg.reply` | `messaging.sent`, `messaging.received`, `messaging.no-reply-target` |

More mappings arrive with the features they belong to.
