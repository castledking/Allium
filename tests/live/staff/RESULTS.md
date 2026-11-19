# Staff tooling live gate

## November 19 follow-up (r20): /playtime and more placeholders

**PASS on targeted runs:** the new `playtime-placeholders` case with PlaceholderAPI 2.12.3 on
Spigot 1.20.1 (`r23-spigot-playtime`) and Paper 26.3 (`r23-paper-playtime`), 8 checks each,
SQLite, Allium.jar `15f8761237576cc8676115988979a3bbd4d64dbe6f82c8ed76f2e3026b58d8fb`
([spigot](evidence/r20-playtime-spigot.json), [paper](evidence/r20-playtime-paper.json)). No
exceptions or SEVERE lines. `./gradlew check`: 1103 unit tests, 0 failures. The other staff cases
did not run on this jar.

| Case | What ran |
| --- | --- |
| playtime-placeholders | `/playtime` with the first-join date; `/playtime StaffBot` refused without `allium.playtime.others`; `/playtime PlayerBot` online and, after PlayerBot leaves, offline from the stats file; `%allium_gamemode%` reads `survival`, then `restricted-creative` in creative and `creative` with `allium.gamemode.creative.bypass`; `playtime`, `playtime_short`, `firstjoindate` and `fly_time_short`; `give PlayerBot npick` gives a netherite pickaxe |

## November 19 follow-up (r19): aliases, chat, travel, creative manager and two server fixes

**PASS: the full staff gate, 546 live checks, 0 failures.** Spigot 1.20.1 (`r22-spigot-full`, 269
checks) and Paper 26.3 (`r22-paper-full`, 277 checks), 39 cases each with PlaceholderAPI 2.12.3,
SQLite, Allium.jar `a5031abb510e66e018bab06167cd8d201a26bf776c8c2875ba12db4e23ec51ab`
([spigot](evidence/r19-spigot-full.json), [paper](evidence/r19-paper-full.json)). No exceptions or
SEVERE lines. `./gradlew check`: 1099 unit tests, 0 failures.

New cases on both lanes:

| Case | What ran |
| --- | --- |
| chat-aliases | `/a`, `/ac`, `/amsg` reach staff chat, echoed to a sender who does not read it, and stay out of global; `/shout` and `/g` with a message need `allium.chat.shout`; `/me`; `/coords`, `/getpos`, `/whereami` (others need `.others`); `/compass` facing north and east; `/who`, `/balancetop`, `/wspeed`, `/walkspeed`, `/wb`, `/wbench` and the `*mode` gamemode aliases |
| locale-sounds | with `settings.locale: es_US` (not bundled), a translated line without a sound plays the English sound, and the generated `es_US.yml` is a copy of `en_US.yml` with its sounds (the previous jar plays nothing) |
| chat-permissions | a link needs `allium.chat.url`; a mention is coloured and the mentioned player hears it; `/ignore` hides chat and `allium.chat.ignoreexempt` both shows it and refuses the ignore; the local chat spy and its exemption; `?question` from local chat in the question format, refused without the node; the four `allium.chat.receive.*` nodes |
| hide-own-commands | a legacy `unsafe-commands-list` with `msg` no longer stops `/msg` completing (the previous jar does not answer the completion) |
| spy-tools | `/commandspy` is off until switched on; `/spy PlayerBot` rows are clickable, local chat spy is on for everyone by default, a click spies on one player's commands; `/spy` shows your own panel; `/spectate` runs the configured `tp` and `gmsp` |
| travel-extras | `/warpinfo`, `/warp <warp> <player>`, `/renamehome` (own and staff), `/core sethomes` (show, set, relative, clear), the home compass, `allium.worlds.<world>` for a world created at runtime, `/tpo` and `/tpohere` past `/tptoggle` |
| mass-teleports | `/spawnmob` (amount, per-mob node, the spawn limit), `/s @e[type=pig]` reports 13 and asks to confirm, moves nothing until confirmed, then moves them; `tphere-max` refuses with the count; `/tpall` past `/tptoggle` |
| economy-messages | `/pay` to yourself, a taxed receipt without the operation id or tax account, an insufficient-funds refusal worded from the language file |
| warmup-actionbar | the warmup counts down 3s, 2s, 1s in the action bar with no chat line; the chat replay after `/delmsg` plays no sounds |
| creative-protections | flint and steel on TNT refused (`blacklist.items`); a creative player's burning arrow does not light TNT (`protect-tnt`), and does with the bypass; a torch placed in creative is marked in its chunk and drops nothing when broken in survival, while an unmarked torch drops; pick block refused without `.pickblock` and allowed with it (Paper only: Spigot 1.20.1 picks on the client); `blacklist.commands`; entering creative puts the pink creative armour on and clears effects, leaving takes it off |

