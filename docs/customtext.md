# Custom text (`/customtext`)

`/customtext [section] [page]` shows pages of `plugins/Allium/customtext.txt`. The file is
written from the bundled example on first start and read again whenever it changes, so edits
show without `/allium reload`. Players need `allium.customtext` (default true).

## Layout

The layout is Essentials' `custom.txt`, so an existing file can be copied over:

- `#vote,voting` starts the section `vote`, also reachable as `voting`. Names are
  case-insensitive and spaces become underscores.
- Lines before the first section are the index, shown by `/customtext` with no section.
- Lines starting with `##` are comments (an Allium addition).
- A section longer than 9 lines is paged, with the `/help` page footer and its clickable arrows.

To give a section its own command, add an alias to the server's `commands.yml`, as with
Essentials:

```yaml
aliases:
  vote:
  - customtext vote $1-
```

## Formatting and click events

Lines use Allium's formatting (`&` codes, `&#RRGGBB`, `<gradient:...>`, `<rainbow>`) and the
placeholders `{player}`, `{displayname}`, `{world}`, `{online}` (vanish-aware), `{max}` and
`{server}`, plus PlaceholderAPI's `%placeholders%` when it is installed. Values a player
controls lose `<` and `>`, so a display name cannot open a click tag.

Click and hover events use the tags the Essentials fork's text files take:

| Tag | Effect |
| --- | --- |
| `<click:run_command:/spawn>text</click>` | Runs a command as the player |
| `<click:suggest_command:/msg >text</click>` | Puts text in the chat box |
| `<click:open_url:https://example.com>text</click>` | Opens a link; only `http` and `https` |
| `<click:copy_to_clipboard:text>text</click>` | Copies text |
| `<hover:show_text:'&aShown\n&7on hover'>text</hover>` | Hover text; `\n` is a new line |

Values may be quoted with `'` or `"`, tags nest, and colours carry across tag boundaries. A
gradient that spans a tag boundary is split there. Unknown tags and text such as `<3` stay as
written.

A whole line of vanilla JSON text, as `/tellraw` takes it, is sent unchanged:

```
{"text":"Copy the address","color":"gray","clickEvent":{"action":"copy_to_clipboard","value":"play.example.com"}}
```

The console sees the text without click events.
