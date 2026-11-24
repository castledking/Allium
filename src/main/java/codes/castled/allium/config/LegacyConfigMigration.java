package codes.castled.allium.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Carries a legacy Allium config.yml into this Allium's layout.
 *
 * <p>The result starts from this Allium's own config.yml and takes each legacy setting through the
 * key map below: settings this Allium reads at the same path are copied, renamed ones move to their
 * new path, settings that now live in another file move there, and sections an addon owns stay in
 * the untouched legacy copy ({@value #SOURCE}) for that addon to read. Legacy settings with no
 * counterpart are retired; they too remain in the legacy copy. Every decision is written to
 * {@value #REPORT}.
 */
final class LegacyConfigMigration {
    static final String SOURCE = "config.legacy.yml";
    static final String CLEANUP_BACKUP = "config.yml.before-legacy-cleanup";
    static final String REPORT = "import/config-migration.txt";

    /** Where a legacy setting went. {@code file} is relative to plugins/Allium. */
    record Move(String legacyPath, String file, String path, Object value) {}

    /** Actual outcome of applying one planned move into its destination file. */
    enum MoveOutcome { APPLIED, EQUAL, CONFLICT_KEPT }

    record AppliedMove(Move move, MoveOutcome outcome) {}

    /** A legacy setting handed off to another file or addon via {@value #SOURCE}. */
    record Delegated(String legacyPath, String owner) {}

    /** A legacy setting that stays only in the legacy copy, and why. */
    record Retired(String legacyPath, String reason) {}

    record Plan(List<Move> moves, List<Delegated> delegated, List<Retired> retired) {
        List<Move> into(String file) {
            return moves.stream().filter(move -> move.file().equals(file)).toList();
        }
    }

    record Result(List<String> changed, List<String> kept, List<String> equal, List<AppliedMove> outcomes) {}

    /** Special-cased nested legacy paths that live under sections this Allium also defines. */
    static final List<String> NESTED_SPECIAL = List.of(
            "give.drop-overflow-items",
            "give.max-items-dropped",
            "economy.symbol-before-amount",
            "mail.cooldown"
    );

    /** Legacy sections this Allium reads at the same paths. */
    static final List<String> SAME = List.of("enable-chat-formatting", "chat", "chat-format", "chat-hover",
            "join-and-quit-messages", "creative-manager", "allow-unsafe-enchants", "redeem-settings", "end-gateway",
            "economy.currency-symbol", "economy.starting-balance", "nickname.updates-per-tick", "restore.enabled");

    /** Legacy path -> path in this Allium's config.yml. */
    static final Map<String, List<String>> RENAMED = ordered(
            "server-name", List.of("discord.server-name"),
            "discord.escalate_webhook", List.of("discord.channels.escalations.webhook"),
            "discord.escalate_mentions", List.of("discord.channels.escalations.mentions"),
            "discord.bot_name", List.of("discord.webhook-name"),
            "discord.bot_avatar", List.of("discord.webhook-avatar"),
            "teleport.delay", List.of("teleport.warmup-seconds"),
            "teleport.expire", List.of("teleport.request-expiry-seconds"),
            "teleport.cooldown", List.of("teleport.cooldowns.tpa", "teleport.cooldowns.tpahere"),
            "home.cooldown", List.of("teleport.cooldowns.home"),
            "handcuffs.commands-on-quit", List.of("restraints.commands-on-quit"),
            "restore.snapshot-retention-days", List.of("restore.retention-days"),
            "addons.enabled.party-locator", List.of("addons.enabled.partylocator"));

    /** Legacy sections that another file or an addon owns now. */
    static final Map<String, String> ELSEWHERE = ordered(
            "auto-restart", "autorestart.yml",
            "explode-command", "explosions.yml",
            "creeper-explosion-regen-speed", "explosions.yml",
            "modguard", "modguard.yml (ModGuard reads it from " + SOURCE + " on its first start)",
            "ore-generation", "the Ores addon (it reads " + SOURCE + " on its first start)",
            "pregen", "the Ores addon (it reads " + SOURCE + " on its first start)",
            "party-manager", "the PartyLocator addon (it reads " + SOURCE + " on its first start)",
            "spawnercraft", "the SpawnerCraft addon's config.yml");

    /** Legacy settings with no counterpart here. */
    static final Map<String, String> RETIRED = ordered(
            "version", "legacy's own version stamp",
            "debug-mode", "debug logging is configured per module",
            "debug-scheduler", "debug logging is configured per module",
            "group_migration_completed", "a legacy upgrade marker",
            "player_migration_completed", "a legacy upgrade marker",
            "update-checker", "there is no update checker",
            "discord-moderation", "Discord moderation commands are not part of Allium",
            "maintenance", "maintenance mode is not part of Allium",
            "anti-bot", "the command-probe ban is not part of Allium",
            "dialog", "join dialogs are not part of Allium",
            "block-unicode", "chat does not block Unicode",
            "player-colors", "per-player name colours come from nicknames",
            "commands", "visible commands are set in hide.yml",
            "heal.cooldown", "/heal has no cooldown",
            "feed.cooldown", "/feed has no cooldown",
            "home.show-location", "/home always shows the destination",
            "home.spawn-if-no-home", "teleport.respawn-at-home decides where players respawn",
            "teleport.tppet-radius", "/tppet takes pets by leash and ownership; see teleport.entity-limits",
            "economy.allow-negative-balance", "balances never go below zero",
            "economy.decimal-places", "amounts always have two decimals",
            "economy.space-between", "economy.currency-symbol is written as it is configured",
            "world-defaults", "set gamerules per world with /gamerule",
            "nickname.sync-to-essentials", "Allium replaces Essentials' nicknames",
            "lang", "settings.locale",
            "addons.enabled.modguard", "ModGuard is a built-in module under modules.modguard",
            "addons.enabled.custom-items", "custom items are built into the items module and addons");

    private LegacyConfigMigration() {}

    static YamlConfiguration read(Path path) throws IOException, InvalidConfigurationException {
        var config = new YamlConfiguration();
        config.load(path.toFile());
        return config;
    }

    /** A config.yml written by legacy Allium (and not yet carried over). */
    static boolean legacy(ConfigurationSection config) {
        return config.isSet("version") && !config.isSet("config-version") && (config.isConfigurationSection("chat")
                || config.isConfigurationSection("auto-restart") || config.isConfigurationSection("redeem-settings"));
    }

    /**
     * Known legacy top-level sections and nested legacy leaf paths still present in a config.yml
     * that was already on a recode layout (regardless of whether any top-level legacy section exists).
     */
    static List<String> leftovers(ConfigurationSection config, ConfigurationSection defaults) {
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        java.util.Set<String> topSections = new java.util.LinkedHashSet<>();
        for (String key : config.getKeys(false)) {
            if (defaults.isSet(key)) continue;
            if (ELSEWHERE.containsKey(key) || RETIRED.containsKey(key) || RENAMED.containsKey(key)
                    || RENAMED.keySet().stream().anyMatch(path -> path.startsWith(key + "."))
                    || RETIRED.keySet().stream().anyMatch(path -> path.startsWith(key + "."))
                    || NESTED_SPECIAL.stream().anyMatch(path -> path.startsWith(key + "."))) {
                found.add(key);
                topSections.add(key);
            }
        }
        // Always scan known nested legacy leaf paths even when their parent section exists in defaults.
        for (String path : nestedLegacyPaths()) {
            String top = path.split("\\.", 2)[0];
            if (topSections.contains(top)) continue;
            if (config.isSet(path) && !defaults.isSet(path)) {
                found.add(path);
            }
        }
        return List.copyOf(found);
    }

    /** Copies all top-level and nested leftover legacy paths from {@code current} into a fresh source config. */
    static YamlConfiguration extractLeftovers(ConfigurationSection current, ConfigurationSection defaults) {
        YamlConfiguration source = new YamlConfiguration();
        for (String path : leftovers(current, defaults)) {
            copyPath(current, source, path);
        }
        return source;
    }

    private static void copyPath(ConfigurationSection from, YamlConfiguration to, String path) {
        if (from.isConfigurationSection(path)) {
            ConfigurationSection section = from.getConfigurationSection(path);
            if (section == null) return;
            var keys = section.getKeys(true);
            if (keys.isEmpty()) {
                to.createSection(path);
                return;
            }
            for (String sub : keys) {
                if (section.isConfigurationSection(sub) && !section.getConfigurationSection(sub).getKeys(false).isEmpty()) {
                    continue;
                }
                to.set(path + "." + sub, section.get(sub));
            }
            return;
        }
        to.set(path, from.get(path));
    }

    private static List<String> nestedLegacyPaths() {
        List<String> paths = new ArrayList<>();
        for (String path : RENAMED.keySet()) {
            if (path.contains(".")) paths.add(path);
        }
        for (String path : RETIRED.keySet()) {
            if (path.contains(".")) paths.add(path);
        }
        paths.addAll(NESTED_SPECIAL);
        return paths;
    }

    static void backup(Path file, Path backup) throws IOException {
        if (!Files.exists(backup)) Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
    }

    /** Where each legacy setting goes. {@code defaults} is this Allium's bundled config.yml. */
    static Plan plan(ConfigurationSection legacy, ConfigurationSection defaults) {
        List<Move> moves = new ArrayList<>();
        List<Delegated> delegated = new ArrayList<>();
        List<Retired> retired = new ArrayList<>();
        for (String path : legacy.getKeys(true)) {
            if (legacy.isConfigurationSection(path) && !legacy.getConfigurationSection(path).getKeys(false).isEmpty()) continue;
            if (path.equals("config-version")) continue;
            Object value = legacy.get(path);
            if (underAny(path, SAME)) {
                moves.add(new Move(path, "config.yml", path, value));
            } else if (RENAMED.containsKey(path)) {
                for (String target : RENAMED.get(path)) moves.add(new Move(path, "config.yml", target, value));
            } else if (path.equals("lang")) {
                moves.add(new Move(path, "config.yml", "settings.locale", locale(String.valueOf(value))));
            } else if (path.equals("give.drop-overflow-items")) {
                moves.add(new Move(path, "config.yml", "items.give.overflow", legacy.getBoolean(path) ? "drop" : "partial"));
            } else if (path.equals("give.max-items-dropped")) {
                retired.add(new Retired(path, "/give drops whatever does not fit; see items.give.overflow"));
            } else if (path.equals("economy.symbol-before-amount")) {
                moves.add(new Move(path, "config.yml", "economy.currency-symbol-suffix", !legacy.getBoolean(path)));
            } else if (path.equals("mail.cooldown")) {
                // One mail per cooldown is legacy's limit, in the rate limit's terms.
                moves.add(new Move(path, "config.yml", "mail.rate-limit.sends", 1));
                moves.add(new Move(path, "config.yml", "mail.rate-limit.per-seconds", Math.max(1, legacy.getInt(path))));
            } else if (path.startsWith("auto-restart.")) {
                moves.add(new Move(path, "autorestart.yml", path.substring("auto-restart.".length()), value));
            } else if (path.startsWith("explode-command.")) {
                String key = path.substring("explode-command.".length());
                String target = switch (key) {
                    case "default" -> "explode.default-power";
                    case "max" -> "explode.max-power";
                    case "min" -> "explode.min-power";
                    default -> null;
                };
                if (target == null) retired.add(new Retired(path, "/explode has no such setting"));
                else moves.add(new Move(path, "explosions.yml", target, value));
            } else if (path.equals("creeper-explosion-regen-speed")) {
                // Legacy's speed 0 turned creeper regeneration off; any speed turned it on.
                moves.add(new Move(path, "explosions.yml", "regeneration.sources.creeper", legacy.getInt(path) > 0));
            } else if (topIn(path, ELSEWHERE)) {
                delegated.add(new Delegated(path, ELSEWHERE.get(path.split("\\.", 2)[0])));
            } else if (RETIRED.containsKey(path) || topIn(path, RETIRED)) {
                String key = RETIRED.containsKey(path) ? path : path.split("\\.", 2)[0];
                retired.add(new Retired(path, RETIRED.get(key)));
            } else if (defaults.isSet(path) && !defaults.isConfigurationSection(path)) {
                moves.add(new Move(path, "config.yml", path, value));
            } else {
                retired.add(new Retired(path, "not a setting of this Allium"));
            }
        }
        return new Plan(List.copyOf(moves), List.copyOf(delegated), List.copyOf(retired));
    }

    /**
     * Writes {@code moves} into {@code target}, keeping any value someone has already changed from
     * {@code defaults} (pass null to overwrite, as on a first load).
     */
    static Result apply(List<Move> moves, YamlConfiguration target, ConfigurationSection defaults) {
        var changed = new ArrayList<String>();
        var kept = new ArrayList<String>();
        var equal = new ArrayList<String>();
        var outcomes = new ArrayList<AppliedMove>();
        for (Move move : moves) {
            MoveOutcome outcome = write(target, defaults, move.path(), move.value(), changed, kept, equal);
            outcomes.add(new AppliedMove(move, outcome));
        }
        return new Result(List.copyOf(changed), List.copyOf(kept), List.copyOf(equal), List.copyOf(outcomes));
    }

    /** The truthful report of a carry-over, recording actual applied/equal/conflict-kept, delegated, and retired outcomes. */
    static String report(Plan plan, List<AppliedMove> appliedMoves, String header) {
        StringBuilder out = new StringBuilder("# ").append(header).append('\n')
                .append("# The legacy file is plugins/Allium/").append(SOURCE).append("; nothing in it was changed.\n");
        for (AppliedMove entry : appliedMoves) {
            Move move = entry.move();
            boolean same = move.file().equals("config.yml") && move.path().equals(move.legacyPath());
            String dest = (move.file().equals("config.yml") ? "" : move.file() + " ") + move.path();
            switch (entry.outcome()) {
                case APPLIED -> {
                    out.append("applied       ").append(move.legacyPath());
                    if (!same) out.append(" -> ").append(dest);
                    out.append('\n');
                }
                case EQUAL -> {
                    out.append("equal         ").append(move.legacyPath());
                    if (!same) out.append(" -> ").append(dest);
                    out.append(" (already equal)\n");
                }
                case CONFLICT_KEPT -> {
                    out.append("conflict-kept ").append(move.legacyPath());
                    if (!same) out.append(" -> ").append(dest);
                    out.append(" (kept existing customized value)\n");
                }
            }
        }
        for (Delegated delegated : plan.delegated()) {
            out.append("delegated     ").append(delegated.legacyPath()).append(": owned by ").append(delegated.owner()).append('\n');
        }
        for (Retired retired : plan.retired()) {
            out.append("retired       ").append(retired.legacyPath()).append(": ").append(retired.reason()).append('\n');
        }
        return out.toString();
    }

    /** Removes the legacy settings a plan carried away, and the sections they leave empty. */
    static void removeLegacy(YamlConfiguration config, Plan plan, ConfigurationSection defaults) {
        for (Move move : plan.moves()) {
            if (!move.legacyPath().equals(move.path()) && !defaults.isSet(move.legacyPath())) config.set(move.legacyPath(), null);
        }
        for (Delegated delegated : plan.delegated()) {
            if (!defaults.isSet(delegated.legacyPath())) config.set(delegated.legacyPath(), null);
        }
        for (Retired retired : plan.retired()) {
            if (!defaults.isSet(retired.legacyPath())) config.set(retired.legacyPath(), null);
        }
        prune(config, config);
    }

    private static void prune(YamlConfiguration root, ConfigurationSection section) {
        for (String key : section.getKeys(false)) {
            if (!section.isConfigurationSection(key)) continue;
            ConfigurationSection child = section.getConfigurationSection(key);
            prune(root, child);
            if (child.getKeys(false).isEmpty()) section.set(key, null);
        }
    }

    static String locale(String legacy) {
        return switch (legacy.toLowerCase(java.util.Locale.ROOT)) {
            case "en", "en_us" -> "en_US";
            case "es", "es_es" -> "es_ES";
            default -> legacy.matches("[a-z]{2}_[A-Z]{2}") ? legacy : "en_US";
        };
    }

    private static boolean underAny(String path, List<String> roots) {
        for (String root : roots) if (path.equals(root) || path.startsWith(root + ".")) return true;
        return false;
    }

    private static boolean topIn(String path, Map<String, String> sections) {
        return sections.containsKey(path.split("\\.", 2)[0]);
    }

    private static MoveOutcome write(YamlConfiguration target, ConfigurationSection defaults, String path, Object value,
                                     List<String> changed, List<String> kept, List<String> equal) {
        Object previous = target.get(path);
        if (Objects.equals(previous, value)) {
            equal.add(path);
            return MoveOutcome.EQUAL;
        }
        for (int separator = path.indexOf('.'); separator != -1; separator = path.indexOf('.', separator + 1)) {
            String parent = path.substring(0, separator);
            if (target.isSet(parent) && !target.isConfigurationSection(parent)) {
                kept.add(path);
                return MoveOutcome.CONFLICT_KEPT;
            }
        }
        if (defaults != null && previous != null && !(previous instanceof ConfigurationSection)
                && !Objects.equals(previous, defaults.get(path))) {
            kept.add(path);
            return MoveOutcome.CONFLICT_KEPT;
        }
        target.set(path, value);
        changed.add(path);
        return MoveOutcome.APPLIED;
    }

    private static <V> Map<String, V> ordered(Object... pairs) {
        Map<String, V> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked") V value = (V) pairs[i + 1];
            map.put((String) pairs[i], value);
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
