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

`/rtoggle` (`/replytoggle`) switches `/reply` between the last person you messaged and the last person who messaged you. The selected mode survives a restart. Message history used to pick the recipient lasts for the current session.

Teleport commands and tab completion accept partial usernames, nicknames, and close typos. Teleporting another player with `/tp <player> <target>` requires `allium.tp.others`.

`/spectate <player>` uses the same `spectate-commands` configuration as the modern runtime. Entries can run as the console or use `player:` to run with the staff member's permissions.

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
