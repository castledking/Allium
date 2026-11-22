[![Allium](https://castled.codes/assets/alliumv2-banner.png?v=3)](https://github.com/castledking/Allium/wiki)

<h1 align="center">The modular essentials, economy, moderation &amp; addon suite for modern Minecraft servers</h1>

<p align="center">
  <a href="https://discord.gg/D6CeVTtJpF"><img alt="Discord" src="https://img.shields.io/badge/Discord-Community-5865F2?style=for-the-badge&logo=discord&logoColor=white"></a>
  <a href="https://github.com/castledking/Allium/releases"><img alt="GitHub Releases" src="https://img.shields.io/badge/GitHub-Releases-181717?style=for-the-badge&logo=github"></a>
  <a href="https://github.com/castledking/Allium/wiki"><img alt="Wiki" src="https://img.shields.io/badge/GitHub-Wiki-181717?style=for-the-badge&logo=github"></a>
  <a href="https://castled.codes"><img alt="CASTLED CODEX" src="https://castled.codes/assets/logo-banner.png" width="140" height="35"></a>
</p>

**Allium** replaces fragmented essentials, economy, sign, mail, moderation, and custom-tool plugins with one clean, modular engine built for **Spigot, Paper, Purpur, and Folia (`1.20` – `26.x`)** — backed by an official addon ecosystem for custom crops, trading cards & relics, custom ores, party locator bars, and spawner crafting.

Every major system can be toggled independently in `config.yml`, runs with crash-safe SQLite/MySQL persistence, and integrates cleanly with Vault, LuckPerms, PlaceholderAPI, PacketEvents, DiscordSRV, Nexo, CraftEngine, Oraxen, GriefPrevention / GriefPrevention3D, and ResourcePackManager.

---

## Key Features

- **Complete Essentials & Travel Suite**
  Homes, warps, default & per-group spawns, `/tpa`/`/tpahere`/`/back`/`/rtp`, `/fly` (with persistent timed flight), `/heal`, `/feed`, `/repair`, `/enchant`, `/spawnmob`, `/give` with a full custom item library, `/worth` & `/sell`, kits, custom text pages (`/motd`, `/rules`, `/info`, `/customtext`), scheduled broadcasts, and `/autorestart`.
####
- **Journaled Economy & Vault Provider**
  Crash-safe SQLite (WAL `synchronous=FULL`) or MySQL/MariaDB ledger with Vault integration, `/bal`, `/pay`, `/baltop`, `/eco`, `/paytoggle`, `/payconfirm`, transaction taxes with refund audit trails, signed physical bank cheques (`/cheque`), and safe cross-module reservations for paid signs, kits, and mail gifts. Full support for Geyser/Floodgate `.`-prefixed Bedrock usernames.
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
  | **Crops** | `Addon-Crops-1.0.0.jar` | Custom crops, fertilizers, sprinklers, Kitchen baking & pies, and Jade overlays. |
  | **Curios** | `Addon-Curios-1.0.0.jar` | Trading cards, 7-slot Relics equipment, textured menus, morphs, `/frame`, and chat hover frames. |
  | **Ores** | `Addon-Ores-1.0.0.jar` | Deterministic CraftEngine custom ore generation, `/pregen`, and Jade ore overlays. |
  | **PartyLocator** | `Addon-PartyLocator-1.0.0.jar` | `/party` groups and vanilla `1.21.6+` locator bar integration. |
  | **SpawnerCraft** | `Addon-SpawnerCraft-1.0.0.jar` | 208+ signed mob head drops, Spawner Changers, and multi-recipe slideshow guides. |
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

## Supported Platforms: Spigot, Paper, Purpur, and Folia.
### Allium targets and supports Minecraft `1.20` – `26.x` on Java 17+ (Java 21+ for Paper `1.20.5+` and optional Java 21 item providers).

---

## Download
### [⬇ Download Allium and Official Addons from GitHub Releases](https://github.com/castledking/Allium/releases)
Also available on [Modrinth](https://modrinth.com/plugin/allium-essentials) (with official addons attached as supplementary files) and [SpigotMC](https://www.spigotmc.org/resources/126462/).

## Documentation
For full setup, configuration, commands, permissions, and addon guides, see the [Allium Wiki](https://github.com/castledking/Allium/wiki).

## Building from Source
Requires JDK 17, JDK 21, and Python 3 with `pyyaml`:
```bash
./gradlew check shadowJar :addons:crops:build :addons:curios:build :addons:ores:build :addons:partylocator:build :addons:spawnercraft:build -PdistributablePack=true
```

## Support
- [📖 Documentation Wiki](https://github.com/castledking/Allium/wiki) — Setup guides, commands, permissions, and configuration reference.
- [🌐 Website](https://castled.codes) — Projects, updates, and downloads from CASTLED CODEX.
- [🐛 Issue Tracker](https://github.com/castledking/Allium/issues) — Report bugs or request features.
- [💬 Discord Community](https://discord.gg/D6CeVTtJpF) — Get support and follow development.
