# S11: social spy live gate

**PASS on 2026-11-12: 734 live checks, 0 failures**, with Allium.jar `9c5f0783…`.
- **Lanes:** 367 checks each on Spigot 1.20.1 (minecraft-protocol 1.68.0) and Paper 26.3-141 (the
  owned raw client).
- **Actors:** three real protocol bots (`SpyA`, `SpyB`, and `SpyC` the observer) and the console.
- **Fixture:** `SpyGate` grants and revokes permissions, toggles operator status, vanishes players
  (the common `vanished` metadata), hides them with Bukkit's `hidePlayer`, mutes them through
  Allium's mute hook and reads the stored preference.

The details are in the [manifest](evidence/s11-socialspy-manifest.json). The runs, with every
captured chat line and sound, are kept in `/mnt/storage/.allium-validation/20261112/s11-socialspy/`.
The operator guide is [docs/messaging.md](../../../docs/messaging.md#social-spy-s11).

Every claim below was verified live on both lanes unless it says otherwise.

## What it showed

- **Opt-in:**
  - **A fresh player** has the stored preference off. With `allium.socialspy` but without
    `/socialspy on`, the observer saw nothing.
  - **The command:** `/socialspy on` and `off` are idempotent, a bare `/socialspy` toggles, and
    invalid arguments change nothing.
- **Delivered traffic:** for every `/msg` and `/reply` between SpyA and SpyB, the observer got
  exactly one correctly addressed spy copy. Sender and recipient each got exactly one ordinary
  line and no spy duplicate.
- **Own conversations:** when SpyC itself sends or receives (including a `/reply` to its
  partner), it gets only its ordinary line, never a spy copy. Observing others does not change
  its reply target.
- **Blocked attempts are never shown:** toggled off, ignoring, muted, offline target and self. In
  each case the recipient and the observer get no chat line and no sound, and the console gets no
  copy. The toggle and ignore refusals read the same for the sender.
- **Permission:**
  - **Removed:** observation stops at the next message, while the stored opt-in stays on.
    `/socialspy` is then refused as an unknown command, because Allium hides commands a player
    may not use, and the setting is unchanged.
  - **Restored:** observation resumes.
- **Exemption:** `allium.socialspy.exempt` is registered with default false. Neither operator
  status nor `allium.*` grants it. When either SpyA or SpyB has it, the observer sees nothing, and
  removing it restores the copy.
- **Vanish:** traffic with a hidden participant (SpyA or SpyB, hidden by the `vanished` metadata
  or by Bukkit's `hidePlayer`) reaches the observer only when the observer can see that
  participant. Revoking visibility stops it again.
- **Untrusted bodies:** a body with formatting tags and a URL reached the recipient and the
  observer literally, in white, with no click, hover or insertion data. Colors the sender is
  allowed (`allium.msg.color`) reach the spy exactly once, with the sender's permissions.
- **Sound:**
  - **By default** the spy copy plays no sound.
  - **With a configured sound** (`messaging.socialspy.message` set to experience orb, volume 0.6),
    the observer heard exactly one sound per copy at its `/mfx` volume: 0.6 × 0.25 = 0.15. With
    `/mfx` muted it heard none.
  - **Own conversations** keep only the ordinary received sound.
- **Console logging:** `messaging.socialspy.log-to-console` was false before an explicit opt-in.
  - **Opted in:** delivered `/msg` and `/reply` bodies appear once in the server log.
  - **Never logged:** exempt traffic and the console's own conversations. A player observer
    still sees a delivered console conversation.
- **Persistence:** after a restart, SpyC's saved opt-in (on) and SpyA's (off) were both kept, and
  SpyC observed again without re-running the command.
- **Log:** there were no listener or Allium exceptions on either lane.

## Found and fixed in the gate harness

- **The fixture's console command** had `permission: ... default: false`. In Bukkit that denies
  even the console, so the fixture now checks for the console in code instead.
- **Bots died:** a mob killed a bot mid-run, and a dead player's commands are refused. The lanes
  now run on peaceful difficulty.

## Unit tests only

- **Router, service, command and storage:** `NotificationRouterS11Test` (33),
  `MessagingServiceS11Test` (36), `SocialSpyCommandTest` (3) and `SocialSpyPreferenceTest` (6).
- **Migrator:** a duplicate `ADD COLUMN`, from a restart between the DDL and the version record,
  is tolerated only for that exact dialect error.
- **Not verified:** social spy on MySQL (both lanes ran SQLite).

## Rerun

```sh
./gradlew build
python3 tests/live/socialspy/run.py --lane spigot-1.20.1 --run-name <name>
python3 tests/live/socialspy/run.py --lane paper-26.3 --run-name <name>
```

It uses the owned ports 25710 (Spigot) and 25711 (Paper), and the inputs in
`/mnt/storage/.allium-validation/infra/`.
