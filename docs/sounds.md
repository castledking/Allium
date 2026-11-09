# Message sounds and `/mfx` (S08)

Allium plays message sounds itself. Any lang entry can carry a sound, and private-message
notifications are scaled by each recipient's own message sound volume.

## A message's sound

In `lang/<locale>.yml`, a message is either text, or a section with text and a sound:

```yaml
messaging:
  received:
    text: "&7[&f{sender} &7→ &fme&7] &f{message}"
    sound: minecraft:entity.experience_orb.pickup
    volume: 0.6        # optional; 0 or more
    pitch: 1.2         # optional; 0.5 to 2.0
```

- **The parts:**
  - `sound` is a namespaced sound ID, or a Bukkit `Sound` name.
  - A sound alone, with no `text`, is valid: it plays without a chat line.
  - `sound: none` (or `off`) switches a message's sound off, with no warning.
- **Omitted values fall back separately:**
  - **Volume:** an omitted volume uses `sounds.defaults.volume`.
  - **Pitch:** an omitted pitch uses `sounds.defaults.pitch`.
  - **Bad values:** a volume or pitch outside its range counts as omitted, with one warning in
    the log per sound and value, not one per message.
- **An unknown sound ID** leaves the text working and logs one warning.
- **Updates keep your omissions:** when an Allium update adds messages, it adds the missing
  ones to your lang file. It never puts back a `volume` or `pitch` you removed from an entry.

```yaml
# config.yml
sounds:
  defaults:
    volume: 1.0        # for message sounds whose entry sets none
    pitch: 1.0
  messages:
    fallback-volume: 1.0   # each player's message sound volume until they set their own
    scale-sent-sound: true # /mfx also scales the sender's own sent sound
```

## Private-message notifications

The sound a recipient hears for a private message plays at:

> the entry's volume (or `sounds.defaults.volume`) × the recipient's message sound volume

- **The recipient's volume** is their own `/mfx` setting, or `sounds.messages.fallback-volume`
  when they have none.
- **Zero on either side mutes reliably.** For example, a recipient at 0.5 hears an entry
  authored at 0.6 at 0.3.
- **The pitch** is the entry's own, or `sounds.defaults.pitch`.
- **The sender's sound:** if `messaging.sent` has a sound, the sender hears it scaled the same
  way, by their own message sound volume. `/mfx toggle` and `/mfx volume 0` therefore silence both
  directions.
  - **`sounds.messages.scale-sent-sound: false`** makes the sent sound an ordinary message sound
    that `/mfx` does not touch.
  - **Preferences still loading:** the sent sound is silent until they load.
- **Blocked sends are silent:** no sound plays for a send that was blocked (messages off,
  ignored, vanished, settings still loading).

## `/mfx`

`/messagesounds`, `/msounds` and `/mfx` are the same command. It is for players only, with
permission `allium.messagesounds` (everyone by default).

| Command | Effect |
| --- | --- |
| `/mfx` | Shows your volume and whether it is your setting or the server default (or that you muted it, and what a toggle brings back), then usage. |
| `/mfx volume <0-1>` | Sets your volume. Only plain decimals are accepted (`0`, `.5`, `0.35`, `1`). Anything else, including `NaN`, infinity, negative numbers, values over 1, exponents, hex and `0.5f`, is refused and changes nothing. `0` mutes. |
| `/mfx toggle` | An audible volume goes to 0, and a positive setting is remembered. From 0 it restores the remembered setting, or the server default. If that default is 0 too, it says sounds stay muted. |
| `/mfx reset` | Removes your setting and the remembered one. You follow `sounds.messages.fallback-volume` from now on, including after the server changes it. |

**Storage:**
- **Persistence:** your setting and the remembered volume are stored with your other
  preferences. They survive reconnects and restarts.
- **Loading:** while your preferences load, `/mfx` refuses with "settings are still loading" and
  changes nothing.
- **Preferences module off** (`modules.preferences: false`): `/mfx` says message sound
  preferences are disabled, and everyone hears the fallback volume.

**Config changes:** `/allium reload` applies new `sounds.*` values and new preference defaults at
once.
- **Followers:** players without their own setting follow `fallback-volume` from that moment.
- **Settings:** players with a setting keep it.

## Verified

See the [S08 results](../tests/live/sounds/RESULTS.md). Every volume and pitch above was read
from the sound packet a real client received, on Spigot 1.20.1 and Paper 26.3.