Updated: `teleport-types` reads the warmup from the action bar; `creative-inventories` expects the
creative helmet; the clients now log sounds and action bars, and the fixture can set and read
blocks, count dropped items, read block marks, fire a burning arrow and create a flat world.

Not covered live: creative item lore, sanitizing and the enchantment/potion refusals (the clients
cannot send creative inventory packets), explosions, pistons, falling blocks, growth and mob builds
on marked blocks, filled containers, bed clicks, `/renamehome` refusals, entity selection limits
for `/tpe` and `/tppet` (unit only). The bundled netherite aliases (`npick`, `nhelm`...) and their
merge under an older `itemdb.yml` came after this jar and are unit tested.

## November 19 follow-up (r18): when timed flight counts

**PASS on targeted runs:** the updated `timed-flight` case with PlaceholderAPI 2.12.3 on Spigot
1.20.1 (`tfly-spigot-3`) and Paper 26.3 (`tfly-paper-3`), 12 checks each, SQLite, Allium.jar
`1c5179182ce7ff555e0c1ec1d9f6b0c4c3e26bf800ef579991915143cb60a067`
([spigot](evidence/r18-tfly-spigot.json), [paper](evidence/r18-tfly-paper.json)). No exceptions or
SEVERE lines. `./gradlew check`: 1068 unit tests, 0 failures. The other staff cases did not run on
this jar.

| Case | What ran |
| --- | --- |
| timed-flight | By default (`timed-flight.count-only-while-flying: false`) `/fly` says only the time left, `%allium_fly%` reads `temporary` on the ground, and 8 s standing still bring the 5 s warning, the run-out message and the end of flight. Then `count-only-while-flying: true` and `/allium reload`: `/fly` adds "It only runs while you fly.", 2.5 s on the ground spend nothing, `%allium_fly%` reads `temporary-paused` and `%allium_fly_time%` `8s` until the fixture lifts PlayerBot (`temporary`), and the time runs out in the air; then the r17 storage checks (about three seconds spent in the air, an offline `take 5`, `set 1h30m`). The config is restored and reloaded afterwards |

Not covered live: the upgrade of an existing `player.tfly-on` text written by the r17 jar (unit
only).

## November 19 follow-up (r17): timed flight

**PASS on targeted runs:** the new `timed-flight` case with PlaceholderAPI 2.12.3 (`fde03259…`)
installed, on Spigot 1.20.1 (`tfly-spigot-2`) and Paper 26.3 (`tfly-paper-2`), 10 checks each,
SQLite, Allium.jar `a14ca00b8e03226e9cab844b9e67f0e04782a6ba6f9be7e6f1dc3463eacdda9b`
([spigot](evidence/r17-tfly-spigot.json), [paper](evidence/r17-tfly-paper.json)). The same case
without PlaceholderAPI passed 8/8 on each lane (`tfly-*-1`). No exceptions or SEVERE lines.
`./gradlew check`: 1066 unit tests, 0 failures. The other staff cases did not run on this jar,
except `placeholders` on Spigot after its parse helper moved (`papi-spigot-2`).

