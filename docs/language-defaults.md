# Language defaults and upgrades

Both message forms are supported:

```yaml
reload-success: '{prefix} &aConfiguration reloaded.'
```

```yaml
reload-success:
  text: '{prefix} &aConfiguration reloaded.'
  sound: minecraft:block.note_block.chime
  volume: 0.55
  pitch: 1.35
```

A plain string has no per-message sound. Bundled defaults now use sections for
many interactive messages, while detail rows remain silent. YAML's wrapped
quoted strings are valid; the line wrapping is not why a message lacks colors.

Startup and `/allium reload` add missing message entries but preserve an existing
entry as a whole. An existing string is not silently converted to a section, and
omitted sound/volume/pitch values are not filled into an existing section. This
protects operator text and sound customizations, even if the installed entry still
matches a previous default.

To adopt every current bundled default, first back up the active file under
`plugins/Allium/lang/`, rename it out of the way, then run `/allium reload` with the
updated jar. The missing bundled locale file is regenerated. Reapply desired
customizations from the backup. Alternatively, replace only selected message
entries with the section form. Do not delete the whole plugin data directory.

`utility.gc.*` controls diagnostic text, including world rows and the paused label.
`chat.delete.*` controls deletion confirmations, usage and amount/not-found refusals.
`chat.player-not-found` is the generic deletion target refusal. These paths now use
the configured `{prefix}` and the normal language/color/sound renderer. GC detail
rows are silent by default; deletion confirmations use a quiet page turn. Other
chat-service feedback still has separate hardcoded text and is not migrated by
this focused fix.
