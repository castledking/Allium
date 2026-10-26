# Cherry pie: filling before the dough top

The cherry recipe and Nexo item/model definitions already exist on SF.
`cherry_pie_filled` uses the model `sf:block/cherry_pie_filled`, whose top and
particle texture reference `sf:pies/cherry_filled_top`. That PNG was missing,
which produced the purple/black top after adding cherries and before adding dough.

Install this folder's `Nexo/pack/assets/sf/textures/pies/cherry_filled_top.png`
under `plugins/Nexo/pack/assets/sf/textures/pies/`. It is an unchanged copy of
the existing 16×16 `cranberry_filled_top.png`: an open dark-red berry filling
with the matching pastry rim. It has no dough lattice.

Run `nexo reload pack` to rebuild Nexo's pack. SF's ResourcePackManager watches
that pack, merges it and distributes the updated combined pack. Nexo's own
hosting and sending stay disabled. Allium's cherry recipe, finished pie artwork,
slice models and item IDs need no changes, and no plugin JAR reload is needed.

Rollback: remove the added PNG and rebuild the pack. The previous generated
pack and cherry model/item definitions were backed up in the task's timestamped
`/tmp/allium-cherry-pie-*/backup/` directory before installation.
