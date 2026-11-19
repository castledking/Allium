# Timed broadcasts and `/broadcast`

Timed broadcasts are off until `broadcasts.enabled` is set. Each `#section` of
`plugins/Allium/broadcasts.txt` is one broadcast, in the layout, formatting, placeholders and
click/hover tags of [customtext.txt](customtext.md). Lines before the first section are not sent.
The file is written from the bundled example on first start and read again for each broadcast,
so edits apply from the next one.

```yaml
broadcasts:
  enabled: false
  # 10-86400
  interval-seconds: 300
  # sequential (the file's order) or random (never the same one twice in a row)
  order: sequential
  # Skip a turn while fewer unvanished players than this are online.
  minimum-players: 1
```

Every interval, the next broadcast goes to each online player with `allium.broadcasts.receive`
(default true; negate it to opt a player or group out) and to the console. Placeholders are
filled for each recipient, so `{player}` greets everyone by name. A sequential rotation follows
the file's order and keeps its place when broadcasts are added or removed. Out-of-range or
unknown settings fall back to their bounds or defaults with a startup warning. `/allium reload`
re-reads the settings and restarts the timer.

| Command | Permission (default op) | Does |
| --- | --- | --- |
| `/broadcast <message>` (`/bc`, `/bcast`) | `allium.broadcast` | Sends `chat.broadcast` with the message to everyone online, ignoring the timed opt-out; colours and click tags work. The console log names the sender |
| `/broadcasts` | `allium.broadcasts.admin` | Shows whether timed broadcasts are on, their settings and the broadcasts |
| `/broadcasts send <name>` | `allium.broadcasts.admin` | Sends one broadcast now (an alias works) |
| `/broadcasts next` | `allium.broadcasts.admin` | Sends the next broadcast of the rotation now |
