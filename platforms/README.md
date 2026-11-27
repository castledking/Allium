# Allium native platforms

The Fabric and NeoForge adapters implement `/allium version`, `/ping`, and `/coords` on dedicated servers. Players can check their own ping and position. Checking another player requires vanilla gamemaster permission. The Paper artifact includes the existing Bukkit plugin and uses its existing server matrix.

## Homes

Fabric, NeoForge, and Bukkit 1.12.2 support `/sethome [name]`, `/home [name]`, `/delhome [name]`, and `/homes`. Omitting the name uses `home`. Each player starts with one slot. Fabric and NeoForge use vanilla gamemaster permission for up to 1,000 homes. Bukkit 1.12.2 uses `allium.sethome.<amount>` and `allium.sethome.unlimited` for higher limits.

Homes save the world or dimension, exact position, and facing. Delete an existing name before reusing it. Teleportation requires the saved destination's world to be loaded. The native mods store homes under `allium/homes/` in the world save. Bukkit 1.12.2 stores them in `plugins/Allium/homes/`. This first port covers players' own saved homes. The Paper plugin's travel configuration and database use their existing paths and commands.

## Text pages and broadcasts on Bukkit 1.12.2–1.19.4

Use `/info`, `/rules`, `/motd`, or `/customtext` with `[section] [page]` to read the text files in `plugins/Allium/`. Files support `#section,alias` headers, `##` comments, and player-specific files such as `info_steve.txt`. `/inform <player> [section] [page]` displays the recipient's info page. The compatibility runtime fills `{player}`, `{displayname}`, `{online}`, `{max}`, and `{server}`. Click and hover tags render as plain text. Spigot and Paper support raw JSON components and clickable page navigation.

The join MOTD uses `motd.show-on-join` and `motd.join-delay-ticks`. Timed broadcasts read sections from `broadcasts.txt`, use `broadcasts.*` settings from `config.yml`, and respect `allium.broadcasts.receive`. Authorized staff can list them with `/broadcasts`, send one by name with `/broadcasts send <name>`, or advance the rotation with `/broadcasts next`. Text edits are read on the next use. Use `/allium reload` to apply scheduling changes.

## Recipe lookup on Bukkit 1.12.2–1.19.4

`/recipe [item] [number]` and its aliases show the server's registered recipes in chat. Players can omit the item to look up what they are holding. Shaped recipes preserve the crafting grid rows, and recipes with alternative ingredients list the available choices when the server exposes them.

`/editsign` (`/sign`) can set or clear individual lines on the sign you are looking at, copy its text, or paste text copied during your session. The command checks spawn protection and honors cancelled Bukkit protection and sign-change events.

`/firework` edits a held rocket. It supports `power <0-4>`, `clear`, and effect options such as `color:red,blue fade:white shape:star effect:trail`. Players with `allium.firework.fire` can launch rockets with `fire [amount|aim]`.

`/potion` lists effects the player can add, accepts options such as `effect:speed power:2 duration:60` and `color:#3366ff`, and can `clear` custom effects. Players with `allium.potion.apply` can apply the held item's custom effects to themselves.

`/unlimited <list|item|clear> [player]` manages blocks replenished after successful Survival placement. It preserves the original item's legacy data and saves the selected block types across server restarts.

## Player settings on Bukkit 1.12.2–1.19.4

`/settings` or `/toggles` shows the player's permitted settings. `/toggle <tpa|msg|pay> [on|off]` changes the same settings as `/tptoggle`, `/msgtoggle`, or `/paytoggle`. Teleport auto-accept configured through `/tpauto` also survives a restart. Messaging and teleport choices are saved in `player-settings-legacy.yml`, and payments use the existing economy file.

`/payconfirmtoggle [on|off] ([player])` saves whether outgoing payments require confirmation. With confirmation enabled, repeat the same recipient and amount within 30 seconds to send the payment. Staff with `allium.payconfirmtoggle.others` can change the setting for another known player.

`/paytoggle [on|off] [player]` controls incoming payments. The `/payon` and `/payoff` aliases always set their named state. Staff with `allium.paytoggle.others` can change the setting for another known player.

`/redeem` (`/claimrank`) uses the same `redeem-settings` as the modern runtime. Allium records the UUID and rank as claimed before running its configured console commands. If a command fails, staff can review the logs and deliver any remaining rewards.

`/autorestart` (`/ar`) reads daily schedules, countdowns, and pre-restart commands from `autorestart.yml`. Staff can schedule, cancel, inspect, or dry-run a restart. Players can vote with `/voterestart`. Use a server wrapper to bring the server back after the clean shutdown. Restart notices on this runtime go to the server console and players.

