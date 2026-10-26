# SF shop seed migration

`import_nexo_seeds.py` migrates exactly the twenty seed products in the live
ExcellentShop crops shop. Nineteen definitions are in
`configuration/items/crop_seeds.yml`; the existing `ce:tomato_seeds` stays in
`configuration/items/tomato.yml`. All twenty use native `ce:<crop>_seeds` IDs.

The source sprites are copied byte-for-byte from the models referenced by
Nexo. This matters for cotton, onion and soybean, whose sprites use
`*_seed_bag` filenames. The resourcepack folder is generated and ignored by
Git. Keep the Nexo source art or a backup to reproduce it.

```sh
python3 craftengine/allium_assets/import_nexo_seeds.py /path/to/fresh/plugins \
  --output /tmp/allium-seeds
python3 -m unittest discover -s craftengine/allium_assets -p 'test_*.py'
```

The input is read-only and output must be empty and outside it. The output has:

- Nineteen CE seed definitions, translations and seed sprites.
- The shop with all twenty seed products on `Provider: craftengine` and
  namespaced `Data.ID`, with prices, amounts, layout and other products intact.
- Allium crop files with only corresponding planting and seed-drop item
  scalars changed. Existing stage/Jade names, icons, models, growth and produce
  remain intact. Berry and weed planting items do not change.
- `seed-migration.json`, including native IDs, replacement counts, and SHA-256
  hashes of all inputs and outputs.

Use fresh live crop configs as input. Before deploying, compare the recorded
input hashes to the current live files. If another worker changed any input,
regenerate in a new empty output directory; never deploy an old crop backup
over newer Jade/stage edits. Re-running against an already migrated snapshot
does not produce replacement shop/crop configs. Nexo files are not removed.

Back up every destination and the existing ExcellentShop JAR. Merge only the
files in the manifest into the plugins folder. Coordinate reloads with any
other worker: CE configuration first, ExcellentShop JAR/config and Allium
configuration next. The Allium JAR can be deployed by the crop/Jade worker;
this migration requires no Java changes to Allium.

Regenerate the CE pack and rebuild ResourcePackManager's combined pack.
ResourcePackManager remains the sole sender. Keep CE `send-on-join: false`
and the `send_pack` workflow step absent, and Nexo pack provider `NONE`.

The shipped tomato example also uses CE seeds for planting and seed returns;
its produce references are unchanged.
