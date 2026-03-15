# Allium Development TODO

## Foundation
- [x] Project structure & Gradle build system
- [x] Main plugin class with Takion integration
- [x] Module system (AbstractModule / ModuleManager)
- [x] Config management
- [x] Database layer (MySQL + SQLite via HikariCP)
- [x] Command manager skeleton
- [x] Listener manager skeleton
- [x] Logger utility

## Infrastructure Systems
- [x] BackupManager (timestamped backups, auto-prune)
- [x] ConfigVersionManager (incremental migration pipeline)
- [x] ResourceSyncManager (deep-merge missing keys from defaults)
- [x] Messages enum (source-of-truth for all message keys)
- [x] LanguageManager (multi-locale, fallback to en_US, auto-sync missing keys)
- [x] MessageManager (Takion MessageSender integration, Builder pattern, placeholders)
- [x] Default language files (en_US, es_ES)
- [x] config-version tracking in config.yml
- [ ] Module-specific config versioning
- [ ] Translation generator tool (future)
- [ ] Optional Google/DeepL translation integration

## Core Modules (to implement)
- [ ] Teleportation (tp, tpa, tpahere, tpaccept, tpdeny, back)
- [ ] Homes (sethome, delhome, home, homes)
- [ ] Warps (setwarp, delwarp, warp, warps)
- [ ] Spawn (setspawn, spawn)
- [ ] Kits (kit, createkit, delkit, kits)
- [ ] Economy (balance, pay, eco)
- [ ] Chat (formatting, channels via Takion, mute, broadcast)
- [ ] Moderation (ban, tempban, kick, mute, tempmute, jail, warn)

## Player Data
- [ ] Player data storage (DB schema for homes, warps, balances, etc.)
- [ ] Player session tracking (join/quit, last location)
- [ ] Nickname system
- [ ] Mail / messaging system

## Commands
- [ ] /allium reload
- [ ] /allium version
- [ ] /gamemode
- [ ] /fly
- [ ] /god
- [ ] /heal / /feed
- [ ] /speed
- [ ] /enchant
- [ ] /give / /item
- [ ] /time / /weather
- [ ] /vanish
- [ ] /near
- [ ] /seen
- [ ] /whois
- [ ] /sudo
- [ ] /burn / /ext
- [ ] /top
- [ ] /hat
- [ ] /skull
- [ ] /afk

## Chat & Messaging (Takion-powered)
- [ ] Custom chat formatting with Takion MessageSender
- [ ] Placeholder integration via Takion PlaceholderManager
- [ ] Title/actionbar commands using TitleManager
- [ ] Private messaging (/msg, /reply)
- [ ] Social spy
- [ ] Chat channels (staff, local, global)

## Permissions & Integration
- [ ] Permission nodes for every command
- [ ] Vault economy provider (if economy module enabled)
- [ ] PlaceholderAPI expansion
- [ ] Vault chat/permissions bridge via Takion VaultAdapter

## Configuration
- [ ] Per-module config files
- [ ] Messages/locale file (fully customizable)
- [ ] Signs (colored signs, special signs)
- [ ] Configurable cooldowns

## Quality & Polish
- [ ] Tab completion for all commands
- [ ] Async database operations
- [ ] Caching layer for frequently accessed data
- [ ] Migration tool from EssentialsX data
- [ ] Comprehensive error handling
- [ ] JAR size audit (target: <5MB)

## Notes
- Takion handles: chat formatting, placeholders, titles, scheduling, channels
- HikariCP shaded for connection pooling (~150KB)
- MySQL connector provided by server or runtime download
- SQLite bundled with Spigot server