`/clearchat [player]` clears everyone’s chat by default. Targeting another player requires `allium.clearchat.others`. Global clears honor `allium.clearchat.exempt`. The line count comes from `chat.deletion_resend.clear_lines`. A value of zero disables clearing. Previous chat messages are never replayed by this command.

`/clear [player|*] [item] [maxCount]` clears online inventories. `*` targets all visible online players with `allium.clearinventory.all`. The item filter accepts vanilla material names, including comma-separated lists. By default the same request must be repeated within 30 seconds. `/clearconfirmon` and `/clearconfirmoff` save the player's confirmation preference.

`/fly` grants unlimited flight with `allium.fly`. Players with `allium.tfly` can spend banked flight time in Survival or Adventure. Staff can use `/fly <player> <set|add|take> <time>` or `/fly <player> reset` with the corresponding `allium.tfly.<action>` permission, including for known offline players. Balances are saved in `timed-flight-legacy.yml`. Turn on `timed-flight.count-only-while-flying` to spend the bank only during actual flight.

`/toggleslowfall [on|off|status]` saves the flight landing protection preference for players with `allium.fly`. Protection is enabled by default. When Allium removes flight in midair, it applies the Slow Falling effect where available and protects the next landing for up to 15 seconds. Teleporting, landing, or enabling flight again ends the protection.

`/xp` calculates spendable points from the current level and XP bar. Players can inspect visible targets with `allium.xp.others`. Changes require `allium.xp.give`, `allium.xp.take`, or `allium.xp.set`. The command supports `add`, `remove`, `reset`, and level amounts such as `5L`. Selectors require `minecraft.command.selector`. Servers without Bukkit's selector API support plain `@s`, `@a`, `@p`, and `@r`.

`/xpbottle <amount|amountL|all>` withdraws experience into a bottle that can be redeemed with a right click in either hand. `/xpbottle <amount|min-max> <player>` mints bottles for another visible online player with `allium.xpbottle.other`. XP bottles store their amount and signer in item metadata under the same keys as the modern runtime.

## Chat and chat filter on Bukkit 1.12.2–1.19.4

Public chat formatting (`enable-chat-formatting`, `chat-format.default`, `chat-format.groups.*`, optional Vault prefix/suffix/group lookup), channels (`/channel`, `/g`, `/global`, `/shout`, `/l`, `/local`, `/a`, `/ac`, `/amsg`, `/staffchat`, `#staff` and `>global` shortcuts), local radius, `?` global questions (`allium.chat.question`), `@<player>` mention highlights and sounds (`allium.chat.mention`, `allium.chat.receive.mention`), URL permission gating (`chat.urls.enabled`, `allium.chat.url`), `/me` (`/action`), and `join-and-quit-messages` (including optional `first-join` and `broadcast-scope: world`) run natively on 1.12.2–1.19.4. Channel selections are saved in `chat-channels-legacy.yml`.

Opt-in `chat.filter.*` rules (phrase tokens/phrases/substrings/exceptions with leetspeak normalization and split-letter detection, repeat-similarity window with flood-suffix stripping and whitelist, rate-limit min-interval and burst caps, `block` vs `alert-only` mode, `allium.chat.filter.bypass`, and `allium.chat.filter.alerts` staff notices) share the same `ChatFilterEngine` logic as 1.20–26.3 and apply to both chat and `/me`.

## Command hider (`hide.yml`) on Bukkit 1.12.2–1.19.4

Opt-in `hide.yml` command filtering (`modules.hide`, `settings.enabled`) runs natively on 1.12.2–1.19.4 using the shared `HidePolicyEngine` group, inheritance, whitelist/blacklist, and double-slash (`//wand`, `//set`) rules. It filters command execution (`PlayerCommandPreprocessEvent`), tab completion (`TabCompleteEvent` on 1.12.2–1.19.4 plus dynamic `PlayerCommandSendEvent` root-tree filtering on 1.13–1.19.4), and `/help`, supports Vault/LuckPerms group lookups via reflection, honors `allium.hide.bypass`, and exposes `/allium hide <status|reload|enabled|resolve|fix|unfix|group>` (`allium.hide.admin`).

`/rtoggle` (`/replytoggle`) switches `/reply` between the last person you messaged and the last person who messaged you. The selected mode survives a restart. Message history used to pick the recipient lasts for the current session.

Teleport commands and tab completion accept partial usernames, nicknames, and close typos. Teleporting another player with `/tp <player> <target>` requires `allium.tp.others`.