| Case | What ran |
| --- | --- |
| timed-flight | PlayerBot has no `allium.fly`. After `/fly PlayerBot reset`, `/fly` says there is no time; the console's `add 8` notifies PlayerBot; `/fly` allows flight without lifting it, 2.5 s on the ground spend nothing and `/fly` turns it off; lifted into flight by the fixture (a client cannot start flying here), the 5 s warning and the run-out message arrive and flight ends; with 20 s, `%allium_fly%` reads `temporary-paused` and `%allium_fly_time%` `20s` on the ground and `temporary` in the air; about three seconds of flight spend 2-5 s; with PlayerBot offline, `take 5` changes the stored bank and the rejoined player sees it; `set 1h30m` shows `1h 30m` |

The fixture's `flight <name> fly` only calls `setFlying(true)` on a player already allowed to fly,
as a double jump would. Not covered live: the 30-second save while flying (the quit save is
covered), the shutdown save, storage failures and the MySQL row lock (unit only), and a gamemode
or world change during timed flight (not verified).

## November 19 follow-up (r16): PlaceholderAPI placeholders

**PASS on targeted runs:** the new `placeholders` case (`--placeholderapi` with the real
PlaceholderAPI 2.12.3, `fde03259…`) on Spigot 1.20.1 (`papi-spigot-1`) and Paper 26.3
(`papi-paper-1`), 9 checks each, SQLite, Allium.jar
`def330331888b49ead9d75d0a6542cada45f084d0d9401fd913ce3b50068332f`. No exceptions or SEVERE lines.
`./gradlew check`: 1059 unit tests, 0 failures. The other staff cases did not run on this jar.

| Case | What ran |
| --- | --- |
| placeholders | `papi parse` from the console: the expansion registers; `nickname`, `world`, `god`, `vanished`, `ping`, `homes_max`, `world_time` and `world_time_24` answer in legacy formats; `%allium_nonsense%` stays as written; after `/eco set PlayerBot 1234.5`, `balance` reads `1234.50` within one refresh and `balance_commas`, `balance_formatted`, `balance_fixed`, `baltop_player_1`, `baltop_balance_commas_1` and `baltop_rank_formatted` follow; a new mail raises `mail_unread` |

Console parsing runs on the server thread, so the per-second snapshot that asynchronous requests
read is unit tested only.

## November 19 follow-up (r15): timed broadcasts

**PASS on targeted runs:** the new `broadcasts` case on Spigot 1.20.1 (`bc-spigot-1`) and Paper 26.3
(`bc-paper-1`), 7 checks each, SQLite, Allium.jar
`57f9758af1747fc8b9945e9489b072456c074f9b4b66fa0ce37d089468b211af`. No exceptions or SEVERE lines.
`./gradlew check`: 1052 unit tests, 0 failures. The other staff cases did not run on this jar.

| Case | What ran |
| --- | --- |
| broadcasts | with `broadcasts.enabled` and a 10 s interval set and `/allium reload`, the first broadcast (`rules`) arrives with its click tag; `/broadcasts` shows the settings and `rules, homes, vote`; `/broadcasts send vote` fills `{player}` per recipient; an unknown name is refused; `/broadcast` reaches players as `[Broadcast] ...`; with `allium.broadcasts.receive` negated a timed broadcast skips that player; after restoring the config and reloading, none arrive for 12 s |

## November 19 follow-up (r14): /top, /bottom and one mail number sequence

**PASS on targeted runs:** the new `top-bottom` case and the updated `mail-numbers` case on Spigot
1.20.1 (`topmail-spigot-1`) and Paper 26.3 (`topmail-paper-1`), 10 checks each, SQLite, Allium.jar
`9c4eb59ee8b218058fe512597f572bab12458c069a02f957f9da3a8aad0a78c1`. No exceptions or SEVERE lines.
`./gradlew check`: 1046 unit tests, 0 failures. The other staff cases did not run on this jar.

| Case | What ran |
| --- | --- |
| top-bottom | on the flat floor `/bottom` finds nothing below; `/top` stands on a stone block placed 6 above and reports `20.5, -54.0, 20.5`; `/bottom` lands back on the floor; `/back` returns to the block |
| mail-numbers (updated) | texts 1-3, a deleted text's number reused, then gifts numbered 4 and 5 after them; `/mail` lists 1 to 5 in order; `/mail claim 1` (a text) and `/mail delete 4` (a gift) are refused; a claimed gift's number 4 is reused |

