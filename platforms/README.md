# Allium native platforms

The Fabric (`1.14`, `1.20.1`, `1.21.11`, `26.1–26.3`) and NeoForge (`1.20.2`, `26.1–26.3`) adapters run a shared portable + Brigadier essentials command suite on dedicated servers. Player commands work out of the box, while admin/staff actions and targeting other players use vanilla gamemaster permission (level 2). Every command registers under `allium:<name>` and exposes bare aliases only when the vanilla command tree does not already own them. The Paper artifact includes the existing Bukkit plugin and uses its existing server matrix.

## Native Fabric & NeoForge commands

The mod JAR stays in `mods/`, while all configuration, language files, text pages, and persisted data live under `<serverRoot>/plugins/Allium/` (matching the Bukkit/Paper layout). On first start, Allium extracts bundled default `config.yml`, `lang/en_US.yml`, `lang/es_ES.yml`, `info.txt`, `rules.txt`, `motd.txt`, `custom.txt`, `broadcasts.txt`, and `worth.yml` into `plugins/Allium/` without overwriting user edits; reload them anytime with `/allium reload` (gamemaster).

- **Homes** (`homes/<uuid>.properties`): `/sethome [name]`, `/home [name]`, `/delhome [name]`, `/homes`, and `/renamehome <old> <new>`. Omitting the name uses `home`. Players start with 1 slot; gamemasters get up to 1,000 slots.
- **Warps** (`warps.properties`): `/setwarp <name>` (gamemaster), `/warp [name]`, `/delwarp <name>` (gamemaster), and `/warps` with tab completion.
- **Spawn** (`spawn.properties`): `/setspawn` (gamemaster) and `/spawn [player]` (falls back to the overworld spawn when unset).
- **Teleportation**: `/tpa <player>`, `/tpahere <player>`, `/tpaccept`, `/tpdeny`, `/tpacancel` (60s expiry, fuzzy online-player lookup), `/tphere <player>` (gamemaster), `/back` (automatically records previous location on `/home`, `/warp`, `/spawn`, `/tpa`, `/tpahere`, `/tphere`, and `/back`), plus persisted `/tptoggle [on|off]` and `/tpauto [on|off]` in `settings.properties`.
- **Messaging & ignores** (`settings.properties`, `ignores/<uuid>.properties`): `/pm` / `/m` / `allium:msg <player> <message>`, `/reply` (`/r`), `/rtoggle` (`/replytoggle`), `/msgtoggle [on|off]`, `/ignore <player>`, `/unignore <player>`, and `/ignorelist`.
- **Chat channels, staff chat & Discord relay** (`channels.properties`, `config.yml`): `/channel [global|local|staff]` (`/ch`), `/g [message]` (`/global`, `/shout`), `/l [message]` (`/local`), `/a [message]` (`/ac`, `/amsg`, `/staffchat`, gamemaster), and `/me <action>` (`/action`). Live chat interception runs on Fabric `1.20.1`/`1.21.11`/`26.x` (`fabric-message-api-v1`) and NeoForge `1.20.2`/`26.x` (`ServerChatEvent`) with `#staff`, `>global`, and `?question` shortcuts, local chat radius, mute enforcement, and ignore filtering. Configuring `discord.channels.{chat,staff,moderation,notes,reports}.webhook` in `plugins/Allium/config.yml` relays global chat, staff chat, join/quit notices, moderation actions, staff notes, and player reports asynchronously to Discord webhooks.
- **Moderation** (`moderation.properties`, `warnings/<uuid>.properties`, `notes/<uuid>.properties`): `/mute <player> [duration] [reason]`, `/unmute <player>`, `/setjail <name>`, `/deljail <name>`, `/jails`, `/jail <player> <jail> [duration] [reason]`, `/unjail <player>` (restores saved return location), `/jailedplayers`, `/warn <player> <reason>`, `/warnings [player]`, `/notes <player> [text]`, `/unnote <player> <id>`, `/freeze <player>`, `/kickall [reason]`, `/vanish` (`/v`, hides from `/list`, `/near`, `/tpa`, and `/pm`), `/report <player> <reason>`, and `/helpop <message>`.
- **Player info, mail, AFK & nicknames** (`players.properties`, `mail/<uuid>.properties`): `/mail [read|send|sendall|clear]` (up to 50 messages per mailbox, respects ignores and mutes), `/seen <player>`, `/whois <player>` (gamemaster, includes UUID, nickname, economy balance, ping, location, playtime, and mail count), `/playtime [player]` (`/pt`), `/afk [reason]` (`/away`), `/nick <nickname|off> [player]`, `/realname <query>`, and `/near [radius]`.
- **Text pages & broadcasts** (`info.txt`, `rules.txt`, `motd.txt`, `custom.txt`, `broadcasts.txt`): `/info`, `/rules`, `/motd`, `/customtext` (`/ctext`) with `[section|page] [page]`, `/inform <player> [section|page] [page]` (gamemaster), and `/broadcasts [next|send <section>]` (gamemaster). Supports Essentials `#section,alias` chapters, `##` comments, player-specific overrides (`info_steve.txt`), 9-line pagination, `&` color codes, and `{player}`, `{displayname}`, `{online}`, `{max}`, `{server}` placeholders; default files are extracted into `plugins/Allium/` and file edits apply immediately.
- **Economy & worth** (`economy.properties`, `worth.properties`): `/balance [player]` (`/bal`, `/money`), `/pay <player> <amount>` (supports `$1.5k`/`2m` shorthand, `/paytoggle` / `/payon` / `/payoff`, and 30s `/payconfirmtoggle` / `/payconfirmon` / `/payconfirmoff`), `/eco <give|take|set|reset> <player> [amount]` (gamemaster, works on known offline accounts), `/baltop [page]`, `/worth [item]`, `/setworth <price> [item]` (gamemaster), and `/sell [hand|all]` (sells main hand or main 36 inventory slots without touching equipped armor or offhand). When **EconomyCraft** (`economycraft`) is installed, Allium automatically delegates balances, transfers, `/eco`, `/sell`, and `/baltop` to EconomyCraft's API so the server shares one unified currency; when EconomyCraft is absent, Allium runs its built-in flatfile ledger and exposes `codes.castled.allium.portable.AlliumEconomyApi` for other mods such as GriefPrevention3D.
- **Utilities & staff shortcuts**: `/allium [help|version|reload]`, `/ping [player]`, `/coords [player]`, `/depth`, `/list` (`/who`, `/online`), `/heal [player]`, `/feed [player]`, `/fly [player]`, `/gmc [player]`, `/gms [player]`, `/gma [player]`, `/gmsp [player]`, `/day`, `/night`, `/sun`, `/rain`, `/storm`, `/clearinventory [player]`, and `/broadcast <message>` (`/bc`).

