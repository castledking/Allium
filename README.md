# Allium

A modern, modular Essentials replacement for Spigot/Paper servers. This is a full recode of the original Allium plugin -- rebuilt from scratch with a cleaner architecture, better tooling, and a focus on maintainability.

> **Status:** Recode in progress. Foundation and infrastructure systems are in place; module implementation is underway.

---

## What's Different in the Recode

- **Gradle Kotlin DSL** replaces the old Maven build. Shadow plugin handles fat-jar packaging with dependency relocation.
- **Modular architecture.** Every feature group (homes, warps, economy, moderation, etc.) is a self-contained module that can be toggled on/off in `config.yml`.
- **Takion-powered messaging.** Rich chat formatting, gradients, placeholders, titles, and channel routing via [Takion](https://github.com/CroaBeast/Takion) instead of hand-rolled text processing.
- **Production-grade config management.** Automatic migrations, backup-before-upgrade, and missing-key insertion so server owners never lose their settings on update.
- **Full i18n from day one.** Every user-facing string lives in a `Messages` enum with locale-specific YAML files and automatic fallback.
- **MySQL + SQLite** via HikariCP connection pooling. One config toggle to switch.
- **InvUI-based GUIs** for commands like `/nick`, `/warps`, and kit selection.
- **JAR size target: < 5 MB** with all dependencies shaded.

---

## Libraries

| Library | Purpose | Bundled |
|---------|---------|---------|
| [Takion](https://github.com/CroaBeast/Takion) 1.4 | Chat formatting, placeholders, titles, scheduling, channel routing. Bundles PrismaticAPI, GlobalScheduler, YAML-API. | Yes (shaded) |
| [InvUI](https://github.com/NichtStudioCode/InvUI) 1.49 | Inventory GUI framework for in-game menus. | Yes (shaded) |
| [HikariCP](https://github.com/brettwooldridge/HikariCP) 5.1.0 | JDBC connection pooling for MySQL/SQLite. | Yes (shaded) |
| [Vault](https://github.com/MilkBowl/VaultAPI) | Economy and permissions bridge. | No (soft-depend) |
| [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) | External placeholder expansion. | No (soft-depend) |

All shaded dependencies are relocated under `net.survivalfun.allium.lib.*` to avoid classpath conflicts.

---

## Versioning

Allium uses a **`config-version`** key in `config.yml` to track schema changes across releases.

**How it works:**

1. On startup, `ConfigVersionManager` reads the installed `config-version` and compares it to the expected version.
2. If behind, `BackupManager` creates a timestamped snapshot of all config and language files under `plugins/Allium/backups/`.
3. Registered migrations run sequentially (v1 -> v2 -> v3, etc.), each modifying the config as needed.
4. `ResourceSyncManager` then deep-merges any new keys from the bundled defaults into the installed files -- existing values are never overwritten.
5. Old backups are auto-pruned (keeps the last 10).

Server owners can update the plugin JAR and restart without worrying about broken configs or lost customizations.

---

## Translations

Every user-facing message is defined in the `Messages` enum -- a single source of truth with a YAML key path and a default English value. Language files live in `plugins/Allium/lang/`:

```
lang/
  en_US.yml   (English - default/fallback)
  es_ES.yml   (Spanish)
```

**On startup:**
- `LanguageManager` loads the locale specified in `settings.locale` from `config.yml`.
- If a key is missing from the active locale file, it falls back to `en_US`.
- If a key is missing from *all* locale files, it gets auto-inserted from the `Messages` enum defaults.
- Locale files for unknown codes are auto-generated from the enum defaults.

Adding a new language is as simple as copying `en_US.yml`, renaming it (e.g., `fr_FR.yml`), translating the values, and setting `settings.locale: fr_FR`.

---

## Building

```bash
git clone https://github.com/castledking/Allium-recode.git
cd Allium-recode
./gradlew clean build
```

Output JAR: `build/libs/Allium-<version>.jar`

Requires **Java 17+** to build. Targets Spigot API 1.20+.

---

## Project Structure

```
src/main/java/net/survivalfun/allium/
  Allium.java              Main plugin entry point
  core/
    AbstractModule.java    Base class for feature modules
    ModuleManager.java     Module lifecycle management
  config/
    ConfigManager.java     Config loading/saving orchestrator
    BackupManager.java     Timestamped file backups
    ConfigVersionManager.java  Migration pipeline
    ResourceSyncManager.java   Missing-key deep merge
  database/
    DatabaseManager.java   HikariCP pooling, MySQL/SQLite
    DatabaseType.java      Enum for DB backend selection
  message/
    Messages.java          Source-of-truth message enum
    LanguageManager.java   Multi-locale loading + fallback
    MessageManager.java    Takion MessageSender integration
  command/
    CommandManager.java    Command registration
  listener/
    ListenerManager.java   Event listener registration
  util/
    AlliumLogger.java      Debug-aware logging wrapper
```

---

## License

TBD
