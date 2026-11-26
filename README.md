[![Allium](https://repository-images.githubusercontent.com/1241676082/584be25a-5a5a-4322-a1f8-5d595b62bd59)](https://github.com/castledking/Allium/wiki)

<h1 align="center">The modular essentials, economy, moderation &amp; addon suite for modern Minecraft servers</h1>

<p align="center">
  <a href="https://discord.gg/D6CeVTtJpF"><img alt="Discord" src="https://img.shields.io/badge/Discord-Community-5865F2?style=for-the-badge&logo=discord&logoColor=white"></a>
  <a href="https://github.com/castledking/Allium/releases"><img alt="GitHub Releases" src="https://img.shields.io/badge/GitHub-Releases-181717?style=for-the-badge&logo=github"></a>
  <a href="https://github.com/castledking/Allium/wiki"><img alt="Wiki" src="https://img.shields.io/badge/GitHub-Wiki-181717?style=for-the-badge&logo=github"></a>
  <a href="https://castled.codes"><img alt="CASTLED CODEX" src="https://castled.codes/assets/logo-banner.png" width="140" height="35"></a>
</p>

**Allium** replaces fragmented essentials, economy, sign, mail, moderation, and custom-tool plugins with a modular engine for **Spigot, Paper, Purpur, and Folia**. Official addons add custom crops, trading cards and relics, player and staff menus, custom ores, party locator bars, and spawner crafting.

Every major system can be toggled independently in `config.yml`. Allium integrates with Vault, LuckPerms, PlaceholderAPI, PacketEvents, DiscordSRV, Nexo, CraftEngine, Oraxen, GriefPrevention / GriefPrevention3D, and ResourcePackManager.

---

## Key Features

- **Complete Essentials & Travel Suite**
  Homes, warps, default & per-group spawns, `/tpa`/`/tpahere`/`/back`/`/rtp`, `/fly` (with persistent timed flight), `/heal`, `/feed`, `/repair`, `/enchant`, `/spawnmob`, `/give` with a full custom item library, `/worth` & `/sell`, kits, custom text pages (`/motd`, `/rules`, `/info`, `/customtext`), scheduled broadcasts, and `/autorestart`.
####
- **Journaled Economy & Vault Provider**
  Single-server ledger on SQLite, MySQL, or MariaDB with optional Vault integration, `/bal`, `/pay`, `/baltop`, `/eco`, `/paytoggle`, `/payconfirm`, transaction taxes with refund audit trails, signed physical bank cheques (`/cheque`), and safe cross-module reservations for paid signs, kits, and mail gifts. Full support for Geyser/Floodgate `.`-prefixed Bedrock usernames.
  ```yaml
  economy:
    vault-provider-enabled: true
    starting-balance: '100.00'
  ```
####
- **22 Interactive Signs & Short Price Formatting**
  Full non-shop EssentialsX sign parity: `[Balance]`, `[Warp]`, `[Kit]`, `[Mail]`, `[Heal]`, `[Repair]`, `[Disposal]`, `[Info]`, `[Color]`, `[Workbench]`, `[Anvil]`, `[Cartography]`, `[Grindstone]`, `[Loom]`, `[Smithing]`, `[Gamemode]`, `[Time]`, `[Weather]`, `[RandomTeleport]` (`[RTP]`), `[Free]`, `[Enchant]`, and `[Spawnmob]` (including `wolf:tamed` owned by the clicking player).
  ```yaml
  sign-short-prices: true       # $1,100 -> $1.1K, $1,500,000 -> $1.5M
  adopt-essentials-signs: false # Automatically adopt legacy dark-blue [Type] signs
  ```
####
- **Seamless Essentials / EssentialsX & Legacy Migration**
  Preview every change with a read-only dry run before applying under an automatic login fence:
  ```
  /allium import essentials
  /allium import essentials apply <run>
  ```
  Imports balances, homes, warps, spawns, logout locations, legacy & structured mail, nicknames, ignore lists, preferences, jails, active mutes, jailed players, account names, and `enabledSigns` — and automatically converts existing dark-blue Essentials world signs into Allium signs on load or click. Upgrading from legacy Allium (`0.x`) also migrates `config.yml`, `modguard.yml`, `autorestart.yml`, `explosions.yml`, and addon configs automatically on first boot.
####
- **Official Addon Ecosystem & In-Game Installer**
  Heavier gameplay features ship as modular addons in `plugins/Allium/addons/` and can be installed directly from GitHub Releases or toggled on/off without removing their jars:
  ```
  /allium addon list
  /allium addon download <addon>
  /allium addon enable <addon>
  /allium addon disable <addon>
  ```
  | Addon | Jar | Description |
  | --- | --- | --- |
  | **Crops** | `Addon-Crops-1.0.1.jar` | Custom crops, fertilizers, sprinklers, Kitchen baking & pies, and Jade overlays. |
  | **Curios** | `Addon-Curios-1.0.1.jar` | Trading cards, 7-slot Relics equipment, textured menus, `/frame`, and chat hover frames. |
  | **Menus** | `Addon-Menus-1.0.1.jar` | Player settings, homes, warps, shop, and staff panels with native dialogs and inventory fallbacks. |
  | **Ores** | `Addon-Ores-1.0.1.jar` | Deterministic CraftEngine custom ore generation, `/pregen`, and Jade ore overlays. |
  | **PartyLocator** | `Addon-PartyLocator-1.0.1.jar` | `/party` groups and vanilla `1.21.6+` locator bar integration. |
  | **SpawnerCraft** | `Addon-SpawnerCraft-1.0.1.jar` | 208+ signed mob head drops, Spawner Changers, and multi-recipe slideshow guides. |
####
- **Chat, Hover Tooltips, Message Deletion & Offline Mail**
  Static RGB/MiniMessage chat formatting with per-group `chat-format.groups`, hover tooltips, packet-level `/deletemsg` resync, `@mentions`, persistent `/nick`, private messaging (`/msg`, `/reply`, `/ignore`, `/msgtoggle`, `/messagesounds`, `/socialspy`), and offline text mail & atomic item gifts (`/mail`).
####
- **Moderation, Discord Feeds, `/restore` & ModGuard**
  - Persistent discipline commands (`/ban`, `/tempban`, `/ipban`, `/mute`, `/jail`, `/freeze`, `/warn`, `/notes`, `/vanish`, `/invsee`, `/enderchest`) with `/report` and `/escalate` Discord webhook/DiscordSRV feeds.
  - Interactive `/restore` inventory & ender chest snapshot editor with retention pruning.
  - **ModGuard** (`modguard.yml`): client brand, plugin channel, Forge/Lunar mod list, and PacketEvents sign translation probe detection with `/allium modguard <reload|alerts [on|off]>`.
  - Command hiding (`hide.yml`) and alt-account detection (`/altexempt`).
####
- **Signed Privileged Tools & Regenerating Explosions**
  HMAC-signed `Mob Disarmer`, `Phantom Obliterator`, `Lazy Axe`, `Handcuffs`, `Claim Handcuffs`, and configurable `/powertool` definitions under `plugins/Allium/tools/*.yml`, plus bottom-up crash-safe regenerating explosions (`explosions.yml`).
####
- **Built-in Resource Pack Delivery**
  Bundles Allium's deterministic core pack, automatically merges enabled official addon packs, and delivers via built-in standalone HTTP self-hosting (`resource-pack.owner: standalone`) or ResourcePackManager (`resource-pack.owner: rpm-export`).

---

## Supported Languages

Set `settings.locale` in `plugins/Allium/config.yml` or customize messages and `[sound]` cues under `plugins/Allium/lang/`:

| Code | Language |
| --- | --- |
| `en_US` | English |
| `es_ES` | Spanish |

---

## Server Compatibility

Allium and all official addons target Minecraft `1.20` through `26.x` on **Spigot, Paper, Purpur, and Folia** (using region- and entity-owned scheduling with `teleportAsync` on Folia). Live regression coverage includes Spigot `1.20.1` and Paper `26.3`. [Live test results](https://github.com/castledking/Allium/blob/master/tests/live/signs/RESULTS.md)

The core JAR targets Java 17. Use the Java version required by your server build and any addons or integrations you install.

| Minecraft version | Server Java |
| --- | --- |
| `1.20`–`1.20.4` | Java 17 on Spigot. Paper recommends Java 21. |
| `1.20.5`–`1.21.11` | Java 21 |
| `26.1` and later in `26.x` | Java 25 |

[Spigot BuildTools requirements](https://www.spigotmc.org/wiki/buildtools/) · [Paper Java requirements](https://docs.papermc.io/paper/getting-started/#requirements) · [Minecraft 1.20.5 release notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-20-5) · [Minecraft 26.1 release notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-1)

The economy uses `database.type: SQLITE` by default with WAL and `synchronous=FULL`. To use MySQL or MariaDB, set `database.type: MYSQL` and configure `database.mysql`. The economy requires InnoDB with `innodb_flush_log_at_trx_commit=1` and `sync_binlog=1` when binary logging is enabled. [MySQL InnoDB durability settings](https://dev.mysql.com/doc/refman/8.4/en/innodb-parameters.html#sysvar_innodb_flush_log_at_trx_commit) · [MariaDB InnoDB durability settings](https://mariadb.com/docs/server/server-usage/storage-engines/innodb/binary-log-group-commit-and-innodb-flushing-performance)

Use one Allium server per economy schema. Back up the database with `plugins/Allium/.server-id` and your server's `playerdata` directory so pending paid actions can recover after a restore. Changing `database.type` selects a different store. Transfer existing data separately before switching a live server.

---

## Download
### [⬇ Download Allium and Official Addons from GitHub Releases](https://github.com/castledking/Allium/releases)
Also available on [Modrinth](https://modrinth.com/plugin/allium-essentials) (with official addons attached as supplementary files) and [SpigotMC](https://www.spigotmc.org/resources/126462/).

## Documentation
For full setup, configuration, commands, permissions, and addon guides, see the [Allium Wiki](https://github.com/castledking/Allium/wiki).

## Building from Source
Requires JDK 17 and JDK 21:
```bash
./gradlew check shadowJar :addons:crops:build :addons:curios:build :addons:menus:build :addons:ores:build :addons:partylocator:build :addons:spawnercraft:build -PdistributablePack=true
```

## Support
- [📖 Documentation Wiki](https://github.com/castledking/Allium/wiki) — Setup guides, commands, permissions, and configuration reference.
- [🌐 Website](https://castled.codes) — Projects, updates, and downloads from CASTLED CODEX.
- [🐛 Issue Tracker](https://github.com/castledking/Allium/issues) — Report bugs or request features.
- [💬 Discord Community](https://discord.gg/D6CeVTtJpF) — Get support and follow development.