## Homes (cross-platform notes)

Bukkit 1.12.2 stores homes in `plugins/Allium/homes/` and uses `allium.sethome.<amount>` / `allium.sethome.unlimited` for higher limits. Homes save the world or dimension, exact position, and facing. Delete an existing name before reusing it. Teleportation requires the saved destination's world to be loaded. The Paper plugin's travel configuration and database use their existing paths and commands.

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

`/report`, `/helpop`, and `/escalate` have separate in-game staff recipient permissions. Reports and help requests use independent cooldowns from `moderation.reports.cooldown-seconds`. The console can attribute an escalation with `/allium escalate <staffmember> <target> <reason>`. Legacy reports and escalations go to in-game staff and the server log.

`/kittycannon` launches a kitten, using a tamed baby ocelot on versions before standalone cats. `/beezooka` (`/beecannon`) works on versions with bees. Both mobs disappear in a harmless pop. `/nuke [player...]` drops TNT that can damage entities and leaves blocks intact. Running it without targets in game targets the sender.

`/cheque <amount>` takes the amount from your balance and gives a Money Note; `/cheque <amount|min-max> <player>` mints an admin note without funds (`allium.cheque.others` or the console). Right-clicking a note pays it once. Notes on 1.12.2–1.19.4 carry only an id line; the amount, signer and whether a note was redeemed live in `cheques-legacy.yml`, so a copied or edited note pays nothing.

A command typed with missing or wrong arguments shows its usage card, as on the modern platform: the command's description and one clickable line per form the sender may use, narrowed to the subcommand that was wrong (`/allium hide enabled`). The lines come from `command-usages.tsv`, which the build keeps equal to the command specs.

`/editnick [player]` (`/nickgui`) opens the nickname styling GUI: a colour (`allium.nick.color`), styles, and on 1.16+ preset gradients (`allium.nick.gradient`), with a preview; saving follows `/nick`'s rules. Versions before 1.16 cannot draw hex colours, so the gradient row is left out there. Another player's nickname needs `allium.nick.others`.

Alt detection (`modules.alts`, the `alts` section) runs as on the modern platform: each join records the player's IP, and an account that first used an IP an older account already used is an alt. Alts get the `alts.group` parent group through LuckPerms' command, or Vault when LuckPerms is absent, and holders of `allium.alerts` are told. `/altexempt <add|remove|check> <player> [reason]` keeps an account out and removes the group. `/seen` (`allium.seen.alts`) and `/whois` (`allium.whois.extra`) list linked accounts. The history is stored in `alts-legacy.yml`.

`/delmsg <id>` and `/delmsg <player> <amount>` (`allium.delmsg`, `allium.deletemsg` or `allium.staff`) delete chat messages for everyone. Staff who may delete see a hover with each public chat and `/me` line's id, and clicking it inserts `/delmsg <id>`. Everyone who saw a deleted message gets their chat cleared (`chat.deletion_resend.clear_lines`, optionally followed by `chat.deletion_resend.header`) and their remaining messages sent again. Allium keeps up to 4096 messages, 200 per player, for 15 minutes. Messages from other plugins are not kept, so they are not sent again after a re-sync.

