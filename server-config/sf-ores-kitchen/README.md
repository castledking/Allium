# SF ore and pizza configuration

This directory contains the deployable CraftEngine ore pack and Nexo pizza
items/models. Merge `kitchen-additions.yml` into `Allium/harvest/kitchen.yml`;
it adds recipes and a single pie type. The packaged Allium config contains the
ore settings. Enable generation only after these CE definitions are loaded.

Rebuild the staged models and copy the local source sprites with:

```sh
python3 server-config/sf-ores-kitchen/build_models.py \
  --sf /path/to/SF/plugins \
  --croptopia /mnt/storage/devops/Croptopia \
  --farmers-delight /mnt/storage/devops/FarmersDelight \
  --metals 'work/silver and galena items' \
  --vanilla-assets /path/to/minecraft-client.jar
```

The script reads the existing SF pie models and writes only beneath this
directory. It copies textures without modifying them. The staged mushroom
carrier allocation uses CE's registry rather than hardcoded states.
Five vanilla textures are copied unchanged into the custom namespace because
the Bedrock converter needs their image files; vanilla block textures are not
overridden. After the first generation they can be reused without the JAR.

Load Nexo's items, then CraftEngine's pack, and reload Allium after replacing
its JAR. Build the combined server resource pack through the server's existing
ResourcePackManager workflow. Full behavior and validation notes are in
[`IMPLEMENTATION.md`](IMPLEMENTATION.md).

Source assets:

- Croptopia: local `common/src/main/resources/assets/croptopia`, cheese, salt
  and salt ore; upstream copyright remains with its authors.
- Farmer's Delight: local `src/main/resources/assets/farmersdelight`, tomato
  sauce; the MIT license is preserved in `FARMERS_DELIGHT_LICENSE.txt`.
- Silver and galena: user-provided recovered assets under Allium `work/`.
- Pie geometry and crust/side textures: the existing SF Nexo pack.