`/tppet [player]` and `/tpent [player]` (`/tpmob`) toggle pet and mob selection for teleportation. Selected mobs show a persistent yellow dust particle highlight above their head on 1.12.2 (and entity glow with yellow dust fallback on 1.13–1.19.4), and follow when the player teleports or uses `/tphere` or `/there`.

`/spectate <player>` uses the same `spectate-commands` configuration as the modern runtime. Entries can run as the console or use `player:` to run with the staff member's permissions.

`/tpoffline <player>` uses known logout locations saved in `logout-locations-legacy.yml`. Saved positions from vanished players require `allium.vanish.see`. When the named player is currently online, the command checks visibility and uses their current location.

## Development build

Run Gradle with JDK 25. JDK 17 and JDK 21 are also required by the build.

```sh
./gradlew -PmodPlatforms=true -PdistributablePack=true platformJars
```

| Artifact staged in `build/platforms/` | Installation |
| --- | --- |
| `Allium-Paper-1.0.2.jar` | `plugins/` on Bukkit, Spigot, Paper, Purpur, or Folia for Minecraft **1.12.2 – 26.3** (Java 8+ compatibility runtime on 1.12.2–1.19.4; full modern runtime on 1.20–26.3 with Java 17+) |
| `Allium-Fabric-1.0.2.jar` | `mods/` on Fabric for Minecraft 1.14, 1.20.1, 1.21.11, 26.1, 26.1.1, 26.1.2, 26.2, or 26.3 |
| `Allium-NeoForge-1.0.2.jar` | `mods/` on NeoForge for Minecraft 1.20.2, 26.1, 26.1.1, 26.1.2, 26.2, or 26.3 |

`./gradlew paperPlatformJar` stages only the unified Bukkit/Spigot/Paper/Folia artifact. Existing `shadowJar` consumers can continue to use `build/libs/Allium.jar` (identical bytes).

## Fabric server requirements

Use Fabric Loader 0.19.3 or later. The jar selects its adapter at startup. Replace any older version-specific Allium Fabric jar in `mods/` with the combined jar.

| Minecraft | Java | Fabric API |
| --- | --- | --- |
| 1.14 | 8 | Bundled Fabric API Base and Fabric Commands v0 |
| 1.20.1 | 17 | Install Fabric API for 1.20.1 |
| 1.21.11 | 21 | Install Fabric API for 1.21.11 |
| 26.1, 26.1.1, 26.1.2, 26.2, 26.3 | 25 | Install Fabric API for your exact Minecraft version |

Build only this jar with `./gradlew -PmodPlatforms=true :allium-fabric-universal:stagePlatformJar`. The older adapters use Loom's remapping toolchain. [Fabric Loom](https://docs.fabricmc.net/develop/loom/)

## NeoForge server requirements

| Minecraft | Java | NeoForge |
| --- | --- | --- |
| 1.20.2 | 17 | 20.2.93 |
| 26.1, 26.1.1, 26.1.2, 26.2, 26.3 | 25 | NeoForge for the exact Minecraft version |

Build only this jar with `./gradlew -PmodPlatforms=true :allium-neoforge:stagePlatformJar`. The task runs the 1.20.2 adapter build with Gradle 8.14.5 and JDK 17. The jar includes the metadata each loader generation expects and selects its adapter at startup. [NeoForge 1.20.2 build template](https://github.com/NeoForgeMDKs/MDK-1.20.2-NeoGradle)

## Development adapters

| Development artifact | Build command | Server dependencies |
| --- | --- | --- |
| `Allium-Fabric-1.14-1.0.2.jar` | `./gradlew -PmodPlatforms=true :allium-fabric-1.14:stagePlatformJar` | Minecraft 1.14, Java 8, and Fabric Loader 0.16.14 or later. The JAR bundles the matching Fabric API Base and Fabric Commands v0 modules. |
| `Allium-NeoForge-1.20.2-1.0.2.jar` | `gradle -p platforms/neoforge-1.20.2 stagePlatformJar` with Gradle 8.14.5 and JDK 17 | Minecraft 1.20.2, Java 17 and NeoForge 20.2.93 |

The unified `Allium-Paper-<version>.jar` (`build/libs/Allium.jar`) bundles a Java 8 bootstrap (`AlliumBootstrap`) and compatibility runtime (`LegacyBukkitRuntime`) alongside the full Java 17 modern runtime (`PluginStart`), selecting the right runtime automatically from the server's Minecraft and Java versions.