Server taxes follow `economy.tax` as on the modern platform: with `apply-to.pay`, `/pay` sends the amount less the tax and the tax goes to the tax account (`account-name`); with `apply-to.cheques`, a player cheque is worth its amount less the tax. `allium.tax.exempt` pays none. `/tax` shows the rate and, with `allium.tax.balance`, the account. `/tax impose <player|*|**> <percent>` takes a percentage of one player's, everyone online's or (`allium.tax.impose.offline`) every account's balance and leaves each taxed player a mail receipt. `/tax history [player] [page]` lists impositions; clicking one refunds it, and `/tax refund <id|player> [percent] [confirm]` asks for confirmation before paying back. History is kept in `tax-legacy.yml`.

Powertools are read from `plugins/Allium/tools/*.yml` with the modern schema. Triggers: `left`, `right`, `shift-left`, `shift-right`, `swap`, `drop`, `on-break`, `on-interact-entity`, `on-hit` and `on-consume`. Actions: `command`, `console`, `message`, `actionbar`, `sound`, `particle`, `potion` and `grant`. Each binding's `hand`, `sneak`, `worlds`, `require-permission`, `cooldown` and `cancel-interaction` apply, and so do the tool's use permission, `uses` and click guard. Console actions follow `powertools.console-allowlist` and `powertools.block-selectors`, and only the modern placeholders are filled in. `allium.powertool.bypass` or the tool's bypass permission skips cooldowns, and skips uses unless `uses.bypassable` is false, as on vouchers. Items before 1.14 carry no hidden data, so a tool item ends with a dark `powertool:<name>` line and keeps its uses in a `Uses left` line. `/powertool list|info|get|give|enable|disable|toggle|reload` and `/ptt` work. Editing tools in game needs 1.20+, so here you edit the file and run `/powertool reload`. `/voucher give|list|reload` and `/vouchers` handle the powertools named `voucher_<name>`. The `on-fish` trigger (with its `fishing` states) and the `ride`, `mount` and `release` actions work too. A tool that uses an action this platform lacks (`tree_fell`, `disarm`, `obliterate`, `block_replace`), the `ride` action's `claim` option (the claim handcuffs) or one of Allium's own items is listed as needing 1.20+.

The Handcuffs (`tools/handcuffs.yml`, installed once as on the modern platform and recorded in `tools/.defaults`) are an unbreakable fishing rod here. With `allium.restrain`, cast at a player and reel them in: they ride you, stacked on anyone you already restrained. A restrained player cannot get down, run commands other than `restraints.allowed-commands`, break, place, use, drop or pick up items, fish, attack or be hurt. Before 1.13 getting down cannot be cancelled, so they are seated again a tick later. Press Q with the handcuffs to let everyone go. A restrained player who logs out runs `restraints.commands-on-quit`. `/unrestrain [player]` (`allium.unrestrain`) frees everyone restrained, or one player who is restrained or carried. Bukkit will not teleport a player who carries anyone, so let go before teleporting.

Creative manager (`modules.creative`, `creative-manager` in `config.yml`) runs natively on 1.12.2–1.19.4: per-action `allium.gamemode.creative.<action>` restrictions, blacklists (`blocks`, `entities`, `items`, `commands`, `protect-tnt`), separate creative and survival/adventure inventories (`creative-inventories-legacy.yml`), creative armor, potion-effect clearing on mode switch, creative-menu item lore/sanitization/enchanted/potion refusals, creative-placed block loot protection (`creative-placed-blocks-legacy.yml`), gamemode restore on join, gamemode audits, and spectator teleport protection. `/creativemanager` (`/cm`, `/allium creativemanager`) provides the paged list and flag-editor GUIs and `player|group <name> edit|list|check|add|remove [flag]` via LuckPerms or Vault reflection (read-only when neither is installed).

`/allium import essentials [apply <run>]` (`allium.import`) imports Essentials/EssentialsX data (`userdata/*.yml`, `warps/*.yml`, `jail.yml`/`jails.yml`, `spawn.yml`, `kits.yml`/`config.yml`, `worth.yml`, and `config.yml` `enabledSigns`) on 1.12.2–1.19.4 using the same dry-run-then-apply workflow as the modern runtime. Dry runs read `plugins/Essentials` (or the `source` and `worlds` mappings in `plugins/Allium/import/essentials.yml`) read-only and write `plan.json`, `records.jsonl`, and `summary.txt` under `plugins/Allium/import/essentials/<run>/`. Applying requires an empty server (refusing logins while running), an unapplied run ID, and matching `sourceSha256` and `planSha256` hashes, writes non-conflicting records into the legacy flatfile stores, records replay hashes in `import/essentials/ledger-legacy.yml`, and reloads the affected modules immediately.

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
