# Inventory snapshots and `/restore` (`modules.restore`)

## Snapshots

Snapshots hold the 41 inventory slots (storage, armor, offhand), the selected hotbar slot (the
main hand; restore schema v2, so older snapshots show it as not recorded), the ender chest, level
and progress, and where the player stood. Items use the mail item codec: data-version aware, with
persistent data (signed Allium tools, provider IDs) carried unchanged. `restore.snapshot-on`
chooses the triggers: `death`, `login`, `logout`, `world-change`, `gamemode-change` (taken before
the creative manager swaps inventories) and `autosave` (`autosave-minutes`). Storage is bounded by
`max-snapshots-per-player` (oldest removed first) and `retention-days`.

## `/restore [player]`

| Node | Default | Allows |
| --- | --- | --- |
| `allium.restore` | op | your own history, and restoring your own snapshots to yourself |
| `allium.restore.others` | op | other players' histories, and restoring to those players |
| `allium.restore.edit` | **false**, not in `allium.*` | editing panels and saving MANUAL snapshots. Panel items are copies, so this can create items |

The history is paged (45 per page, newest first). A snapshot opens a **restore panel** laid out
like `/invsee`: storage in the top four rows, then helmet, chestplate, leggings, boots and offhand
(empty ones show their slot icons) and a sword marking the main hand (the snapshot's selected
hotbar slot). With `allium.restore.edit` the panel is an editable working copy: items move between
the panel and your inventory (shift-click moves a whole stack: out of the panel into your
inventory, or from your inventory into the panel's first free storage cell, never into armor or
the buttons), and every restore button uses what the panel holds now. Nothing is written to
storage unless you press **Save as manual snapshot**.

Bottom row, left to right: restore everything to the snapshot's owner (yourself for your own
snapshots; inventory, armor, offhand, main hand, experience and ender chest); save as manual
snapshot; restore inventory only (with the main hand), armor and offhand only, or experience only
to the owner; teleport to where it was taken; the ender chest panel (restore to the owner or, with
`.edit` for someone else's snapshot, to yourself); the snapshot's info; back. Restoring someone
else's whole snapshot onto yourself is not offered in the main panel; editors take items out
instead.

A restore **replaces** the chosen parts. The target's open inventory is closed first, and a
target busy with mail, a kit, a paid repair or an `/invsee` editor is refused.

## Offline owners

A restore for an offline owner is queued (`allium_restore_pending`) and applied on their next join.
The applied restore's ID is written to the player's `allium:restore_receipts` and saved with the
restored items before the queue row is removed, so a crash in between never applies it twice. If
join-time recovery (mail, kits) holds the inventory, the restore retries for up to 20 seconds and
otherwise waits for the next join.

## Verification

- **Live (Spigot 1.20.1 and Paper 26.3, [staff gate](../tests/live/staff/RESULTS.md)):**
  snapshots on login/logout/gamemode change, the history and panel (contents, slot icons, the
  main-hand sword and the info paper's place), restoring everything to an online owner (main hand
  selected again), shift-click moves in both directions, an edited panel used by "inventory only" while armor is
  kept, the MANUAL save as the only storage write, a queued restore applied on join with queue row
  and receipt cleared, and a read-only panel without `.edit`.
- **Unit:** `SnapshotRepositoryTest` (paging, cap, age purge, queue).
- **Not verified live:** death, world-change and autosave triggers; the ender chest panel's buttons;
  teleport; a crash between applying a queued restore and clearing its row.