These lanes start from an empty database, so the version 4 renumbering of existing mail is unit
tested only (SQLite; MySQL when `ALLIUM_TEST_MYSQL_URL` is set, which it was not).

## November 18 full re-run (r13)

**PASS: the whole gate, 25 cases on each lane.** Spigot 1.20.1 (`r13b-full-spigot`, 182 checks) and
Paper 26.3 (`r13b-full-paper`, 189 checks), SQLite, Allium.jar
`69d1a916957deb739364d97c06b50ab45a12e0e75e89cb85550bc2e9b60b97d8` (commit `661b376` plus the fix below). No exceptions or SEVERE lines. `./gradlew check`:
1041 unit tests, 0 failures. Manifests: `evidence/r13-full-spigot.json`, `evidence/r13-full-paper.json`.

The first full run on `661b376` (`aaf722c8006ed72f5aba84268bb90fc620f2347893913378295ec6e6a4c80ea0`; `r13-full-spigot` 176 checks and `r13-full-paper` 183
checks, 2 failures each) found a regression from the r10 capitalisation change: the creative
audit alert's text holds `allium.gamemode.{to}`, so title-casing `{to}` named a node that does
not exist (`allium.gamemode.Creative`). The node is now filled in lowercase before `{to}` becomes
`Creative`, which also covers customized text, and `creative-audit` checks the whole line
(`(Survival -> Creative)` and `allium.gamemode.creative`). Its second failure was a knock-on:
the audit case stopped before restoring survival, so `usage-items-homes` saw Creative in
`/whois`. `creative-audit` now restores survival in a `finally`, and `usage-items-homes` sets
survival itself.

## November 18 follow-up (r12): /motd, /rules and the join MOTD

**PASS on targeted runs:** the new `motd-rules` case with `home-admin` (`motd-spigot-2`,
`motd-paper-2`, 17 checks each) and `usage-items-homes` (`usage-spigot-motd-1`,
`usage-paper-motd-1`, 17 checks each) on Spigot 1.20.1 and Paper 26.3, SQLite, Allium.jar
`aaf722c8006ed72f5aba84268bb90fc620f2347893913378295ec6e6a4c80ea0`. No exceptions or SEVERE lines. `./gradlew check`: 1041 unit tests, 0 failures. The other
staff cases did not run on this jar. `home-admin` and `motd-rules` reconnect PlayerBot, which
drops the fixture's non-sticky grants, so they now run last.

| Case | What ran |
| --- | --- |
| motd-rules | on rejoin the MOTD arrives with `{displayname}` filled and its `/rules` click tag, and not with `allium.motd` denied; `/motd`; `/rules` with click tags, `/rules grief` (an alias), a missing chapter, `/rules g` completion; a `rules.txt` with chapters only lists them as clickable links and `/rules build` works without a reload |
| usage-items-homes (updated) | the bundled `customtext.txt` index now links `/customtext vote` and `/rules` |

## November 18 follow-up (r11): offline home administration

**PASS on targeted runs:** `usage-items-homes` and the new `home-admin` case ran together with
`--only` on Spigot 1.20.1 (`homes-both-spigot-1`) and Paper 26.3 (`homes-both-paper-1`), 26 checks
each, SQLite, Allium.jar `0dd3b5a1d0b660236a6a76800128cd4835c059e51f0aeeb271f028c6bc7c95a2`. No exceptions or SEVERE lines. `./gradlew check`: 1036 unit tests,
0 failures. The other staff cases did not run on this jar.

| Case | What ran |
| --- | --- |
| home-admin | `/home PlayerBot:adm` completes `PlayerBot:admin1`/`admin2` and `/home PlayerB` completes `PlayerBot:`; `/delhome PlayerBot:` completes nothing without `allium.delhome.others`; `/home PlayerBot:` lists; `/delhome PlayerBot:admin1` refused, then deleted, and the online owner's `/homes` and the completion follow; `/home PlayerBot:bed` refused, then lands at the bed; with PlayerBot offline, the saved bed is reached, `/delhome PlayerBot:admin2` deletes and `/home PlayerBot:admin2` no longer finds it, which still holds after PlayerBot rejoins |

