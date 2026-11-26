# Menus

Install `Menus.jar` in `plugins/Allium/addons/` with Allium API 1.9 or newer. Configuration lives in `plugins/Allium/Menus/`.

- `/settings` opens the hub. **Toggles** contains the TPA, message and payment preferences. **Request Teleport** lists visible online players and lets players pin favorites using **Add Player to List**. Requests still go through `/tpa` and `/tpahere`. `/toggles` is available in chat.
- **Allium** is the native Escape entry when Menus supplies the only entry. It opens public shortcuts directly and links to the personalized Settings hub through **Player Settings**. Minecraft groups entries when several packs register pause-screen buttons. Menus writes the pack to the primary world's `datapacks/allium-menus.zip`. Restart to load the pack and reconnect after installing it or changing Escape entries.
- `/home`, `/homes`, `/warp` and `/warps` with no arguments open inventory menus. The hub links to the same destinations through dialogs on compatible clients. The home list uses Allium's effective limit, including `allium.sethome.<amount>` and configured overrides. **Show More** uses `locked-homes` or `locked-warps` from `config.yml`. Staff with `allium.setwarp` can create warps and see restricted entries.
- `/rename`, `/itemname`, `/lore` and `/nick` with no text open editors. Lore supports up to 32 fields. Inventory fallback offers a clickable text prompt through a private command. `text-editors: false` restores the command-only editors. Core still handles validation and color permissions.
- `/punish [player]` opens staff moderation with individual command permissions. `/ptgui` opens a powertool list and a held-tool editor. These views are also accessible through **Staff Tools**.
- `/sell` with no arguments opens an inventory menu backed by Allium Worth. LiteFish catches are priced from the catch's weight and the plugin's configured price. `/shop` opens the bundled SurvivalFun catalog with its categories, pages, and per-item buy and sell prices in the product tooltips. Product sell prices can be fixed or floating in `shop.yml`. Products without a `sell-price` use Worth. A category's optional `display-name` styles its icon independently of its title. Provider items still require the corresponding addon or plugin. Back at the root of a command-opened inventory closes it.
- Set `auction-command` to the installed ExcellentShop auction command. With ExcellentShop enabled, **Auction House** opens its auction view. Leave the value empty to hide the entry. The patched ExcellentShop provider accepts signed Crops items in product and icon material references such as `Material: allium:banana` and `Material: allium:baked_pumpkin_pie`.
- `/joinmessages`, `/jm`, `/quitmessages` and `/qm` open the SurvivalFun join and leave message packs. Each pack has one permission and changes both messages. Click a selected pack again to restore the server defaults. Edit the packs in `message-packs.yml`.

## Configuring menus

`/menu <id>` opens a panel with a `command` trigger. `/menus validate` checks configuration and `/menus reload` applies validated changes. Use `first-join` or `join` in a dialog's `open-on` list to open it after login. First-join panels take priority. The first accessible matching file in filename order opens.

Files in `dialogs/` define namespaced menu IDs, permissions and actions. A matching file in `guis/` can set inventory rows, slots and icons. A panel can instead declare a dynamic `source`, such as `homes`, `warps`, `teleport`, `staff`, `powertools`, `shop` or `sell`. Use `action: 'view:<source>'` to link to one of those views from a statically configured panel.

Use `when` to require all listed conditions and `when-any` to require at least one. Conditions accept `permission:<node>`, `world:<name>`, `data:<key>` and `data:<key>=<value>`. Prefix a condition with `!` to invert it. `set-data:<key>=<value>` and `clear-data:<key>` update persistent player data. Use `%data:<key>%` in button text or icon references. Use `action: back` to follow the current menu's navigation history.

Players with `allium.setwarp` can save the held item's appearance for a warp with `/menus warp-icon <warp>` or **Set Icon from Hand** in its detail view. Append an item ID to use it as the icon or `clear` to restore the default. Shared icons are stored in `warp-icons.yml`, which also accepts `when` and `when-any` conditions.

Menus updates unmodified starter definitions automatically. When upgrading a customized hub, add links to `allium:toggles` and `allium:teleport` and use `source: homes` or `source: warps` in the corresponding travel definitions. Update the matching inventory layout if it references removed button IDs.

Paper and Folia use native dialogs for compatible clients. Spigot, older clients and `force-inventory: true` use inventory menus.
