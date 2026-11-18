# Staff tooling live gate

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
