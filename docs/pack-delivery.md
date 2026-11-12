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

> **`required: true` plus a host outage means nobody can join.** If the host goes down or serves
> a different file after Allium's startup check, every joining player's download fails, and every
> one of them is kicked. That lasts until the host is back, or until you set `required: false` and
> run `/allium reload`. (If the host is already down at startup, the check fails, Allium sends
> nothing, and players join without the pack instead.) Use a reliable host, or keep the pack
> optional.

### `/allium reload`

`/allium reload` (permission `allium.reload`) reloads `config.yml` and the lang files, then
applies the `resource-pack` settings:
- It takes the URL, `required`, `prompt`, `kick-message` and `pack-file` from the reloaded
  config, and re-exports the pack.
- For `standalone`, it downloads the hosted file again and checks its SHA-1. On a match, Allium
  sends the pack, including to online players who do not have it yet. On a mismatch, it stops
  sending until the next successful check.
- For `rpm-export`, it rewrites the mixer file; reload ResourcePackManager to merge it.
- A changed `owner` is reported and takes effect only after a restart.

### Another pack build (`pack-file`)

The jar bundles Allium's own, distributable art. To deliver other art, such as SF's disarmer
textures, build a pack from Allium's manifest with that art and point `resource-pack.pack-file`
at it, relative to `plugins/Allium/`:

```sh
SF=/path/to/sf/plugins python3 pack/tools/sf_export.py   # writes build/sf-export/allium-core-pack-sf.zip (git-ignored)
cp build/sf-export/allium-core-pack-sf.zip <server>/plugins/Allium/pack/
# config.yml: resource-pack.pack-file: pack/allium-core-pack-sf.zip
```

- **Validation:** the file must keep every model, item definition and `pack.mcmeta` of the bundled
  pack. Textures may differ, and it may add models for tools the bundled pack has no art for yet
  (the Phantom Obliterator and the Lazy Axe). Otherwise Allium warns and delivers the bundled pack.
- **Delivery:** `standalone` exports this file (so host the exported
  `plugins/Allium/pack/allium-core-pack.zip`), and `rpm-export` writes it to the mixer.
- **SF art stays out of git:** SF's art is server-only. `sf_export.py` builds it locally from
  `$SF/Nexo/pack/assets/sf/textures/items/`, and it is never committed or published.
  - **How:** it writes a derived manifest (`build/sf-export/manifest-sf.json`) in which the SF
    tools are ready, then builds and, with `--clients <dir>`, validates the pack against vanilla
    client jars.
  - **Mob Disarmer and Phantom Obliterator:** SF has four charge textures for each (`<id>`,
    `_2-3`, `_1-3`, `_0-3`). They map to `full`, `two_thirds`, `one_third` and `empty`, and
    `broken` reuses `empty`, since SF has no broken art.
  - **Lazy Axe:** SF's `tree_axe` texture, only with `--include-lazy-axe`. The
    default export excludes this tool's dispatch so it cannot shadow Nexo's axe.
    Opt in only for standalone use or after choosing the merged dispatch owner.
  - **Its carrier collides:** the Lazy Axe's carrier is the netherite axe, which SF's Nexo pack
    also overrides (its `tree_axe`).
    - **In a merged pack (ResourcePackManager),** one of the two netherite axe overrides wins, so
      either Allium's Lazy Axe model or Nexo's `tree_axe` model shows as a plain netherite axe.
    - **On SF,** keep the Lazy Axe on its Nexo appearance: a pre-recode `tree_axe` keeps
      `nexo:tree_axe` when it is adopted. Do not serve both overrides until the merge order is
      decided.

### SF texture follow-up verification (2026-11-12)

- **Automated pack validation:** the default local SF export contains Mob Disarmer
  and Phantom Obliterator art (SHA-1 `682bdf1d917ae081eb5e2498c990ece3c80490f9`,
  17,955 bytes). An explicit `--include-lazy-axe` export adds the axe (SHA-1
  `85fe5dc51e3745072a74492525cb686b2828ba15`, 22,538 bytes). Structural/model
  resolution checks pass against client jars 1.20.1, 1.21.8 and 26.3 with zero errors.
  `--clients` requires all three client JARs; missing files fail before replacing
  an existing export. Local provenance and validation remain in `build/sf-export/`
  and `build/sf-export-with-lazy-axe/`; SF texture bytes
  and the generated ZIP are not committed or published.
- **Unit-tested:** local-export manifest validation accepts server-only art while
  distributable builds continue rejecting it, and charge models select from the
  manifest. See `CorePackTest`, `CoreItemsTest`, and
  `python3 -m unittest discover -s pack/tools -p test_sf_export.py` for collision
  opt-in and missing-input regressions.
- **Not visually verified:** appearance in a rendered Minecraft client. No pack
  was installed on SF, and no SF configuration was changed. The netherite-axe
  carrier collision above still requires a deployment choice.

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

  On SF this used to report `Nexo_resource_pack.zip`'s `assets/minecraft/items/blaze_rod.json`,
  then the planned Phantom Obliterator's carrier. S10 moved that tool to the phantom membrane,
  which no readable SF pack overrides, so the audit now reports nothing on SF. Where it does
  report a collision, RPM's pack priority decides which file wins; set it deliberately.
- **Dry run:** `rpm-export.dry-run: true` reports the collisions and what would be written, with
  the existing file's SHA-1, and writes nothing.
- **Unreadable packs:** a pack Allium cannot read, for example for permissions, is reported and
  skipped.

## Verified

See the [S06c results](../tests/live/delivery/RESULTS.md): accept, decline and hash mismatch on
Spigot 1.20.1, Paper 1.21.8 and Paper 26.3, coexistence with a second pack on 1.20.3+, and an
`rpm-export` dry run and export against a copy of SF's mixer.