## November 18 follow-up (r10): usage cards, homes, item text, /xp, /customtext

**PASS on targeted runs only, not the full gate:** the new `usage-items-homes` case ran with
`--only` on Spigot 1.20.1 (`usage-spigot-2`, 16 checks) and Paper 26.3 (`usage-paper-1`, 16 checks),
and the cases whose expected text changed (`gamemode-permissions`, `scale`, `tp-position`,
`whois-alts`, `paid-repair`) ran on Spigot (`changed-spigot-1`, 33 checks) and Paper
(`changed-paper-1`, 40 checks). SQLite, Allium.jar
`7f8fcd97194ca6fbc8531d75a1ee18b656d84b0e952aa9133c8f3a18c49820ea`. After `/lore copy` and
`/lore paste` were added, `usage-items-homes` ran again on jar
`bed0c33c755a32531a8fc281067d275536b0dec714ca5fd75f7e930b5d673f19`: 17/17 on Spigot
(`usage-spigot-copy-1`) and Paper (`usage-paper-copy-1`); the changed cases were not re-run on it. No exceptions or SEVERE lines
in any server log. `./gradlew check`: 1032 unit tests (680 plugin, 348 core, 4 API), 0 failures.
The other 17 staff cases did not run on this jar.

| Case | What ran |
| --- | --- |
| usage-items-homes | `/nick` with no arguments shows the command card: `Usage(s):` and clickable, suggest-command lines with gold arguments, the `<player>` forms hidden without `allium.nick.others`; `/core hide group` lists only the group forms; `Gamemode set to Survival.` and `Repaired Diamond Sword.`; `/rename &aCool Sword` and `/lore add`/`insert`/`remove`/`rename clear` (checked on the item), `/lore copy` and `/lore paste` onto a second item; `/xp reset`, `give 5L` (level 5), `take 10`, own `/xp`, refusal without `allium.xp.give`; `/whois` one field per line; `/homes` strikes `bed` through until a bed is set, `/bed` without one, `sethome bed` refused, `/home bed` lands at the respawn point; `/homes PlayerBot` and `/home PlayerBot:base` for staff, refused for players; `/customtext` click tags become click events and a JSON line arrives with its copy-to-clipboard event |
| gamemode-permissions, paid-repair (updated) | `permission to use Creative mode`, `Repaired Diamond Sword for $1.25` |

Not covered: the help card shown by `/help <command>` (unit only), deleting another player's home
(not supported), and the live gates outside this one whose usage strings changed
(`moderation` `/mute`, `discipline` `/notes`, `parity` `/nick`): their expected text was
updated but they were not re-run.

## November 18 follow-up (r9)

**PASS: 311 live checks, 0 failures, 22 cases on each lane.** Spigot 1.20.1 (`spigot-r9-full`,
152 checks) and Paper 26.3 (`paper-r9-full-b`, 159 checks; Spigot has no scale attribute, so its
`scale` case only checks the "unavailable" reply), SQLite, both on Allium.jar
`18e325378e11546ae9fc5eed65488dd0717faf49251ec9fcc2dde3962c1cdf5e`. `./gradlew check shadowJar`:
982 unit tests, 0 failures. No exceptions or SEVERE lines in either server log.

New cases on both lanes:

| Case | What ran |
| --- | --- |
| items-batch | `/i stick,gshovel 1` (a legacy alias inside a batch), `/i stick:1,diamond:2`, `/i stick:5,dirt 2`, `/give <player> diamond:3,stick`, `/i 1:1` still granite; completion after a comma |
| potions | `potion;poison`, strong splash swiftness, long lingering swiftness and a strong tipped arrow are the vanilla potions (base type, no custom effect); a custom duration keeps its effect and is named after the vanilla potion (Paper: the potion name field; Spigot: a translated item name); haste keeps the plain name |
| variant-completion, clear, scale | `/i` variant completion; `/clear` others, `*` and `**` with confirmation and item filters; `/scale` for self, others and selected mobs |
| tp-position | `/tp x y z` needs `allium.tp.position`; block centers, yaw/pitch, `~` offsets; `/tppos` with an explicit world, a missing world reported, usage errors; completions |
| teleport-types | moving cancels a warmup without a node and not with `allium.teleport.timer.move.tppos`; the `tppos` cooldown and its bypass node; `allium.teleport.timer.bypass.tppos` teleports at once; the `requests` group skips an accepted `/tpa`'s warmup |
| invsee, restore (updated) | the held hotbar cell is empty; placing on it is refused; a shift-click merges into the target's stack and, with storage full, skips the held cell |
| whois-alts (updated) | `/alts` is `/seen` |

