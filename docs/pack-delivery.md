# Delivering Allium's core resource pack

Allium generates its core pack at build time and bundles it in the jar: 7,055 bytes, SHA-1
`2413dc17…`, the same on every build. `resource-pack.owner` in `config.yml` decides who delivers
it, and changing it needs a restart. Players who decline the pack keep working, named vanilla
items unless the pack is required.

| Owner | What Allium does |
| --- | --- |
| `none` (default) | Nothing: it neither sends nor exports the pack. Another plugin or `server.properties` can serve it. |
| `standalone` | Allium exports the pack to `plugins/Allium/pack/allium-core-pack.zip`, with a `.sha1` file beside it. You host that file. Allium sends players `resource-pack.standalone.url` with the pack's SHA-1. Allium has no web server. |
| `rpm-export` | Allium writes the pack into ResourcePackManager's mixer (`rpm-export.mixer-directory`, as `rpm-export.file-name`), before any plugin enables. ResourcePackManager merges and sends it. Allium never edits RPM's config or any other pack. |

## standalone

1. Set `owner: standalone` and restart once. Allium exports the pack.
2. Upload `plugins/Allium/pack/allium-core-pack.zip` to your web host or CDN, and set
   `standalone.url` to it.
3. Restart. With `standalone.verify-hosted-file: true` (the default), Allium downloads the URL once
   at startup, off the server thread, and compares its SHA-1 with the bundled pack's.
   - **Match:** it sends the pack to every player who joins.
   - **Mismatch or unreachable:** it logs both SHA-1s, or the error, and sends nothing. Every
     client would reject a pack whose hash does not match. Upload the file again after every
     Allium update that changes the pack.

**Required or optional:**
- `required: false`: a player who declines, or whose download fails, keeps the named vanilla
  items. Allium logs it.
- `required: true`: the offer is forced, and declining or failing kicks the player with
  `kick-message`.
- `prompt` is the text the client shows with the offer.

### Several packs (1.20.3 and later)

From 1.20.3 a client can hold several server packs at once.
- Allium adds its pack under its own fixed UUID, `ee490115-…` (derived from `allium:core-pack`).
  It sits beside packs from `server.properties` or other plugins, and never replaces them.
- An updated Allium pack replaces only Allium's earlier one.
- Allium reacts only to statuses for its own UUID.

### One owner before 1.20.3

On 1.20–1.20.2 a client holds **one** server pack, and each new pack replaces the last.
**Exactly one delivery owner is mandatory** there: either Allium (`standalone`), or
`server.properties`/another plugin (`none`). If `server.properties` sets `resource-pack` while
Allium is `standalone`, Allium logs a warning at startup, because its pack replaces the
server's for every player.

## rpm-export

- **Placement:** Allium writes its pack in `onLoad`, before ResourcePackManager enables, so RPM
  merges the current pack on every start. The default file name, `Allium_core_resource_pack.zip`,
  is separate from any older `Allium_resource_pack.zip` (SF's mixer has one with Reliques
  assets); Allium never touches that file.
- **Carrier collisions:** before writing, Allium reads every other pack in the mixer. It reports
  each pack that defines one of its vanilla carrier files (`assets/minecraft/items/<carrier>.json`
  or `assets/minecraft/models/item/<carrier>.json`, at the root or in an overlay):
  - for a carrier Allium's pack ships today, a warning, because RPM keeps one file per path;
  - for a carrier of a planned tool, a note that it will collide once that tool ships.

  On SF this reports `Nexo_resource_pack.zip`'s `assets/minecraft/items/blaze_rod.json`, the
  carrier of the planned Phantom Obliterator. RPM's pack priority decides which file wins; set it
  deliberately.
- **Dry run:** `rpm-export.dry-run: true` reports the collisions and what would be written, with
  the existing file's SHA-1, and writes nothing.
- **Unreadable packs:** a pack Allium cannot read, for example for permissions, is reported and
  skipped.

## Verified

See the [S06c results](../tests/live/delivery/RESULTS.md): accept, decline and hash mismatch on
Spigot 1.20.1, Paper 1.21.8 and Paper 26.3, coexistence with a second pack on 1.20.3+, and an
`rpm-export` dry run and export against a copy of SF's mixer.
