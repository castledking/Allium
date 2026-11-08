# S06c: core pack delivery gate

**PASS on 2026-11-08: 52 live checks, 0 failures**, all with Allium.jar `32a4687b…`:
- **standalone:** 44 checks on Spigot 1.20.1, Paper 1.21.8 and Paper 26.3.
- **rpm-export:** 8 checks, against a copy of SF's ResourcePackManager mixer directory.

Unit tests: 146 plugin, 69 core and 4 API, 0 failures. Every check and the bot's view of every
offer are in the [manifest](evidence/s06c-manifest.json). Run logs and summaries are kept in
`/mnt/storage/.allium-validation/20261108/s06c-pack-delivery/`. The operator guide is
[docs/pack-delivery.md](../../../docs/pack-delivery.md).

| Lane | Client | Packs per client | Checks |
| --- | --- | --- | --- |
| Spigot 1.20.1 (3871-d2eba2c), Java 17 | minecraft-protocol 1.68.0, protocol 763 | one (legacy `resource_pack_send`) | 14 |
| Paper 1.21.8-60, Java 21 | minecraft-protocol 1.68.0, protocol 772 | several, by UUID | 15 |
| Paper 26.3-141, Java 25 | owned raw client, protocol 777 | several, by UUID | 15 |
| Paper 26.3-141 (rpm-export) | none | — | 8 |

## How it runs

- **Hosting:** the harness serves the pack on loopback port 25689, as an operator's host would.
  Allium has no web server.
- **Server:** Allium runs with `owner: standalone` and `standalone.url` pointing there.
- **Bot:** a real protocol bot answers each pack offer.
  - accept: it reports ACCEPTED, downloads the URL and compares its SHA-1 with the offered hash.
    It then reports DOWNLOADED (1.20.3+) and SUCCESSFULLY_LOADED, or FAILED_DOWNLOAD on a
    mismatch.
  - decline: it reports DECLINED and downloads nothing.
- **Second pack:** on the 1.20.3+ lanes, `server.properties` also offers a second pack, with its
  own UUID.

## What each standalone lane showed

- **Export and startup check:**
  - Allium exported the bundled pack (7,055 bytes, SHA-1 `2413dc17…`) and its `.sha1` to
    `plugins/Allium/pack/`.
  - At startup it downloaded the hosted URL once and verified the SHA-1.
- **Optional:**
  - **Accept:** the bot was offered Allium's pack with its SHA-1, not forced, with the configured
    prompt. It downloaded it, the hash matched, it loaded, and Allium logged it.
  - **Decline:** the bot downloaded nothing and stayed, keeping the vanilla items. Allium logged
    it.
  - **Hash mismatch:** the hosted file was swapped after the startup check. The bot found the
    wrong SHA-1, reported FAILED_DOWNLOAD, and stayed.
- **Required:**
  - The offer is forced, and a bot that loads it stays.
  - Declining, and a hash mismatch, each get the player kicked with `kick-message`: "This server
    requires its resource pack. Reconnect and accept it to play."
- **Stale hosted file at startup:** Allium's check failed, and the log gave both SHA-1s and the
  file to upload again. Allium then sent nothing, and the player stayed with the vanilla items.
- **Coexistence (Paper 1.21.8 and 26.3):**
  - The `server.properties` pack (`5e3c0d1e…`) arrived during configuration, and Allium's
    (`ee490115…`) in play.
  - Both were offered and loaded, and nothing removed either one.
  - Allium tracked only its own pack's status.
- **One owner (Spigot 1.20.1):**
  - The bot was sent Allium's pack as its one server pack, with no UUID.
  - With `server.properties` also setting a pack, Allium warned at startup that exactly one
    delivery owner is mandatory before 1.20.3.

## rpm-export against a copy of SF's mixer

- **Copy:** 13 of the 14 files were copied read-only from SF's
  `plugins/ResourcePackManager/mixer`. `CraftEngine_resource_pack.zip` is mode 0600 and was not
  readable, so it was not copied.
- **Dry run (`dry-run: true`):**
  - Allium read all 13 packs.
  - It reported the blaze rod collision: `Nexo_resource_pack.zip` defines
    `assets/minecraft/items/blaze_rod.json`, the carrier of the planned Phantom Obliterator.
  - It reported no collision for the golden hoe the current pack ships.
  - It said it would write `Allium_core_resource_pack.zip` ("no such file exists yet"), and it
    wrote nothing: every file stayed byte-identical.
- **Export:**
  - Allium wrote only `Allium_core_resource_pack.zip`: the bundled pack, byte for byte.
  - Every other file is unchanged, including SF's legacy `Allium_resource_pack.zip`.
  - Allium did not send the pack itself.
  - A second start kept the identical file.
- **SF's live mixer** was untouched: the same files, sizes and modification times before and
  after. The copy was deleted after the run and is not part of the persisted evidence.

## Follow-up: `/allium reload`, `pack-file` and the SF-art build (2026-11-08)

**70 live checks, 0 failures** on Allium.jar `df1d53e4…`: 20 on Spigot 1.20.1, 21 on Paper 1.21.8,
21 on Paper 26.3, and 8 for rpm-export. Every earlier check passed again.

- **`/allium reload`** is new (`allium.reload`). It reloads `config.yml`, the lang files and
  `disabled-commands`, then re-applies the `resource-pack` settings.
  - After a stale hosted file had failed the startup check, the host was fixed and the reload
    re-checked it: "The hosted resource pack matches; sending it." A player who joined next got
    the pack and loaded it.
  - With the host stale again, a reload logged "Not sending the resource pack: the file at … has
    SHA-1 …", and the next player was sent nothing.
- **`resource-pack.pack-file`:**
  - A local build, the bundled pack with one texture swapped (standing in for SF's art), was
    exported, checked at startup and offered with its own SHA-1. The bot loaded it.
  - A `pack-file` without the core models, applied by a reload, logged "lacks the core pack's
    […]", and Allium went back to the bundled pack. The next offer carried the bundled SHA-1.
- **SF's art:** `pack/tools/sf_export.py` builds `build/sf-export/allium-core-pack-sf.zip`
  (8,932 bytes, SHA-1 `417076f4…`) locally from SF's four disarmer textures. It is git-ignored
  and was not used in the gate, so no SF art is in the evidence.
- **Docs:** [pack delivery](../../../docs/pack-delivery.md) now covers reload and `pack-file`. It
  also warns that `required: true` plus a host outage after startup means nobody can join. The real
  ResourcePackManager merge is on the staging checklist in `todo.md`.

## Scope and limits

- **No real merge:** ResourcePackManager itself was not run against the copy. The gate shows what
  Allium writes and reports; RPM's merge and its priority order are RPM's.
- **No rendering:** the bot verifies download and hash, not rendering. The S06b gate validated the
  pack's content per client format, and the S06b
  [visual checklist](../pack/VISUAL-CHECKLIST.md) is still a manual step in a real client.
- **Regression:** the baseline gate passed again on this jar (31 checks on each of Spigot 1.20.1
  and Paper 26.3).

## Rerun

```sh
./gradlew build
python3 tests/live/delivery/run.py --lane <spigot-1.20.1|paper-1.21.8|paper-26.3> --run-name <name>
python3 tests/live/delivery/run.py --lane paper-26.3 --scenario rpm --run-name <name>
```

The runs use owned loopback ports 25686–25688 for servers and 25689 for hosting. They delete
their copied server files afterwards and exit nonzero on any failed check. The rpm scenario
reads SF's mixer only to copy it, and checks that the live directory is unchanged.