The first Paper r9 run failed `paid-repair`: the new `scale` case left PlayerBot at scale 16 (scale
is saved with the player), so its eyes were out of reach of the repair sign. The case now scales
PlayerBot back to 1; it is a harness fix, not a plugin change. That first run is kept as
`paper-r9-full`. An earlier follow-up pass on jar `74dfe544…` (127 checks per lane,
`*-resume-final`) found that a missing explicit `/tppos` world fell back to the current world;
that was fixed before r9.

Regressions on r9: S24 inspection 98/98 on each lane; S25 utilities 143/143 on Spigot, and on Paper
142/143 twice, both times only the log check: Paper's own `/skull Notch` profile lookup got
HTTP 429 from Mojang's session server. The hide gate passed 29/29 on each lane on r8
(`hide-*-r8`); r8 to r9 changed no hide code.

Runs: `/mnt/storage/.allium-validation/20261118/staff/runs/`.

**PASS on 2026-11-18: 184 live checks, 0 failures.** Spigot 1.20.1 (`spigot-r7-full`, 92 checks) and
Paper 26.3 (`paper-r7-full`, 92 checks), SQLite, both on Allium.jar
`23823e09a089649d82ea84993b941d85d07291f7fc78103cf6e8a4a0e2174002`. Every case below ran on
both lanes. The [manifest](evidence/staff-manifest.json) lists every check; runs are kept in
`/mnt/storage/.allium-validation/20261117/staff/runs/`.

- **Clients:** real protocol clients (minecraft-protocol 1.68.0 on 1.20.1, the owned raw client on
  26.3) send the commands, chat lines, completions, window clicks and sign clicks.
- **Fixture:** `StaffGate` grants permissions (at login when a case needs them at join), seeds and
  reads inventories and player data, places a repair sign's identity, arms faults and reads
  SQLite. It never drives a feature under test.

