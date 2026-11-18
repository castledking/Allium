# Staff tooling live gate

**PASS on 2026-11-17: 158 live checks, 0 failures.** Spigot 1.20.1 (`spigot-r5`, 79 checks) and
Paper 26.3 (`paper-r5`, 79 checks), SQLite, both on Allium.jar
`be62473a769f17f6719ce07c385ce964f2aad65f1449dde561e0ce3d7afc04e0`. Every case below ran on
both lanes. The [manifest](evidence/staff-manifest.json) lists every check; runs are kept in
`/mnt/storage/.allium-validation/20261117/staff/runs/`.

- **Clients:** real protocol clients (minecraft-protocol 1.68.0 on 1.20.1, the owned raw client on
  26.3) send the commands, chat lines, completions, window clicks and sign clicks.
- **Fixture:** `StaffGate` grants permissions (at login when a case needs them at join), seeds and
  reads inventories and player data, places a repair sign's identity, arms faults and reads
  SQLite. It never drives a feature under test.

| Case | What ran |
| --- | --- |
| completions | `/eco` lists give/take/set/open (not the admin-only resolve), `/eco give` completes known players and amounts, `/pay` visible players, and `/eco` completes nothing without `allium.eco` |
| staff-chat | `>text` while writing to staff chat reaches a global reader without the `>`; the next line stays staff-only; `#text` from global stays staff-only |
| gamemode-permissions | `/gamemode creative` refused with only `allium.gamemode`; completion lists only permitted modes |
| creative-inventories | survival items leave on entering creative and come back on leaving; a creative item never reaches survival; adventure shares survival's inventory; the stash is in player data |
| creative-restriction | `/workbench` in creative is refused without `.container` and opens with it |
| creative-restart | a player leaves in creative; the server restarts with `force-gamemode=true`; they are restored to creative with both inventories intact |
| creative-audit | the console puts a player without `allium.gamemode.creative` into creative; staff are alerted |
| invsee | empty armor/offhand slots show icons with the `gui_slot` model data, the sword names the held hotbar slot, a helmet placed on the helmet icon equips it, no icon reaches the viewer; `/invsee` on a dead player says so; an offline session announces itself and reports one saved slot change, which reaches the player data |
| mail-numbers | text mail reads `[1] [2] [3]`; deleting 2 frees it for the next mail; gifts are numbered apart (1, 2); claiming 1 frees it for the next gift |
| whois-alts | joins record the IP; a rejoining alt triggers a staff alert naming the older account (no permissions plugin, so the "cannot add group" path, logged once per server run); `/seen` shows alts and IP to staff only; `/altexempt` add/check/remove and a quiet exempt rejoin; `/whois` shows every extra line and the action links, with LiteBans running `/history PlayerBot`; the basic view without the node |
| restore | snapshots exist; history and panel (contents, icons); restore everything to an online owner; an edited panel used by "inventory only" (armor kept); a MANUAL save is the only storage write; a queued restore for an offline owner is applied on join and its row and receipt cleared; a read-only panel hands out nothing |
| paid-repair | a `$1.25` repair sign repairs the held sword and captures exactly 125 with nothing held, then drops the receipt; an undamaged item is not charged; with `$1.00` nothing is repaired, held or charged |
| repair-halt-after-reserve | the JVM halts (exit 77) after the hold; at restart the hold is released, the balance is whole and the sword still damaged |
| repair-halt-after-save | the JVM halts after the repair and receipt are saved; at restart the hold is captured once and the repaired sword kept; the receipt is cleared on join |

Server logs: no exceptions or SEVERE lines on either lane.

**Regressions on the same jar:** the S24 inspection gate passed 98/98 on each lane
(`r5-spigot`, `r5-paper`), and the economy sign gate 384/384 on each lane
(`signs-repair-spigot-r5`, `signs-repair-paper-r5`), including a priced repair sign created through
the real sign editor.

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
