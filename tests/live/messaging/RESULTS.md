# S07: private messaging gate

**PASS on 2026-11-08: 46 live checks, 0 failures**, all with Allium.jar `c877b783…`, 23 checks on
each lane. Two real protocol bots (`MsgA`, `MsgB`) and the server console took part.

Unit tests: 156 plugin, 81 core and 4 API, 0 failures. The policy matrix is in
`MessagePolicyTest` (8 tests) and `ReplyTargetsAndIgnoresTest` (4); sanitization, including
injection attempts, is in `UntrustedTextTest` (6). Every check, with what the bots received, is in
the [manifest](evidence/s07-manifest.json). Run logs and summaries are kept in
`/mnt/storage/.allium-validation/20261108/s07-messaging/`. The operator guide is
[docs/messaging.md](../../../docs/messaging.md).

| Lane | Client | Checks |
| --- | --- | --- |
| Spigot 1.20.1 (3871-d2eba2c), Java 17 | minecraft-protocol 1.68.0, protocol 763 | 23 |
| Paper 26.3-141, Java 25 | owned raw client, protocol 777 | 23 |

## How it runs

- **Fixture:** a small test plugin, `MsgGate`, stands in for features that arrive later:
  - It sets the `vanished` player metadata and Bukkit's `hidePlayer` (a vanish plugin).
  - It mutes a sender through `MessagingService#setMuteSource` (moderation).
  - It grants and revokes permissions through attachments.
  - It injects one preference-load failure (`-Dallium.fault-injection=true`).
- **Silence:** a blocked send counts as silent only if the recipient bot got no chat line and no
  `entity.experience_orb.pickup` sound for 1.5 s afterwards. Other sounds, such as world ambience,
  are ignored.

## What both lanes showed

- **Send:**
  - MsgB got "[MsgA → me] hello there", and MsgA saw "[me → MsgB] hello there".
  - MsgB heard `minecraft:entity.experience_orb.pickup` at volume 0.6: the lang volume (0.6)
    times MsgB's message volume (1.0).
- **Reply:**
  - MsgB's `/r` reached MsgA, its last partner.
  - The console sent to MsgA, MsgA replied to the console, and the console replied to MsgA.
- **Reply target offline:** after MsgB quit, MsgA's `/r` said "You have nobody to reply to."
- **Block paths:** the recipient got nothing on every path, with no chat line and no sound.

  | Path | Sender saw |
  | --- | --- |
  | MsgB toggled messages off | "MsgB can't receive messages right now." |
  | MsgB ignores MsgA | the same message, byte for byte |
  | MsgB is vanished | "Player MsgB not found.", word for word the offline message |
  | MsgA is muted | "You are muted." |
  | MsgB's preferences failed to load | "MsgB's settings are still loading. Try again shortly." |
  | MsgA messages themself | "You can't message yourself." |

- **Bypass, only when granted:**
  - With `allium.vanish.see`, MsgA could message the vanished MsgB.
  - With `allium.msg.bypass.toggle`, granted explicitly, MsgA reached MsgB, who had messages off.
- **Tab completion:**
  - The vanished MsgB was missing from MsgA's `/msg` completions (`['MsgA']`).
  - MsgB was listed with `allium.vanish.see` (`['MsgA', 'MsgB']`) and again after unvanishing.
  - A player without `allium.msgtoggle.others` was offered only `on` and `off` for `/msgtoggle`.
- **Untrusted input:**
  - With no formatting permission, MsgB received every sequence exactly as typed, all in the
    template's white: `&cRed <gradient:#ff0000:#00ff00>grad</gradient> #00ff00hex <b>bold</b>
    <click:run_command:/op MsgA>op me</click>`.
  - A message with URLs reached MsgB as plain text, with no click or hover data on any
    component.
  - With `allium.msg.color` alone, `&c` applied and the hex code stayed literal.
- **`/msgtoggle` syntax, from the console:**
  - A bare `/msgtoggle` and `/msgtoggle foo bar baz` only printed usage.
  - An unknown name gave "not found".
  - `/msgtoggle off MsgB` set MsgB's setting, and MsgB was told.
- **Persistence across a restart:**
  - MsgB ignored MsgA, and the console had turned MsgB's messages off. Then the server was
    restarted.
  - After the restart, `/ignore` still listed MsgA, and MsgA's message was blocked with the
    generic message and silence.
  - A bare `/msgtoggle` reported "You now accept private messages.", so the stored setting had
    stayed off. After `/unignore`, MsgA's message arrived.
- **Log:** there were no listener or Allium exceptions in either server log.

## A bug this gate found, fixed before the final run

**Spigot made URLs clickable.**
- On Spigot, legacy strings go through `CraftChatMessage.fromString`, which turns URLs into
  clickable links. A player could therefore send a clickable link without any permission.
- Untrusted text is now sent as explicit BungeeCord components (`SpigotComponents`) on Spigot,
  and through the Adventure bridge on Paper.
- A control run with the previous jar (`ee298d9c…`) on Spigot 1.20.1 failed exactly the URL
  check. Its recipient got a component with click data. That run is in the persisted evidence as
  `control-old-jar-url`.

## Scope and limits

- **Mute and vanish:** only the hooks are tested, with the fixture standing in. Allium's own
  moderation and vanish come later.
- **Untested bypass:** `allium.msg.bypass.ignore` is covered by unit tests, not live.
- **No social spy:** it is a separate follow-up.
- **Not tested on other lanes:** Paper 1.21.8 and the older Paper lanes did not run this gate.
  The same text path passed the baseline gate on all five lanes.

## Rerun

```sh
./gradlew build
python3 tests/live/messaging/run.py --lane <spigot-1.20.1|paper-26.3> --run-name <name>
```

The runs use owned loopback ports 25692 (Spigot) and 25693 (Paper). They delete their copied
server files afterwards and exit nonzero on any failed check.