| Case | What ran |
| --- | --- |
| help | with command hiding and its permission resolver on, `/help` lists commands although Allium registers `allium.command.selector.all` (the same case fails on the previous jar with the reported `StringIndexOutOfBoundsException`), with the rewritten descriptions |
| completions | `/eco` lists give/take/set/open (not the admin-only resolve), `/eco give` completes known players and amounts, `/pay` visible players, and `/eco` completes nothing without `allium.eco` |
| staff-chat | `>text` while writing to staff chat reaches a global reader without the `>`; the next line stays staff-only; `#text` from global stays staff-only |
| gamemode-permissions | `/gamemode creative` refused with only `allium.gamemode`; completion lists only permitted modes |
| creative-inventories | survival items leave on entering creative and come back on leaving; a creative item never reaches survival; adventure shares survival's inventory; the stash is in player data |
| creative-restriction | `/workbench` in creative is refused without `.container` and opens with it |
| creative-restart | a player leaves in creative; the server restarts with `force-gamemode=true`; they are restored to creative with both inventories intact |
| creative-audit | the console puts a player without `allium.gamemode.creative` into creative; staff are alerted |
| invsee | empty armor/offhand slots show icons with the `gui_slot` model data; the held hotbar item sits in the main-hand cell and its hotbar cell is a marker; taking it empties the held slot and placing on the empty (sword) cell fills it; a helmet placed on the helmet icon equips it, no icon reaches the viewer; `/invsee` on a dead player says so; an offline session reads the main hand from the player file's `SelectedItemSlot`, announces itself, and reports one saved change (the main hand taken), which reaches the player data |
| mail-numbers | text mail reads `[1] [2] [3]`; deleting 2 frees it for the next mail; gifts are numbered apart (1, 2); claiming 1 frees it for the next gift |
| whois-alts | joins record the IP; a rejoining alt triggers a staff alert naming the older account (no permissions plugin, so the "cannot add group" path, logged once per server run); `/seen` shows alts and IP to staff only; `/altexempt` add/check/remove and a quiet exempt rejoin; `/whois` shows every extra line and the action links, with LiteBans running `/history PlayerBot`; the basic view without the node |
| restore | snapshots exist; history and panel (contents, icons, the snapshot's held item in the main-hand cell with a marker in its hotbar cell, the info paper in the bottom row); the main hand shifted out and placed back; restore everything to an online owner, which also selects the recorded main hand; shift-click moving a whole stack out of the panel and back into the first free storage cell (never armor or controls); an edited panel used by "inventory only" (armor kept); a MANUAL save is the only storage write; a queued restore for an offline owner is applied on join and its row and receipt cleared; a read-only panel hands out nothing, by click or shift-click |
| paid-repair | a `$1.25` repair sign repairs the held sword and captures exactly 125 with nothing held, then drops the receipt; an undamaged item is not charged; with `$1.00` nothing is repaired, held or charged |
| repair-halt-after-reserve | the JVM halts (exit 77) after the hold; at restart the hold is released, the balance is whole and the sword still damaged |
| repair-halt-after-save | the JVM halts after the repair and receipt are saved; at restart the hold is captured once and the repaired sword kept; the receipt is cleared on join |

Server logs: no exceptions or SEVERE lines on either lane.

**Regressions:** on this jar the S24 inspection gate passed 98/98 on each lane (`r7-spigot-b`,
`r7-paper`; its layout checks now expect the held slot in the main-hand cell). The economy sign
gate passed 384/384 on each lane on jar `be62473a…` (r5), including a priced repair sign created
through the real sign editor; later jars do not change signs or the economy and it was not rerun.

## What the gate found and fixed

- **Alt detection never recorded an IP without Vault.** `AltService` named a Vault type, so Bukkit
  could not register its listener on servers without Vault. Vault use now sits in `VaultGroups`,
  loaded only when Vault is present.
- **A queued restore could be skipped at join.** Mail's join recovery briefly holds the inventory;
  the restore now retries for up to 20 seconds instead of waiting for the next join.
- **Every player behind a proxy could be flagged as an alt.** The S24 rerun showed every join
  sharing 127.0.0.1. Loopback addresses are now ignored by default
  (`alts.ignore-local-addresses`), and the missing-group-backend warning is logged once.
- **Re-editing an Allium sign on Paper dropped its identity** (found by the economy sign gate's
  first Paper run of its repair branch). Paper keeps a line's style when a face is re-edited, so the
  header arrived as `§1[repair]`. Sign lines are now read without color codes.
- **`/help` (and the command list sent on join) crashed with command hiding on.** The hide
  permission resolver read a module name between `allium.` and `.command.` and failed on
  `allium.command.selector.all`, which has none.
- **Creative players could not use Allium's action signs.** Signs are no longer a creative
  "interact" block, as in legacy Allium; the economy sign gate runs its bot in creative and now
  disables the creative module, which is gated here.

## Not covered here

- Creative restrictions other than containers (place, break, interact, drop, pickup, creative menu,
  spawn eggs, blacklist, spectator teleport): the clients cannot send those packets.
- Alt group assignment through LuckPerms or Vault (neither is installed on the lanes).
- `/restore`'s ender chest panel, teleport, and the death/world-change/autosave triggers.
- The slot icons' look in a real client (only the model data the server sends is checked).

## Rerun

```sh
./gradlew build
python3 tests/live/staff/run.py --suite spigot --allium-jar <jar> --sha256 <sha256> --run-name <name>
python3 tests/live/staff/run.py --suite paper --allium-jar <jar> --sha256 <sha256> --run-name <name>
```

Owned ports 25860 (Spigot) and 25861 (Paper); run roots under
`/mnt/storage/.allium-validation/YYYYMMDD/staff/runs`.
