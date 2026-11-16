# Language message format

Bundled messages use lists of text lines and an optional sound directive:

```yaml
teleport:
  success:
    - "{prefix} &aTeleported to &f{target}&a."
    - "[sound] block.note_block.pling 1.35"
```

Quote the entire directive: unquoted `[sound]` is YAML flow-list syntax, not a
message string. Text lines join with newlines. Only one sound is selected; sound
lines do not appear in chat. This format never executes commands.

Sound syntax is `[sound] id [pitch] [volume]`. One number is **pitch**. Omitted
volume uses `sounds.defaults.volume`; omitted pitch uses `sounds.defaults.pitch`.
Bundled messages omit all volumes. Private-message notification scaling by `/mfx`
still applies after these defaults. To specify volume while inheriting pitch,
use `"[sound] custom:bell default 0.4"`. `none`/`off` explicitly silence the entry,
including when the fallback locale has a sound.

Bare dotted IDs default to `minecraft:`. `minecraft:block.note_block.pling` is
also valid, and `custom:bell` retains its namespace. The namespace may be omitted
from vanilla sounds in the file; playback resolves it canonically.

Plain strings and the previous `{text, sound, volume, pitch}` sections remain
supported. Startup and `/allium reload` preserve existing entries as a whole;
upgrading the jar does not rewrite an operator's text, omitted fields or sound
choices. New entries use bundled lists. The root `prefix` may remain a string.

To adopt every current bundled default, back up the active locale file under
`plugins/Allium/lang/`, rename it out of the way, then `/allium reload` with the
updated jar. The missing bundled locale is regenerated; reapply customizations
from the backup. Alternatively convert selected entries by hand. Never remove
the whole plugin data directory.

`utility.gc.*` controls diagnostic text; `chat.delete.*` controls deletion
feedback. `/back` uses `teleport.back`, `/spawn` uses `spawn.teleport`, and nickname
save/reset uses `player.nick-set`/`player.nick-reset`. See the
[message-key audit](message-key-audit.md) for obsolete alternatives and dynamic
lookups. Existing pre-recode language imports are parsed with their historical
volume-then-pitch convention and converted into this new pitch-first list format.
