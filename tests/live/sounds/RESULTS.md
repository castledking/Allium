# S08: message sound preferences gate

**PASS on 2026-11-09: 110 live checks, 0 failures**, all with Allium.jar `dc256661…`, 55 checks on
each lane. Two real protocol bots (`MsgA`, `MsgB`) and the console took part.

**Unit tests:** 158 plugin, 94 core and 4 API, 0 failures. New:
- `MessageSoundVolumeInputTest`: the strict `/mfx volume` parsing.
- `LegacyLangImportTest` (8) and `LegacyLangImporterTest` (2): the legacy lang import.
- `ResourceSyncManagerTest`: lang sync keeps a message entry whole.

**Where things are:**
- **Manifest:** every check, with the sound packets behind it, is in the
  [manifest](evidence/s08-manifest.json).
- **Run logs and summaries:** `/mnt/storage/.allium-validation/20261109/s08-sounds/`.
- **Operator guides:** [message sounds and `/mfx`](../../../docs/sounds.md) and
  [lang import](../../../docs/lang-import.md).

| Lane | Client | Checks |
| --- | --- | --- |
| Spigot 1.20.1 (3871-d2eba2c), Java 17 | minecraft-protocol 1.68.0, protocol 763 | 55 |
| Paper 26.3-141, Java 25 | owned raw client, protocol 777 | 55 |

## How it runs

- **Real messages only:** every volume and pitch is read from the sound packet `MsgB` (or `MsgA`)
  receives for a real `/msg`. Nothing is read from Allium's state.
- **Silence:** a silent check means the message's chat line arrived and no sound with the
  message's ID followed within 1.2 s. Vanilla world sounds are ignored.
- **Fixture and clients:** the S07 `MsgGate` fixture (permissions, faults) and the S07 clients
  are reused, on the same owned ports.
- **Changes mid-run:** the harness edits the lane's `config.yml` and `lang/en_US.yml` and runs
  `/allium reload`.
- **Pacing:** each bot sends at most one command a second, below the server's spam kick.

## What both lanes showed

- **Defaults:**
  - `messaging.received` (volume 0.6, no pitch) plays at 0.6 × the fallback 1.0, at pitch 1.0.
  - `/mfx` reports "1 (the server default)" and usage. `/msounds` and `/messagesounds` are the
    same command.
- **Setting a volume:**
  - `/mfx volume 0.5` makes the message play at 0.3.
  - `NaN`, `Infinity`, `-0.1`, `1.5`, `abc`, `1e309`, `0x1p-1` and `0.5f` are each refused with
    "is not a volume from 0 to 1; nothing changed", and the next message still plays at 0.3.
  - A missing volume and an unknown action print usage only.
- **Zero and toggling:**
  - `/mfx volume 0` mutes: the text arrives with no sound, and `/mfx` says what a toggle restores
    (0.5).
  - The toggle restores 0.5 (0.3 heard), mutes again, and restores again.
- **Reset:** `/mfx reset` follows the default again (0.6 heard).
- **Console and tab completion:**
  - The console gets "players only".
  - Tab completion offers `reset`, `toggle` and `volume`, then `0`, `0.25`, `0.5`, `0.75` and `1`.
- **Blocked sends** are still silent for the recipient.
- **Persistence:** `MsgB` set 0.5 and toggled to mute, `MsgA` set 0.8, then the server restarted.
  - `MsgB` was still muted.
  - The toggle still restored 0.5.
  - `MsgA` heard 0.48 (0.6 × 0.8).
- **Config changes through `/allium reload`:**
  - **Fallback 0.25:** `MsgB` (reset) hears 0.15 at once, and `MsgA`'s 0.8 override still gives
    0.48. `/mfx` shows the new default.
  - **Fallback 0:** `MsgB` is silent. A toggle with nothing to restore says sounds stay muted.
    `/mfx volume 0.5` still works (0.3).
- **Omitted values fall back separately,** with `sounds.defaults` set to volume 0.8 and pitch
  1.5:

  | `messaging.received` sets | Heard |
  | --- | --- |
  | volume 0.6, no pitch | 0.6, pitch 1.5 |
  | pitch 0.7, no volume | 0.8, pitch 0.7, and the reload did not put the volume back into the file |
  | only the sound | 0.8, pitch 1.5 |
  | volume 0 | silent |
  | pitch 3.0 (out of range) | pitch 1.5, with one warning across two messages |
  | `sound: none` | silent, with no warning |

- **Preferences still loading:** while `MsgB`'s preferences fail to load, `/mfx volume 0.2` says
  "settings are still loading" and changes nothing (0.7 afterwards).
- **The legacy lang import:** the pre-recode plugin's own bundled `lang.yml` was placed at
  `plugins/Allium/lang.yml`.
  - **Dry run** (`/allium import lang`): 18 to write, 313 not portable yet, and the target
    unchanged.
  - **Apply:** wrote them and left a byte-identical backup.
  - **Live result:**
    - `MsgB` now sees "(From MsgA) …".
    - `MsgB` hears the imported `minecraft:block.note_block.pling` at 0.5, pitch 1.5.
    - `MsgA` hears the imported sent sound at 0.5, pitch 2.
  - **Second apply:** said "Nothing to import", and the legacy file was untouched.
  - **Notes:** the import reported the dropped `[particle]` line, and the `{cmd}` placeholder in
    `no-permission` and `player-only-command`, which Allium's own texts do not use.
- **Log:** there were no listener or Allium exceptions in either server log.

## Found and fixed during this slice

- **Lang sync re-added omitted values.**
  - **The bug:** on every start and `/allium reload`, lang sync re-added any key the bundled file
    has. So removing `volume` or `pitch` from a message entry was silently undone, and the
    independent fallback could never be used.
  - **The fix:** a message entry (`text`/`sound`/`volume`/`pitch`) is now one value for sync. New
    messages are still added.
- **Preference defaults were read only at startup.** A reset player did not follow a changed
  `sounds.messages.fallback-volume` until a restart. `/allium reload` now re-reads them.
- **A flaky S07 unit test:** `ReplyTargetsAndIgnoresTest` compared ignore lists in insertion
  order, but two adds in one millisecond tie on `created_at`. The test now uses a ticking clock,
  and the query breaks ties by UUID.
- **Earlier run:** an earlier Paper run (`r2-paper-26.3`, jar `79722380…`) failed one check. The
  blocked-send check counted vanilla world sounds (registry IDs 1500/1501), as S07's harness once
  did. Only message sounds count now.

## Scope and limits

- **Sound IDs:** only namespaced sound IDs were exercised. A Bukkit `Sound` enum name is sent as
  a registry sound, which these clients log without a name.
- **Importer mappings:** the importer maps the 18 messages whose features exist. Mappings for
  other features arrive with them.
- **No other-player administration:** `/mfx` has none, matching the roadmap contract (self
  only).
- **Lanes:** Paper 1.21.8 and the older lanes did not run this gate.

## Rerun

```sh
./gradlew build
python3 tests/live/sounds/run.py --lane <spigot-1.20.1|paper-26.3> --run-name <name>
```

The runs use owned loopback ports 25692 (Spigot) and 25693 (Paper), the same as S07. They delete
their copied server files afterwards and exit nonzero on any failed check.
