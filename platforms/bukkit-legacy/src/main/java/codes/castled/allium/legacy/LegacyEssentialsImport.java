package codes.castled.allium.legacy;

import codes.castled.allium.portable.Homes;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java 8 / 1.12.2-1.19.4 implementation of {@code /allium import essentials [apply <run>]}.
 *
 * <p>A dry run reads {@code plugins/Essentials} (or the folder configured in
 * {@code plugins/Allium/import/essentials.yml}) read-only and writes {@code plan.json},
 * {@code records.jsonl}, and {@code summary.txt} under
 * {@code plugins/Allium/import/essentials/<run>/}. Applying requires that run ID, an empty server
 * (logins are refused while apply is running), an unapplied run, and matching {@code sourceSha256}
 * and {@code planSha256} fingerprints.
 */
final class LegacyEssentialsImport implements Listener {

    static final String PERMISSION = "allium.import";
    static final String SETTINGS_REL = "import/essentials.yml";
    private static final Pattern RUN_ID_PATTERN = Pattern.compile("essentials-[0-9]{8}-[0-9]{6}(-[0-9]{1,3})?");
    private static final Pattern SIMPLE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern ACCOUNT_NAME = Pattern.compile("\\.?[A-Za-z0-9_]{1,16}");
    private static final Pattern JSON_STRING_FIELD = Pattern.compile("\"([a-zA-Z0-9_]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern JSON_LONG_FIELD = Pattern.compile("\"([a-zA-Z0-9_]+)\"\\s*:\\s*(-?[0-9]+)");
    private static final double COORD_EPSILON = 0.01;
    private static final double ANGLE_EPSILON = 0.5;
    private static final int MAX_KIT_ITEMS = 36;
    private static final long MAX_KIT_COOLDOWN_SECONDS = 365L * 86400L;
    private static final Set<String> KNOWN_SIGNS = new HashSet<String>(Arrays.asList(
            "balance", "buy", "sell", "free", "disposal", "heal", "kit", "mail", "repair",
            "spawnmob", "time", "trade", "warp", "weather", "enchant", "gamemode", "info"
    ));

    enum Domain {
        WARP, JAIL, SPAWN, KIT, WORTH,
        NAME, BALANCE, HOME, LOGOUT, MAIL, NICKNAME, IGNORE, PREFERENCE, MUTE, JAILED, KIT_USAGE
    }

    enum Status {
        IMPORT, PRESENT, REPLAYED, CONFLICT, BLOCKED, SKIPPED
    }

    private final JavaPlugin plugin;
    private final LegacyMessageManager messages;
    private final Runnable reloadAfterApply;
    private final boolean inlineExecution;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean applying = false;

    LegacyEssentialsImport(JavaPlugin plugin, LegacyMessageManager messages, Runnable reloadAfterApply) {
        this(plugin, messages, reloadAfterApply, false);
    }

    LegacyEssentialsImport(JavaPlugin plugin, LegacyMessageManager messages, Runnable reloadAfterApply,
                           boolean inlineExecution) {
        this.plugin = plugin;
        this.messages = messages;
        this.reloadAfterApply = reloadAfterApply;
        this.inlineExecution = inlineExecution;
    }

    boolean isRunning() {
        return running.get();
    }

    boolean isApplying() {
        return applying;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        if (applying) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    ChatColor.RED + "Server is importing data. Please try again in a moment.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (applying) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    ChatColor.RED + "Server is importing data. Please try again in a moment.");
        }
    }

    boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION) && !sender.isOp()) {
            messages.send(sender, "no-permission",
                    "{prefix} &cYou don't have permission to use &f/{cmd}&c.", "{cmd}", "allium import");
            return true;
        }
        if (args.length < 1 || !"essentials".equalsIgnoreCase(args[0])) {
            LegacyUsageCards.invalid(sender, "/allium import essentials [apply <run>]");
            return true;
        }
        if (args.length == 1) {
            start(sender, null);
            return true;
        }
        if (args.length == 3 && "apply".equalsIgnoreCase(args[1])) {
            String runId = args[2];
            if (!RUN_ID_PATTERN.matcher(runId).matches()) {
                refuse(sender, "unknown run '" + runId + "'");
                return true;
            }
            start(sender, runId);
            return true;
        }
        LegacyUsageCards.invalid(sender, "/allium import essentials [apply <run>]");
        return true;
    }

    List<String> complete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION) && !sender.isOp()) {
            return Collections.emptyList();
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            return "essentials".startsWith(prefix) ? Collections.singletonList("essentials") : Collections.<String>emptyList();
        }
        if (args.length == 2 && "essentials".equalsIgnoreCase(args[0])) {
            return "apply".startsWith(prefix) ? Collections.singletonList("apply") : Collections.<String>emptyList();
        }
        if (args.length == 3 && "essentials".equalsIgnoreCase(args[0]) && "apply".equalsIgnoreCase(args[1])) {
            return listDryRunIds(prefix);
        }
        return Collections.emptyList();
    }

    private List<String> listDryRunIds(String prefix) {
        Path root = importRoot();
        if (!Files.isDirectory(root)) return Collections.emptyList();
        List<String> out = new ArrayList<String>();
        DirectoryStream<Path> stream = null;
        try {
            stream = Files.newDirectoryStream(root);
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) continue;
                String name = entry.getFileName().toString();
                if (RUN_ID_PATTERN.matcher(name).matches()
                        && Files.isRegularFile(entry.resolve("plan.json"))
                        && name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(name);
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) {}
            }
        }
        Collections.sort(out, Collections.reverseOrder());
        return out;
    }

    private void start(final CommandSender sender, final String applyRun) {
        if (applyRun == null && applying) {
            refuse(sender, "an import apply is already running");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            refuse(sender, "another Essentials import is running");
            return;
        }
        final Settings settings;
        try {
            settings = loadSettings();
        } catch (IOException | RuntimeException invalid) {
            running.set(false);
            refuse(sender, SETTINGS_REL + ": " + firstLine(invalid.getMessage()));
            return;
        }
        if (applyRun != null) {
            if (!plugin.getServer().getOnlinePlayers().isEmpty()) {
                running.set(false);
                refuse(sender, "players are online; apply runs on an empty server (logins are refused while it runs)");
                return;
            }
            applying = true;
        }
        final WorldIndex worlds = WorldIndex.capture(plugin.getServer(), settings.worldMappings);
        messages.send(sender, "core.import.essentials-started",
                "{prefix} &7Reading Essentials data for {mode}...",
                "{mode}", applyRun == null ? "a dry run" : "apply " + applyRun);

        Runnable work = new Runnable() {
            @Override
            public void run() {
                try {
                    if (applyRun == null) {
                        runDry(sender, settings, worlds);
                    } else {
                        runApply(sender, settings, worlds, applyRun);
                    }
                } catch (Exception | LinkageError failure) {
                    String reason = failure.getMessage() == null
                            ? failure.getClass().getSimpleName()
                            : firstLine(failure.getMessage());
                    plugin.getLogger().warning("Essentials import failed: " + reason);
                    tell(sender, "core.import.essentials-failed",
                            "{prefix} &cEssentials import failed: &f{reason}", "{reason}", reason);
                } finally {
                    applying = false;
                    running.set(false);
                }
            }
        };
        if (inlineExecution) {
            work.run();
        } else {
            try {
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, work);
            } catch (RuntimeException unavailable) {
                work.run();
            }
        }
    }

    private void runDry(CommandSender sender, Settings settings, WorldIndex worlds) throws Exception {
        String runId = newRunId();
        Path dir = runDir(runId);
        long now = System.currentTimeMillis();
        Snapshot snapshot = readSnapshot(settings.source);
        Destination dest = readDestination();
        Plan plan = buildPlan(snapshot, worlds, dest, now);
        Files.createDirectories(dir);
        writePlanJson(dir.resolve("plan.json"), runId, settings, plan, now);
        writeRecordsJsonl(dir.resolve("records.jsonl"), plan);
        writeSummaryTxt(dir.resolve("summary.txt"), runId, settings, plan, null);

        plugin.getLogger().info("Essentials import dry run " + runId + ": "
                + plan.count(Status.IMPORT) + " to import, "
                + plan.count(Status.PRESENT) + " present, "
                + plan.count(Status.CONFLICT) + " conflicts, "
                + plan.count(Status.BLOCKED) + " blocked. Reports: " + relative(dir));

        tell(sender, "core.import.essentials-dry-run",
                "{prefix} &aEssentials dry run &f{run}&a: &f{import} &ato import, &f{present} &apresent, &f{conflicts} &aconflicts, &f{blocked} &ablocked. Review &f{summary}&a, then run &f/allium import essentials apply {run}&a.",
                "{run}", runId,
                "{import}", Long.toString(plan.count(Status.IMPORT)),
                "{present}", Long.toString(plan.count(Status.PRESENT)),
                "{conflicts}", Long.toString(plan.count(Status.CONFLICT)),
                "{blocked}", Long.toString(plan.count(Status.BLOCKED)),
                "{summary}", relative(dir.resolve("summary.txt")));
    }

    private void runApply(CommandSender sender, Settings settings, WorldIndex worlds, String runId) throws Exception {
        Path dir = runDir(runId);
        Path planFile = dir.resolve("plan.json");
        if (!Files.isRegularFile(planFile)) {
            tell(sender, "core.import.essentials-refused",
                    "{prefix} &cEssentials import refused: &f{reason}",
                    "{reason}", "dry run '" + runId + "' not found (" + relative(planFile) + ")");
            return;
        }
        Ledger ledger = readLedger();
        if (ledger.appliedRuns.contains(runId)) {
            tell(sender, "core.import.essentials-refused",
                    "{prefix} &cEssentials import refused: &f{reason}",
                    "{reason}", "run '" + runId + "' was already applied");
            return;
        }
        ReviewedPlan reviewed = parseReviewedPlan(planFile);
        if (reviewed == null || !runId.equals(reviewed.runId)) {
            tell(sender, "core.import.essentials-refused",
                    "{prefix} &cEssentials import refused: &f{reason}",
                    "{reason}", "invalid or corrupted plan.json for '" + runId + "'");
            return;
        }
        Snapshot snapshot = readSnapshot(settings.source);
        if (!reviewed.sourceSha256.equals(snapshot.sourceSha256)) {
            tell(sender, "core.import.essentials-refused",
                    "{prefix} &cEssentials import refused: &f{reason}",
                    "{reason}", "Essentials files changed since " + runId + "; run a new dry run");
            return;
        }
        Destination dest = readDestination();
        Plan plan = buildPlan(snapshot, worlds, dest, reviewed.plannedAt);
        if (!reviewed.planSha256.equals(plan.planSha256)) {
            tell(sender, "core.import.essentials-refused",
                    "{prefix} &cEssentials import refused: &f{reason}",
                    "{reason}", "the plan differs from " + runId + "; run a new dry run");
            return;
        }
        ApplyResult result = applyPlan(plan, ledger, runId);
        writeApplyJsonl(dir.resolve("apply.jsonl"), result);
        writeSummaryTxt(dir.resolve("apply-summary.txt"), runId, settings, plan, result);

        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                if (reloadAfterApply != null) {
                    reloadAfterApply.run();
                }
            }
        });

        plugin.getLogger().info("Essentials import apply " + runId + ": "
                + result.applied + " applied, " + result.adopted + " adopted, " + result.conflicts + " conflicts.");

        tell(sender, "core.import.essentials-applied",
                "{prefix} &aEssentials import &f{run} &aapplied: &f{applied} &aapplied, &f{adopted} &aadopted, &f{conflicts} &aconflicts. Details: &f{summary}&a.",
                "{run}", runId,
                "{applied}", Long.toString(result.applied),
                "{adopted}", Long.toString(result.adopted),
                "{conflicts}", Long.toString(result.conflicts),
                "{summary}", relative(dir.resolve("apply-summary.txt")));
    }

    // ==================== Settings & Paths ====================

    private Path importRoot() {
        return plugin.getDataFolder().toPath().resolve("import").resolve("essentials");
    }

    private Path runDir(String runId) {
        return importRoot().resolve(runId);
    }

    private Path ledgerPath() {
        return importRoot().resolve("ledger-legacy.yml");
    }

    private String relative(Path file) {
        Path dataDir = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path abs = file.toAbsolutePath().normalize();
        if (abs.startsWith(dataDir)) {
            return dataDir.relativize(abs).toString().replace('\\', '/');
        }
        return abs.toString().replace('\\', '/');
    }

    private Settings loadSettings() throws IOException {
        Path dataDir = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path defaultSource = dataDir.getParent() != null
                ? dataDir.getParent().resolve("Essentials")
                : dataDir.resolve("Essentials");
        Path file = dataDir.resolve("import").resolve("essentials.yml");
        Map<String, String> worldMappings = new LinkedHashMap<String, String>();
        Path source = defaultSource;
        if (Files.isRegularFile(file)) {
            YamlConfiguration yml = new YamlConfiguration();
            try {
                yml.loadFromString(readUtf8(file));
            } catch (InvalidConfigurationException e) {
                throw new IOException(firstLine(e.getMessage()), e);
            }
            String rawSource = trimToNull(yml.getString("source"));
            if (rawSource != null) {
                Path p = new File(rawSource).toPath();
                source = p.isAbsolute() ? p.normalize() : dataDir.resolve(p).normalize();
            }
            ConfigurationSection worldsSec = yml.getConfigurationSection("worlds");
            if (worldsSec != null) {
                for (String key : worldsSec.getKeys(false)) {
                    String val = trimToNull(worldsSec.getString(key));
                    if (val != null) {
                        worldMappings.put(key.trim().toLowerCase(Locale.ROOT), val);
                    }
                }
            }
        }
        return new Settings(source, worldMappings);
    }

    private String newRunId() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        String base = "essentials-" + fmt.format(new Date());
        if (!Files.exists(runDir(base))) return base;
        for (int i = 1; i <= 999; i++) {
            String candidate = base + "-" + i;
            if (!Files.exists(runDir(candidate))) return candidate;
        }
        return base + "-" + (System.currentTimeMillis() % 1000L);
    }

    // ==================== Read-Only Essentials Snapshot ====================

    private static Snapshot readSnapshot(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Essentials directory not found: " + root);
        }
        MessageDigest digest = sha256();
        List<UserFile> users = new ArrayList<UserFile>();
        List<SkippedFile> skippedUsers = new ArrayList<SkippedFile>();
        List<WarpFile> warps = new ArrayList<WarpFile>();
        List<JailEntry> jails = new ArrayList<JailEntry>();
        List<SpawnEntry> spawns = new ArrayList<SpawnEntry>();
        List<RawKit> kits = new ArrayList<RawKit>();
        List<RawWorth> worth = new ArrayList<RawWorth>();
        List<String> enabledSigns = new ArrayList<String>();
        List<String> notes = new ArrayList<String>();

        Path userdataDir = root.resolve("userdata");
        if (Files.isDirectory(userdataDir)) {
            for (Path file : sortedYml(userdataDir)) {
                String name = file.getFileName().toString();
                byte[] raw = Files.readAllBytes(file);
                hashFile(digest, "userdata/" + name, raw);
                String stem = name.substring(0, name.length() - 4);
                UUID uuid = parseUuid(stem);
                if (uuid == null) {
                    skippedUsers.add(new SkippedFile(name, "non-UUID filename (needs explicit UUID map; never guessed from name)"));
                    continue;
                }
                YamlConfiguration yaml = new YamlConfiguration();
                try {
                    yaml.loadFromString(new String(raw, StandardCharsets.UTF_8));
                } catch (InvalidConfigurationException malformed) {
                    skippedUsers.add(new SkippedFile(name, "malformed YAML: " + firstLine(malformed.getMessage())));
                    continue;
                }
                users.add(parseUser(name, uuid, yaml));
            }
        } else {
            notes.add("No userdata/ directory found under " + root);
        }

        Path warpsDir = root.resolve("warps");
        if (Files.isDirectory(warpsDir)) {
            for (Path file : sortedYml(warpsDir)) {
                String name = file.getFileName().toString();
                byte[] raw = Files.readAllBytes(file);
                hashFile(digest, "warps/" + name, raw);
                String stem = name.substring(0, name.length() - 4);
                YamlConfiguration yaml = new YamlConfiguration();
                try {
                    yaml.loadFromString(new String(raw, StandardCharsets.UTF_8));
                } catch (InvalidConfigurationException malformed) {
                    warps.add(new WarpFile(name, stem, null, "malformed YAML: " + firstLine(malformed.getMessage())));
                    continue;
                }
                String warpName = trimToNull(yaml.getString("name"));
                if (warpName == null) warpName = stem;
                ParsedLocation loc = parseLocation(yaml, "");
                warps.add(new WarpFile(name, warpName, loc.location, loc.errorIn("warps/" + name)));
            }
        }

        Path jailsFile = Files.isRegularFile(root.resolve("jail.yml"))
                ? root.resolve("jail.yml") : root.resolve("jails.yml");
        if (Files.isRegularFile(jailsFile)) {
            byte[] raw = Files.readAllBytes(jailsFile);
            hashFile(digest, jailsFile.getFileName().toString(), raw);
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(new String(raw, StandardCharsets.UTF_8));
                ConfigurationSection section = yaml.getConfigurationSection("jails");
                if (section != null) {
                    for (String jailName : sortedKeys(section)) {
                        ParsedLocation loc = parseLocation(yaml, "jails." + jailName);
                        jails.add(new JailEntry(jailName, loc.location, loc.errorIn(jailsFile.getFileName().toString())));
                    }
                }
            } catch (InvalidConfigurationException malformed) {
                notes.add(jailsFile.getFileName() + " could not be parsed: " + firstLine(malformed.getMessage()));
            }
        }

        Path spawnFile = root.resolve("spawn.yml");
        if (Files.isRegularFile(spawnFile)) {
            byte[] raw = Files.readAllBytes(spawnFile);
            hashFile(digest, "spawn.yml", raw);
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(new String(raw, StandardCharsets.UTF_8));
                ConfigurationSection section = yaml.getConfigurationSection("spawns");
                if (section != null) {
                    for (String group : sortedKeys(section)) {
                        ParsedLocation loc = parseLocation(yaml, "spawns." + group);
                        spawns.add(new SpawnEntry(group, loc.location, loc.errorIn("spawn.yml")));
                    }
                }
            } catch (InvalidConfigurationException malformed) {
                notes.add("spawn.yml could not be parsed: " + firstLine(malformed.getMessage()));
            }
        }

        Path configFile = root.resolve("config.yml");
        YamlConfiguration configYaml = null;
        if (Files.isRegularFile(configFile)) {
            byte[] raw = Files.readAllBytes(configFile);
            hashFile(digest, "config.yml", raw);
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(new String(raw, StandardCharsets.UTF_8));
                configYaml = yaml;
                for (String sign : yaml.getStringList("enabledSigns")) {
                    String s = trimToNull(sign);
                    if (s != null) enabledSigns.add(s.toLowerCase(Locale.ROOT));
                }
            } catch (InvalidConfigurationException malformed) {
                notes.add("config.yml could not be parsed: " + firstLine(malformed.getMessage()));
            }
        }

        Path kitsFile = root.resolve("kits.yml");
        if (Files.isRegularFile(kitsFile)) {
            byte[] raw = Files.readAllBytes(kitsFile);
            hashFile(digest, "kits.yml", raw);
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(new String(raw, StandardCharsets.UTF_8));
                parseKitsSection(yaml.getConfigurationSection("kits"), kits);
            } catch (InvalidConfigurationException malformed) {
                notes.add("kits.yml could not be parsed: " + firstLine(malformed.getMessage()));
            }
        } else if (configYaml != null && configYaml.isConfigurationSection("kits")) {
            parseKitsSection(configYaml.getConfigurationSection("kits"), kits);
        }

        Path worthFile = root.resolve("worth.yml");
        if (Files.isRegularFile(worthFile)) {
            byte[] raw = Files.readAllBytes(worthFile);
            hashFile(digest, "worth.yml", raw);
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(new String(raw, StandardCharsets.UTF_8));
                parseWorthSection(yaml.getConfigurationSection("worth"), worth);
            } catch (InvalidConfigurationException malformed) {
                notes.add("worth.yml could not be parsed: " + firstLine(malformed.getMessage()));
            }
        }

        return new Snapshot(toHex(digest.digest()), users, skippedUsers, warps, jails, spawns, kits, worth, enabledSigns, notes);
    }

    private static void parseKitsSection(ConfigurationSection section, List<RawKit> out) {
        if (section == null) return;
        for (String kitName : sortedKeys(section)) {
            ConfigurationSection kitSec = section.getConfigurationSection(kitName);
            if (kitSec == null) {
                out.add(new RawKit(kitName, 0L, Collections.<String>emptyList(), "kits." + kitName + " must be a YAML section"));
                continue;
            }
            long delay = kitSec.getLong("delay", 0L);
            List<String> items = new ArrayList<String>();
            List<?> rawItems = kitSec.getList("items");
            if (rawItems != null) {
                for (Object elem : rawItems) {
                    if (elem != null) {
                        String line = String.valueOf(elem).trim();
                        if (!line.isEmpty()) items.add(line);
                    }
                }
            }
            out.add(new RawKit(kitName, delay, items, null));
        }
    }

    private static void parseWorthSection(ConfigurationSection section, List<RawWorth> out) {
        if (section == null) return;
        for (String key : sortedKeys(section)) {
            Object raw = section.get(key);
            if (raw instanceof ConfigurationSection) {
                ConfigurationSection sub = (ConfigurationSection) raw;
                Set<String> subKeys = sub.getKeys(false);
                if (subKeys.size() == 1) {
                    raw = sub.get(subKeys.iterator().next());
                } else {
                    raw = sub.get("*");
                }
            }
            if (raw != null) {
                out.add(new RawWorth(key, String.valueOf(raw).trim()));
            }
        }
    }

    private static UserFile parseUser(String fileName, UUID uuid, YamlConfiguration yaml) {
        List<String> errors = new ArrayList<String>();
        boolean npc = yaml.getBoolean("npc", false);
        String lastAccountName = trimToNull(yaml.getString("last-account-name"));
        if (lastAccountName == null) lastAccountName = trimToNull(yaml.getString("lastAccountName"));

        boolean moneyPresent = yaml.contains("money");
        String moneyRaw = null;
        if (moneyPresent) {
            Object raw = yaml.get("money");
            if (raw != null) moneyRaw = String.valueOf(raw).trim();
        }

        Map<String, RawLocation> homes = new LinkedHashMap<String, RawLocation>();
        ConfigurationSection homesSection = yaml.getConfigurationSection("homes");
        if (homesSection != null) {
            for (String homeName : sortedKeys(homesSection)) {
                RawLocation loc = readUserLocation(yaml, "homes." + homeName, errors);
                if (loc != null) homes.put(homeName, loc);
            }
        }

        RawLocation logoutLoc = readUserLocation(yaml, "logoutlocation", errors);
        long logoutTs = yaml.getLong("timestamps.logout", 0L);
        List<RawMail> mail = parseMail(yaml, errors);
        String nickname = trimToNull(yaml.getString("nickname"));

        List<String> ignores = new ArrayList<String>();
        if (yaml.isList("ignore")) {
            List<?> rawList = yaml.getList("ignore");
            if (rawList != null) {
                for (Object item : rawList) {
                    if (item != null) ignores.add(String.valueOf(item).trim());
                }
            }
        }

        boolean muted = yaml.getBoolean("muted", false);
        String muteReason = trimToNull(yaml.getString("mute-reason"));
        if (muteReason == null) muteReason = trimToNull(yaml.getString("muteReason"));
        long muteTs = yaml.getLong("timestamps.mute", 0L);

        boolean jailed = yaml.getBoolean("jailed", false);
        String jailName = trimToNull(yaml.getString("jail"));
        long jailTs = yaml.getLong("timestamps.jail", 0L);
        long onlineJail = yaml.getLong("timestamps.onlinejail", 0L);

        Boolean acceptingPay = optionalBoolean(yaml, "accepting-pay", "acceptingPay", errors);
        Boolean teleportEnabled = optionalBoolean(yaml, "teleportenabled", "teleport-enabled", errors);
        Boolean teleportAuto = optionalBoolean(yaml, "teleportauto", "teleport-auto", errors);
        Boolean confirmPay = optionalBoolean(yaml, "confirm-pay", "confirmPay", errors);
        Boolean socialSpy = optionalBoolean(yaml, "socialspy", "social-spy", errors);
        Boolean msgToggle = optionalBoolean(yaml, "msgtoggle", "msg-toggle", errors);

        Map<String, Long> kitTimestamps = new LinkedHashMap<String, Long>();
        ConfigurationSection kitsTsSection = yaml.getConfigurationSection("timestamps.kits");
        if (kitsTsSection != null) {
            for (String kitKey : sortedKeys(kitsTsSection)) {
                long ts = kitsTsSection.getLong(kitKey, 0L);
                if (ts > 0L) {
                    kitTimestamps.put(kitKey.trim().toLowerCase(Locale.ROOT), ts);
                }
            }
        }

        return new UserFile(fileName, uuid, npc, lastAccountName, moneyRaw, moneyPresent,
                homes, logoutLoc, logoutTs, mail, nickname, ignores,
                muted, muteReason, muteTs, jailed, jailName, jailTs, onlineJail,
                acceptingPay, teleportEnabled, teleportAuto, confirmPay, socialSpy, msgToggle,
                kitTimestamps, errors);
    }

    private static List<RawMail> parseMail(YamlConfiguration yaml, List<String> errors) {
        List<?> rawList = yaml.getList("mail");
        if (rawList == null || rawList.isEmpty()) return Collections.emptyList();
        List<RawMail> out = new ArrayList<RawMail>();
        for (int i = 0; i < rawList.size(); i++) {
            Object entry = rawList.get(i);
            if (entry instanceof String) {
                String legacyText = ((String) entry).trim();
                if (!legacyText.isEmpty()) {
                    out.add(new RawMail(i, true, false, null, 0L, 0L, legacyText));
                }
            } else if (entry instanceof Map<?, ?>) {
                Map<?, ?> map = (Map<?, ?>) entry;
                boolean legacy = boolValue(map.get("legacy"), false);
                boolean read = boolValue(map.get("read"), false);
                String msg = map.get("message") == null ? null : trimToNull(String.valueOf(map.get("message")));
                String senderName = map.get("sender-name") == null ? null : trimToNull(String.valueOf(map.get("sender-name")));
                long ts = longValue(map.get("timestamp"), 0L);
                long expire = longValue(map.get("expire"), 0L);
                if (msg == null) {
                    errors.add("mail[" + i + "] has empty message");
                } else {
                    out.add(new RawMail(i, legacy, read, senderName, ts, expire, msg));
                }
            } else {
                errors.add("mail[" + i + "] has unsupported entry type");
            }
        }
        return out;
    }

    private static RawLocation readUserLocation(YamlConfiguration yaml, String path, List<String> errors) {
        if (!yaml.contains(path)) return null;
        ParsedLocation parsed = parseLocation(yaml, path);
        if (parsed.error != null) {
            errors.add(path + ": " + parsed.error);
            return null;
        }
        return parsed.location;
    }

    private static ParsedLocation parseLocation(YamlConfiguration yaml, String path) {
        ConfigurationSection sec = path.isEmpty() ? yaml : yaml.getConfigurationSection(path);
        if (sec == null) return new ParsedLocation(null, "missing location section");
        String world = trimToNull(sec.getString("world"));
        String worldName = trimToNull(sec.getString("world-name"));
        if (worldName == null) worldName = trimToNull(sec.getString("worldName"));
        if (world == null && worldName == null) {
            return new ParsedLocation(null, "missing world");
        }
        if (!sec.isSet("x") || !sec.isSet("y") || !sec.isSet("z")) {
            return new ParsedLocation(null, "missing x/y/z coordinates");
        }
        double x = sec.getDouble("x", Double.NaN);
        double y = sec.getDouble("y", Double.NaN);
        double z = sec.getDouble("z", Double.NaN);
        float yaw = (float) sec.getDouble("yaw", 0.0);
        float pitch = (float) sec.getDouble("pitch", 0.0);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || Math.abs(x) >= 30_000_000.0 || Math.abs(z) >= 30_000_000.0) {
            return new ParsedLocation(null, "out-of-bounds or non-finite coordinates");
        }
        return new ParsedLocation(new RawLocation(world, worldName, x, y, z, yaw, pitch), null);
    }

    private static Boolean optionalBoolean(YamlConfiguration yaml, String primary, String alt, List<String> errors) {
        String key = yaml.contains(primary) ? primary : (yaml.contains(alt) ? alt : null);
        if (key == null) return null;
        Object raw = yaml.get(key);
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof String) {
            String s = ((String) raw).trim().toLowerCase(Locale.ROOT);
            if ("true".equals(s)) return Boolean.TRUE;
            if ("false".equals(s)) return Boolean.FALSE;
        }
        errors.add(key + " is not a boolean");
        return null;
    }

    // ==================== Destination Reader ====================

    private Destination readDestination() throws IOException {
        File dataDir = plugin.getDataFolder();
        Ledger ledger = readLedger();

        // Warps
        Map<String, SavedLoc> warps = new HashMap<String, SavedLoc>();
        File warpsFile = new File(dataDir, "warps.yml");
        if (warpsFile.isFile()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(warpsFile);
            ConfigurationSection sec = yml.getConfigurationSection("warps");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    SavedLoc loc = readLegacyLoc(sec.getConfigurationSection(key));
                    if (loc != null) warps.put(key.toLowerCase(Locale.ROOT), loc);
                }
            }
        }

        // Spawn
        SavedLoc spawn = null;
        File spawnFile = new File(dataDir, "spawn.yml");
        if (spawnFile.isFile()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(spawnFile);
            spawn = readLegacyLoc(yml.getConfigurationSection("spawn"));
        }

        // Homes
        Map<UUID, Map<String, SavedLoc>> homes = new HashMap<UUID, Map<String, SavedLoc>>();
        Path homesDir = dataDir.toPath().resolve("homes");
        if (Files.isDirectory(homesDir)) {
            DirectoryStream<Path> stream = null;
            try {
                stream = Files.newDirectoryStream(homesDir, "*.properties");
                for (Path file : stream) {
                    String fn = file.getFileName().toString();
                    UUID owner = parseUuid(fn.substring(0, fn.length() - ".properties".length()));
                    if (owner == null) continue;
                    Properties props = new Properties();
                    Reader reader = null;
                    try {
                        reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                        props.load(reader);
                    } catch (IOException ignored) {
                        continue;
                    } finally {
                        if (reader != null) try { reader.close(); } catch (IOException ignored) {}
                    }
                    Map<String, SavedLoc> map = new HashMap<String, SavedLoc>();
                    for (String homeName : props.stringPropertyNames()) {
                        SavedLoc loc = decodeHomePosition(props.getProperty(homeName));
                        if (loc != null) map.put(homeName.toLowerCase(Locale.ROOT), loc);
                    }
                    homes.put(owner, map);
                }
            } finally {
                if (stream != null) try { stream.close(); } catch (IOException ignored) {}
            }
        }

        // Economy
        Map<UUID, BigDecimal> balances = new HashMap<UUID, BigDecimal>();
        Map<UUID, Boolean> payDisabled = new HashMap<UUID, Boolean>();
        Map<UUID, Boolean> payConfirm = new HashMap<UUID, Boolean>();
        File econFile = new File(dataDir, "economy-legacy.yml");
        if (econFile.isFile()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(econFile);
            ConfigurationSection accts = yml.getConfigurationSection("accounts");
            if (accts != null) {
                for (String key : accts.getKeys(false)) {
                    UUID id = parseUuid(key);
                    ConfigurationSection s = accts.getConfigurationSection(key);
                    if (id == null || s == null) continue;
                    if (s.contains("balance")) {
                        BigDecimal bal = LegacyCompat.parseMoneyAmount(s.getString("balance"));
                        if (bal != null) balances.put(id, bal);
                    }
                    if (s.isBoolean("pay-disabled")) payDisabled.put(id, s.getBoolean("pay-disabled"));
                    if (s.isBoolean("pay-confirm")) payConfirm.put(id, s.getBoolean("pay-confirm"));
                }
            }
        }

        // Worth
        Map<String, BigDecimal> worth = new HashMap<String, BigDecimal>();
        File worthFile = new File(dataDir, "worth.yml");
        if (worthFile.isFile()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(worthFile);
            ConfigurationSection sec = yml.getConfigurationSection("worth");
            if (sec == null) sec = yml.getConfigurationSection("prices");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    if (sec.isConfigurationSection(key)) continue;
                    double v = sec.getDouble(key, -1.0);
                    if (v > 0.0) {
                        Material m = LegacyCompat.matchMaterial(key);
                        String matKey = m != null ? m.name() : key.toUpperCase(Locale.ROOT);
                        worth.put(matKey, BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP));
                    }
                }
            }
        }

        // Kits & Cooldowns
        Map<String, Long> kitCooldownsDef = new HashMap<String, Long>();
        File kitsFile = new File(dataDir, "kits-legacy.yml");
        if (kitsFile.isFile()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(kitsFile);
            ConfigurationSection sec = yml.getConfigurationSection("kits");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    ConfigurationSection ks = sec.getConfigurationSection(key);
                    if (ks != null) kitCooldownsDef.put(key.toLowerCase(Locale.ROOT), ks.getLong("cooldown", 0L));
                }
            }
        }
        Map<String, Long> kitUsageUntil = new HashMap<String, Long>();
        File cdFile = new File(dataDir, "kit-cooldowns.yml");
        if (cdFile.isFile()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(cdFile);
            ConfigurationSection root = yml.getConfigurationSection("cooldowns");
            if (root != null) {
                for (String uuidStr : root.getKeys(false)) {
                    ConfigurationSection sub = root.getConfigurationSection(uuidStr);
                    if (sub == null) continue;
                    for (String kitKey : sub.getKeys(false)) {
                        kitUsageUntil.put(uuidStr + ":" + kitKey.toLowerCase(Locale.ROOT), sub.getLong(kitKey, 0L));
                    }
                }
            }
        }

        // Player info
        Map<UUID, String> names = new HashMap<UUID, String>();
        Map<UUID, String> nicknames = new HashMap<UUID, String>();
        Map<UUID, Set<UUID>> ignores = new HashMap<UUID, Set<UUID>>();
        Map<UUID, List<String>> mail = new HashMap<UUID, List<String>>();
        File infoFile = new File(dataDir, "player-info-legacy.yml");
        if (infoFile.isFile()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(infoFile);
            ConfigurationSection playersSec = yml.getConfigurationSection("players");
            if (playersSec != null) {
                for (String key : playersSec.getKeys(false)) {
                    UUID id = parseUuid(key);
                    ConfigurationSection s = playersSec.getConfigurationSection(key);
                    if (id == null || s == null) continue;
                    String n = trimToNull(s.getString("name"));
                    if (n != null) names.put(id, n);
                    String nick = trimToNull(s.getString("nick"));
                    if (nick != null) nicknames.put(id, nick);
                    List<String> ign = s.getStringList("ignores");
                    if (!ign.isEmpty()) {
                        Set<UUID> set = new HashSet<UUID>();
                        for (String u : ign) {
                            UUID parsed = parseUuid(u);
                            if (parsed != null) set.add(parsed);
                        }
                        if (!set.isEmpty()) ignores.put(id, set);
                    }
                    List<String> m = s.getStringList("mail");
                    if (!m.isEmpty()) mail.put(id, new ArrayList<String>(m));
                }
            }
        }

        // Player settings
        Map<UUID, Map<String, Boolean>> playerSettings = new HashMap<UUID, Map<String, Boolean>>();
        File settingsFile = new File(dataDir, "player-settings-legacy.yml");
        if (settingsFile.isFile()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(settingsFile);
            ConfigurationSection playersSec = yml.getConfigurationSection("players");
            if (playersSec != null) {
                for (String key : playersSec.getKeys(false)) {
                    UUID id = parseUuid(key);
                    ConfigurationSection s = playersSec.getConfigurationSection(key);
                    if (id == null || s == null) continue;
                    Map<String, Boolean> map = new HashMap<String, Boolean>();
                    for (String k : Arrays.asList("tpa", "msg", "tp-auto")) {
                        if (s.isBoolean(k)) map.put(k, s.getBoolean(k));
                    }
                    if (!map.isEmpty()) playerSettings.put(id, map);
                }
            }
        }

        // Logout locations
        Map<UUID, SavedLoc> logouts = new HashMap<UUID, SavedLoc>();
        File logoutFile = new File(dataDir, "logout-locations-legacy.yml");
        if (logoutFile.isFile()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(logoutFile);
            for (String key : yml.getKeys(false)) {
                UUID id = parseUuid(key);
                SavedLoc loc = readLegacyLoc(yml.getConfigurationSection(key));
                if (id != null && loc != null) logouts.put(id, loc);
            }
        }

        // Moderation (jails, mutes, jailed)
        Map<String, SavedLoc> jails = new HashMap<String, SavedLoc>();
        Map<UUID, MuteData> mutes = new HashMap<UUID, MuteData>();
        Map<UUID, JailedData> jailed = new HashMap<UUID, JailedData>();
        File modFile = new File(dataDir, "moderation-legacy.yml");
        if (modFile.isFile()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(modFile);
            ConfigurationSection jailSec = yml.getConfigurationSection("jails");
            if (jailSec != null) {
                for (String key : jailSec.getKeys(false)) {
                    SavedLoc loc = readLegacyLoc(jailSec.getConfigurationSection(key));
                    if (loc != null) jails.put(key.toLowerCase(Locale.ROOT), loc);
                }
            }
            ConfigurationSection muteSec = yml.getConfigurationSection("mutes");
            if (muteSec != null) {
                for (String key : muteSec.getKeys(false)) {
                    UUID id = parseUuid(key);
                    ConfigurationSection s = muteSec.getConfigurationSection(key);
                    if (id != null && s != null) {
                        mutes.put(id, new MuteData(s.getString("name", key), s.getLong("expires", 0L), s.getString("reason", "")));
                    }
                }
            }
            ConfigurationSection jailedSec = yml.getConfigurationSection("jailed");
            if (jailedSec != null) {
                for (String key : jailedSec.getKeys(false)) {
                    UUID id = parseUuid(key);
                    ConfigurationSection s = jailedSec.getConfigurationSection(key);
                    if (id != null && s != null) {
                        jailed.put(id, new JailedData(s.getString("name", key), s.getString("jail", ""), s.getLong("expires", 0L)));
                    }
                }
            }
        }

        return new Destination(ledger, warps, spawn, homes, balances, payDisabled, payConfirm, worth,
                kitCooldownsDef, kitUsageUntil, names, nicknames, ignores, mail, playerSettings, logouts,
                jails, mutes, jailed);
    }

    // ==================== Planner ====================

    private static Plan buildPlan(Snapshot snapshot, WorldIndex worlds, Destination dest, long now) {
        List<PlanRecord> records = new ArrayList<PlanRecord>();
        List<String> notes = new ArrayList<String>(snapshot.notes);
        int npcSkipped = 0;

        for (SkippedFile sf : snapshot.skippedUsers) {
            notes.add("Skipped userdata/" + sf.fileName + ": " + sf.reason);
        }

        // 1. Warps
        Set<String> seenWarps = new HashSet<String>();
        for (WarpFile wf : snapshot.warps) {
            String norm = wf.name == null ? "" : wf.name.trim().toLowerCase(Locale.ROOT);
            if (!SIMPLE_NAME.matcher(norm).matches()) {
                records.add(PlanRecord.simple(Domain.WARP, String.valueOf(wf.name), Status.BLOCKED,
                        "invalid warp name", hash("warp:bad:" + wf.fileName)));
                continue;
            }
            if (!seenWarps.add(norm)) {
                records.add(PlanRecord.simple(Domain.WARP, norm, Status.BLOCKED,
                        "duplicate normalized warp name", hash("warp:dup:" + wf.fileName)));
                continue;
            }
            if (wf.error != null || wf.location == null) {
                records.add(PlanRecord.simple(Domain.WARP, norm, Status.BLOCKED,
                        wf.error != null ? wf.error : "invalid warp location", hash("warp:err:" + norm)));
                continue;
            }
            ResolvedWorld w = worlds.resolve(wf.location.world, wf.location.worldName);
            if (w == null) {
                records.add(PlanRecord.simple(Domain.WARP, norm, Status.BLOCKED,
                        "unresolved world: " + worldLabel(wf.location), hash("warp:world:" + norm)));
                continue;
            }
            SavedLoc loc = toSavedLoc(w, wf.location);
            String ph = hashLoc("warp", norm, loc);
            Status st = classifyLocation(dest.ledger, Domain.WARP, norm, ph, dest.warps.get(norm), loc, worlds);
            PlanRecord rec = new PlanRecord(Domain.WARP, norm, st,
                    st == Status.CONFLICT ? "different warp already exists" : statusLabel(st), ph);
            rec.name = norm;
            rec.location = loc;
            records.add(rec);
        }

        // 2. Jails
        Set<String> availableJails = new HashSet<String>(dest.jails.keySet());
        for (JailEntry je : snapshot.jails) {
            String norm = je.name == null ? "" : je.name.trim().toLowerCase(Locale.ROOT);
            if (!SIMPLE_NAME.matcher(norm).matches()) {
                records.add(PlanRecord.simple(Domain.JAIL, String.valueOf(je.name), Status.BLOCKED,
                        "invalid jail name", hash("jail:bad:" + je.name)));
                continue;
            }
            if (je.error != null || je.location == null) {
                records.add(PlanRecord.simple(Domain.JAIL, norm, Status.BLOCKED,
                        je.error != null ? je.error : "invalid jail location", hash("jail:err:" + norm)));
                continue;
            }
            ResolvedWorld w = worlds.resolve(je.location.world, je.location.worldName);
            if (w == null) {
                records.add(PlanRecord.simple(Domain.JAIL, norm, Status.BLOCKED,
                        "unresolved world: " + worldLabel(je.location), hash("jail:world:" + norm)));
                continue;
            }
            SavedLoc loc = toSavedLoc(w, je.location);
            String ph = hashLoc("jail", norm, loc);
            Status st = classifyLocation(dest.ledger, Domain.JAIL, norm, ph, dest.jails.get(norm), loc, worlds);
            if (st == Status.IMPORT || st == Status.PRESENT || st == Status.REPLAYED) {
                availableJails.add(norm);
            }
            PlanRecord rec = new PlanRecord(Domain.JAIL, norm, st,
                    st == Status.CONFLICT ? "different jail already exists" : statusLabel(st), ph);
            rec.name = norm;
            rec.location = loc;
            records.add(rec);
        }

        // 3. Spawn
        for (SpawnEntry se : snapshot.spawns) {
            String group = se.group == null ? "" : se.group.trim().toLowerCase(Locale.ROOT);
            if (!"default".equals(group)) {
                records.add(PlanRecord.simple(Domain.SPAWN, "spawn:" + group, Status.SKIPPED,
                        "per-group spawns are not used in legacy mode (only 'default' spawn is imported)",
                        hash("spawn:group:" + group)));
                continue;
            }
            if (se.error != null || se.location == null) {
                records.add(PlanRecord.simple(Domain.SPAWN, "spawn", Status.BLOCKED,
                        se.error != null ? se.error : "invalid spawn location", hash("spawn:err")));
                continue;
            }
            ResolvedWorld w = worlds.resolve(se.location.world, se.location.worldName);
            if (w == null) {
                records.add(PlanRecord.simple(Domain.SPAWN, "spawn", Status.BLOCKED,
                        "unresolved world: " + worldLabel(se.location), hash("spawn:world")));
                continue;
            }
            SavedLoc loc = toSavedLoc(w, se.location);
            String ph = hashLoc("spawn", "spawn", loc);
            Status st = classifyLocation(dest.ledger, Domain.SPAWN, "spawn", ph, dest.spawn, loc, worlds);
            PlanRecord rec = new PlanRecord(Domain.SPAWN, "spawn", st,
                    st == Status.CONFLICT ? "different spawn already exists" : statusLabel(st), ph);
            rec.name = "spawn";
            rec.location = loc;
            records.add(rec);
        }

        // Enabled signs note
        List<String> supportedSigns = new ArrayList<String>();
        List<String> unsupportedSigns = new ArrayList<String>();
        for (String sign : snapshot.enabledSigns) {
            if (KNOWN_SIGNS.contains(sign)) {
                if (!supportedSigns.contains(sign)) supportedSigns.add(sign);
            } else if (!unsupportedSigns.contains(sign)) {
                unsupportedSigns.add(sign);
                notes.add("Essentials enabledSign '" + sign + "' is not a standard Allium sign type (skipped)");
            }
        }

        // 4. Kits
        Map<String, Long> plannedKitCooldowns = new HashMap<String, Long>(dest.kitCooldownsDef);
        Set<String> seenKits = new HashSet<String>();
        for (RawKit rk : snapshot.kits) {
            String norm = rk.name == null ? "" : rk.name.trim().toLowerCase(Locale.ROOT);
            if (rk.error != null) {
                records.add(PlanRecord.simple(Domain.KIT, String.valueOf(rk.name), Status.BLOCKED,
                        rk.error, hash("kit:err:" + rk.name)));
                continue;
            }
            if (!SIMPLE_NAME.matcher(norm).matches()) {
                records.add(PlanRecord.simple(Domain.KIT, String.valueOf(rk.name), Status.BLOCKED,
                        "invalid kit name", hash("kit:name:" + rk.name)));
                continue;
            }
            if (!seenKits.add(norm)) {
                records.add(PlanRecord.simple(Domain.KIT, norm, Status.BLOCKED,
                        "duplicate normalized kit name", hash("kit:dup:" + rk.name)));
                continue;
            }
            long cooldownSeconds = rk.delaySeconds < 0L ? 0L : Math.min(rk.delaySeconds, MAX_KIT_COOLDOWN_SECONDS);
            List<String> validSpecs = new ArrayList<String>();
            List<String> invalidSpecs = new ArrayList<String>();
            int reviewCommands = 0;
            for (String line : rk.rawItems) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("/") || trimmed.startsWith("$") || trimmed.startsWith("@")) {
                    reviewCommands++;
                    notes.add("Essentials kit '" + norm + "' contains embedded command/action '" + trimmed
                            + "' (not imported; review manually)");
                    continue;
                }
                if (parseEssentialsItem(trimmed) == null) {
                    invalidSpecs.add("'" + trimmed + "'");
                } else {
                    validSpecs.add(trimmed);
                }
            }
            if (!invalidSpecs.isEmpty()) {
                records.add(PlanRecord.simple(Domain.KIT, norm, Status.BLOCKED,
                        "unsupported item spec(s): " + joinStrings(invalidSpecs, ", "),
                        hash("kit:baditems:" + norm + ":" + joinStrings(rk.rawItems, "\n"))));
                continue;
            }
            if (validSpecs.size() > MAX_KIT_ITEMS) {
                records.add(PlanRecord.simple(Domain.KIT, norm, Status.BLOCKED,
                        "kit has " + validSpecs.size() + " items (max " + MAX_KIT_ITEMS + ")",
                        hash("kit:max:" + norm)));
                continue;
            }
            if (validSpecs.isEmpty()) {
                records.add(PlanRecord.simple(Domain.KIT, norm, Status.SKIPPED,
                        reviewCommands == 0 ? "kit has no items" : "kit only contained embedded commands (see notes)",
                        hash("kit:empty:" + norm)));
                continue;
            }
            plannedKitCooldowns.put(norm, cooldownSeconds);
            String ph = hash("kit:" + norm + ":" + cooldownSeconds + ":" + joinStrings(validSpecs, "\n"));
            Status st = checkLedger(dest.ledger, Domain.KIT, norm, ph);
            String reason = "already imported";
            if (st == null) {
                if (!dest.kitCooldownsDef.containsKey(norm)) {
                    st = Status.IMPORT;
                    reason = reviewCommands == 0
                            ? "new kit (" + validSpecs.size() + " item(s))"
                            : "new kit (" + validSpecs.size() + " item(s); " + reviewCommands + " command(s) flagged)";
                } else {
                    st = Status.CONFLICT;
                    reason = "kit '" + norm + "' already exists";
                }
            }
            PlanRecord rec = new PlanRecord(Domain.KIT, norm, st, reason, ph);
            rec.name = norm;
            rec.longValue = cooldownSeconds;
            rec.stringList = validSpecs;
            records.add(rec);
        }

        // 5. Worth
        Set<String> seenWorth = new HashSet<String>();
        for (RawWorth rw : snapshot.worth) {
            Material mat = LegacyCompat.matchMaterial(rw.rawKey);
            if (mat == null || mat == Material.AIR) {
                records.add(PlanRecord.simple(Domain.WORTH, rw.rawKey, Status.BLOCKED,
                        "unknown material '" + rw.rawKey + "'", hash("worth:mat:" + rw.rawKey)));
                continue;
            }
            String matKey = mat.name();
            if (!seenWorth.add(matKey)) continue;
            BigDecimal price = parsePositiveMoney(rw.rawPrice);
            if (price == null) {
                records.add(PlanRecord.simple(Domain.WORTH, matKey, Status.BLOCKED,
                        "invalid worth price '" + rw.rawPrice + "'", hash("worth:price:" + matKey + ":" + rw.rawPrice)));
                continue;
            }
            String ph = hash("worth:" + matKey + ":" + price.toPlainString());
            Status st = checkLedger(dest.ledger, Domain.WORTH, matKey, ph);
            String reason = "already imported";
            if (st == null) {
                BigDecimal existing = dest.worth.get(matKey);
                if (existing == null) {
                    st = Status.IMPORT;
                    reason = "new worth price (" + price.toPlainString() + ")";
                } else if (existing.compareTo(price) == 0) {
                    st = Status.PRESENT;
                    reason = "matches existing worth price";
                } else {
                    st = Status.CONFLICT;
                    reason = "different worth price already set (" + existing.toPlainString() + ")";
                }
            }
            PlanRecord rec = new PlanRecord(Domain.WORTH, matKey, st, reason, ph);
            rec.name = matKey;
            rec.money = price;
            records.add(rec);
        }

        // 6. Users
        for (UserFile user : snapshot.users) {
            if (user.npc) {
                npcSkipped++;
                continue;
            }
            for (String err : user.parseErrors) {
                notes.add("userdata/" + user.fileName + ": " + err);
            }
            UUID uuid = user.uuid;
            String displayName = user.lastAccountName != null ? user.lastAccountName : uuid.toString();

            // 6a. Account name
            if (user.lastAccountName != null && ACCOUNT_NAME.matcher(user.lastAccountName).matches()) {
                String key = uuid.toString();
                String ph = hash("name:" + key + ":" + user.lastAccountName);
                Status st = checkLedger(dest.ledger, Domain.NAME, key, ph);
                String reason = "already imported";
                if (st == null) {
                    String existing = dest.names.get(uuid);
                    if (existing == null) {
                        st = Status.IMPORT;
                        reason = "new account name";
                    } else if (existing.equalsIgnoreCase(user.lastAccountName)) {
                        st = Status.PRESENT;
                        reason = "already recorded";
                    } else {
                        st = Status.SKIPPED;
                        reason = "different name already recorded (" + existing + ")";
                    }
                }
                PlanRecord rec = new PlanRecord(Domain.NAME, key, st, reason, ph);
                rec.owner = uuid;
                rec.text = user.lastAccountName;
                records.add(rec);
            }

            // 6b. Balance
            if (!user.moneyPresent) {
                records.add(PlanRecord.simple(Domain.BALANCE, uuid.toString(), Status.SKIPPED,
                        "missing money field (not treated as zero)", hash("money:missing:" + uuid)));
            } else {
                BigDecimal parsed = parseNonNegativeMoney(user.moneyRaw);
                if (parsed == null) {
                    records.add(PlanRecord.simple(Domain.BALANCE, uuid.toString(), Status.BLOCKED,
                            "invalid money value '" + user.moneyRaw + "'", hash("money:bad:" + uuid + ":" + user.moneyRaw)));
                } else {
                    String key = uuid.toString();
                    String ph = hash("balance:" + key + ":" + parsed.toPlainString());
                    Status st = checkLedger(dest.ledger, Domain.BALANCE, key, ph);
                    String reason = "already imported";
                    if (st == null) {
                        BigDecimal existing = dest.balances.get(uuid);
                        if (existing == null) {
                            st = Status.IMPORT;
                            reason = "new balance (" + parsed.toPlainString() + ")";
                        } else if (existing.compareTo(parsed) == 0) {
                            st = Status.PRESENT;
                            reason = "matches existing balance";
                        } else {
                            st = Status.CONFLICT;
                            reason = "different balance already exists (" + existing.toPlainString() + ")";
                        }
                    }
                    PlanRecord rec = new PlanRecord(Domain.BALANCE, key, st, reason, ph);
                    rec.owner = uuid;
                    rec.name = displayName;
                    rec.money = parsed;
                    records.add(rec);
                }
            }

            // 6c. Homes
            Map<String, SavedLoc> existingHomes = dest.homes.get(uuid);
            Set<String> seenHomes = new HashSet<String>();
            for (Map.Entry<String, RawLocation> he : user.homes.entrySet()) {
                String norm = he.getKey().trim().toLowerCase(Locale.ROOT);
                String key = uuid + ":" + norm;
                if (!SIMPLE_NAME.matcher(norm).matches() || "bed".equals(norm)) {
                    records.add(PlanRecord.simple(Domain.HOME, uuid + ":" + he.getKey(), Status.BLOCKED,
                            "invalid or reserved home name", hash("home:bad:" + uuid + ":" + he.getKey())));
                    continue;
                }
                if (!seenHomes.add(norm)) {
                    records.add(PlanRecord.simple(Domain.HOME, key, Status.BLOCKED,
                            "duplicate normalized home name", hash("home:dup:" + key)));
                    continue;
                }
                ResolvedWorld w = worlds.resolve(he.getValue().world, he.getValue().worldName);
                if (w == null) {
                    records.add(PlanRecord.simple(Domain.HOME, key, Status.BLOCKED,
                            "unresolved world: " + worldLabel(he.getValue()), hash("home:world:" + key)));
                    continue;
                }
                SavedLoc loc = toSavedLoc(w, he.getValue());
                String ph = hashLoc("home", key, loc);
                SavedLoc existing = existingHomes == null ? null : existingHomes.get(norm);
                Status st = classifyLocation(dest.ledger, Domain.HOME, key, ph, existing, loc, worlds);
                PlanRecord rec = new PlanRecord(Domain.HOME, key, st,
                        st == Status.CONFLICT ? "different home already exists" : statusLabel(st), ph);
                rec.owner = uuid;
                rec.name = norm;
                rec.location = loc;
                records.add(rec);
            }

            // 6d. Logout location
            if (user.logoutLocation != null) {
                ResolvedWorld w = worlds.resolve(user.logoutLocation.world, user.logoutLocation.worldName);
                if (w == null) {
                    records.add(PlanRecord.simple(Domain.LOGOUT, uuid.toString(), Status.BLOCKED,
                            "unresolved world: " + worldLabel(user.logoutLocation), hash("logout:world:" + uuid)));
                } else {
                    SavedLoc loc = toSavedLoc(w, user.logoutLocation);
                    long quitAt = user.logoutTimestamp > 0L ? user.logoutTimestamp : now;
                    String key = uuid.toString();
                    String ph = hashLoc("logout", key, loc);
                    Status st = checkLedger(dest.ledger, Domain.LOGOUT, key, ph);
                    String reason = "already imported";
                    if (st == null) {
                        SavedLoc existing = dest.logouts.get(uuid);
                        if (existing == null) {
                            st = Status.IMPORT;
                            reason = "new logout position";
                        } else if (sameLoc(existing, loc, worlds)) {
                            st = Status.PRESENT;
                            reason = "matches existing logout position";
                        } else {
                            st = Status.SKIPPED;
                            reason = "server already has a logout position";
                        }
                    }
                    PlanRecord rec = new PlanRecord(Domain.LOGOUT, key, st, reason, ph);
                    rec.owner = uuid;
                    rec.name = displayName;
                    rec.location = loc;
                    rec.longValue = quitAt;
                    records.add(rec);
                }
            }

            // 6e. Mail
            List<String> existingMail = dest.mail.get(uuid);
            for (RawMail rm : user.mail) {
                if (rm.expire > 0L && rm.expire <= now) {
                    records.add(PlanRecord.simple(Domain.MAIL, uuid + ":" + rm.index, Status.SKIPPED,
                            "expired mail", hash("mail:expired:" + uuid + ":" + rm.index)));
                    continue;
                }
                String senderLabel = rm.senderName != null ? rm.senderName : (rm.legacy ? "Legacy" : "Unknown");
                String formatted = rm.legacy && rm.senderName == null ? rm.message : senderLabel + ": " + rm.message;
                String ph = hash("mail:" + uuid + ":" + rm.index + ":" + formatted);
                String key = uuid + ":" + rm.index + ":" + ph.substring(0, 12);
                Status st = checkLedger(dest.ledger, Domain.MAIL, key, ph);
                String reason = "already imported";
                if (st == null) {
                    if (existingMail != null && existingMail.contains(formatted)) {
                        st = Status.PRESENT;
                        reason = "mail entry already present";
                    } else {
                        st = Status.IMPORT;
                        reason = rm.legacy ? "legacy text mail" : "structured text mail";
                    }
                }
                PlanRecord rec = new PlanRecord(Domain.MAIL, key, st, reason, ph);
                rec.owner = uuid;
                rec.text = formatted;
                records.add(rec);
            }

            // 6f. Nickname
            if (user.nickname != null) {
                String plain = ChatColor.stripColor(LegacyCompat.color(user.nickname));
                if (plain.isEmpty() || plain.length() > 32) {
                    records.add(PlanRecord.simple(Domain.NICKNAME, uuid.toString(), Status.BLOCKED,
                            "nickname must be 1-32 visible characters", hash("nick:bad:" + uuid + ":" + user.nickname)));
                } else {
                    String key = uuid.toString();
                    String ph = hash("nick:" + key + ":" + user.nickname);
                    Status st = checkLedger(dest.ledger, Domain.NICKNAME, key, ph);
                    String reason = "already imported";
                    if (st == null) {
                        String existing = dest.nicknames.get(uuid);
                        if (existing == null || existing.trim().isEmpty()) {
                            st = Status.IMPORT;
                            reason = "new nickname";
                        } else if (existing.equals(user.nickname)) {
                            st = Status.PRESENT;
                            reason = "matches existing nickname";
                        } else {
                            st = Status.CONFLICT;
                            reason = "different nickname already set";
                        }
                    }
                    PlanRecord rec = new PlanRecord(Domain.NICKNAME, key, st, reason, ph);
                    rec.owner = uuid;
                    rec.text = user.nickname;
                    records.add(rec);
                }
            }

            // 6g. Ignores
            Set<UUID> existingIgnores = dest.ignores.get(uuid);
            Set<UUID> seenIgnores = new HashSet<UUID>();
            for (String rawIgn : user.ignores) {
                UUID target = parseUuid(rawIgn);
                if (target == null) {
                    records.add(PlanRecord.simple(Domain.IGNORE, uuid + ":" + rawIgn, Status.SKIPPED,
                            "invalid ignored UUID", hash("ignore:bad:" + uuid + ":" + rawIgn)));
                    continue;
                }
                if (target.equals(uuid)) {
                    records.add(PlanRecord.simple(Domain.IGNORE, uuid + ":" + target, Status.SKIPPED,
                            "self-ignore skipped", hash("ignore:self:" + uuid)));
                    continue;
                }
                if (!seenIgnores.add(target)) continue;
                String key = uuid + ":" + target;
                String ph = hash("ignore:" + key);
                Status st = checkLedger(dest.ledger, Domain.IGNORE, key, ph);
                String reason = "already imported";
                if (st == null) {
                    if (existingIgnores != null && existingIgnores.contains(target)) {
                        st = Status.PRESENT;
                        reason = "already ignored";
                    } else {
                        st = Status.IMPORT;
                        reason = "new ignore entry";
                    }
                }
                PlanRecord rec = new PlanRecord(Domain.IGNORE, key, st, reason, ph);
                rec.owner = uuid;
                rec.target = target;
                records.add(rec);
            }

            // 6h. Preferences
            planPreference(dest, uuid, "accept-payments", user.acceptingPay,
                    dest.payDisabled.containsKey(uuid) ? Boolean.valueOf(!dest.payDisabled.get(uuid)) : null, records);
            planPreference(dest, uuid, "pay-confirm", user.confirmPay,
                    dest.payConfirm.get(uuid), records);
            Map<String, Boolean> existingPrefs = dest.playerSettings.get(uuid);
            planPreference(dest, uuid, "tpa", user.teleportEnabled,
                    existingPrefs == null ? null : existingPrefs.get("tpa"), records);
            planPreference(dest, uuid, "tp-auto", user.teleportAuto,
                    existingPrefs == null ? null : existingPrefs.get("tp-auto"), records);
            planPreference(dest, uuid, "msg", user.msgToggle == null ? null : Boolean.valueOf(!user.msgToggle),
                    existingPrefs == null ? null : existingPrefs.get("msg"), records);
            if (user.socialSpy != null) {
                records.add(PlanRecord.simple(Domain.PREFERENCE, uuid + ":socialspy", Status.SKIPPED,
                        "socialspy is session-only in legacy mode", hash("pref:" + uuid + ":socialspy:" + user.socialSpy)));
            }

            // 6i. Mute
            if (user.muted) {
                if (user.muteTimestamp > 0L && user.muteTimestamp <= now) {
                    records.add(PlanRecord.simple(Domain.MUTE, uuid.toString(), Status.SKIPPED,
                            "expired mute", hash("mute:expired:" + uuid)));
                } else {
                    String reasonText = user.muteReason != null ? user.muteReason : "";
                    long expiresAt = Math.max(0L, user.muteTimestamp);
                    String key = uuid.toString();
                    String ph = hash("mute:" + key + ":" + expiresAt + ":" + reasonText);
                    Status st = checkLedger(dest.ledger, Domain.MUTE, key, ph);
                    String reason = "already imported";
                    if (st == null) {
                        MuteData existing = dest.mutes.get(uuid);
                        if (existing == null || (existing.expiresAt > 0L && existing.expiresAt <= now)) {
                            st = Status.IMPORT;
                            reason = expiresAt == 0L ? "permanent mute" : "active timed mute";
                        } else if (existing.expiresAt == expiresAt && existing.reason.equals(reasonText)) {
                            st = Status.PRESENT;
                            reason = "matches existing mute";
                        } else {
                            st = Status.CONFLICT;
                            reason = "different active mute already exists";
                        }
                    }
                    PlanRecord rec = new PlanRecord(Domain.MUTE, key, st, reason, ph);
                    rec.owner = uuid;
                    rec.name = displayName;
                    rec.longValue = expiresAt;
                    rec.text = reasonText;
                    records.add(rec);
                }
            }

            // 6j. Jailed
            if (user.jailed) {
                String jailNorm = user.jailName == null ? "" : user.jailName.trim().toLowerCase(Locale.ROOT);
                long expiresAt = user.jailTimestamp > 0L ? user.jailTimestamp
                        : (user.onlineJailMillis > 0L ? now + user.onlineJailMillis : 0L);
                if (jailNorm.isEmpty() || !availableJails.contains(jailNorm)) {
                    records.add(PlanRecord.simple(Domain.JAILED, uuid.toString(), Status.BLOCKED,
                            "references unknown jail '" + user.jailName + "'", hash("jailed:nojail:" + uuid + ":" + jailNorm)));
                } else if (expiresAt > 0L && expiresAt <= now) {
                    records.add(PlanRecord.simple(Domain.JAILED, uuid.toString(), Status.SKIPPED,
                            "expired jail sentence", hash("jailed:expired:" + uuid)));
                } else {
                    String key = uuid.toString();
                    String ph = hash("jailed:" + key + ":" + jailNorm + ":" + expiresAt);
                    Status st = checkLedger(dest.ledger, Domain.JAILED, key, ph);
                    String reason = "already imported";
                    if (st == null) {
                        JailedData existing = dest.jailed.get(uuid);
                        if (existing == null || (existing.expiresAt > 0L && existing.expiresAt <= now)) {
                            st = Status.IMPORT;
                            reason = expiresAt == 0L ? "permanent jail sentence" : "active timed jail sentence";
                        } else if (existing.jailName.equalsIgnoreCase(jailNorm) && existing.expiresAt == expiresAt) {
                            st = Status.PRESENT;
                            reason = "matches existing jail sentence";
                        } else {
                            st = Status.CONFLICT;
                            reason = "different active jail sentence already exists";
                        }
                    }
                    PlanRecord rec = new PlanRecord(Domain.JAILED, key, st, reason, ph);
                    rec.owner = uuid;
                    rec.name = displayName;
                    rec.text = jailNorm;
                    rec.longValue = expiresAt;
                    records.add(rec);
                }
            }

            // 6k. Kit usage cooldowns
            for (Map.Entry<String, Long> ke : user.kitTimestamps.entrySet()) {
                String kitNorm = ke.getKey();
                long claimedAt = ke.getValue();
                Long cdSec = plannedKitCooldowns.get(kitNorm);
                if (cdSec == null || cdSec <= 0L) {
                    continue;
                }
                long until = claimedAt + (cdSec * 1000L);
                if (until <= now) {
                    records.add(PlanRecord.simple(Domain.KIT_USAGE, uuid + ":" + kitNorm, Status.SKIPPED,
                            "kit cooldown already expired", hash("kit_usage:exp:" + uuid + ":" + kitNorm)));
                    continue;
                }
                String key = uuid + ":" + kitNorm;
                String ph = hash("kit_usage:" + key + ":" + until);
                Status st = checkLedger(dest.ledger, Domain.KIT_USAGE, key, ph);
                String reason = "already imported";
                if (st == null) {
                    Long existingUntil = dest.kitUsageUntil.get(key);
                    if (existingUntil == null || existingUntil <= now) {
                        st = Status.IMPORT;
                        reason = "active kit cooldown";
                    } else if (existingUntil == until) {
                        st = Status.PRESENT;
                        reason = "matches existing kit cooldown";
                    } else if (existingUntil > until) {
                        st = Status.SKIPPED;
                        reason = "stricter kit cooldown already recorded";
                    } else {
                        st = Status.IMPORT;
                        reason = "extends older kit cooldown";
                    }
                }
                PlanRecord rec = new PlanRecord(Domain.KIT_USAGE, key, st, reason, ph);
                rec.owner = uuid;
                rec.name = kitNorm;
                rec.longValue = until;
                records.add(rec);
            }
        }

        Collections.sort(records, new Comparator<PlanRecord>() {
            @Override
            public int compare(PlanRecord a, PlanRecord b) {
                int c = Integer.compare(a.domain.ordinal(), b.domain.ordinal());
                return c != 0 ? c : a.key.compareTo(b.key);
            }
        });
        String planSha = hashPlan(snapshot.sourceSha256, records);
        return new Plan(snapshot.sourceSha256, planSha, records, supportedSigns, unsupportedSigns,
                notes, npcSkipped, snapshot.skippedUsers.size());
    }

    private static void planPreference(Destination dest, UUID owner, String prefKey, Boolean desired,
                                       Boolean existing, List<PlanRecord> records) {
        if (desired == null) return;
        String key = owner + ":" + prefKey;
        String ph = hash("pref:" + key + ":" + desired);
        Status st = checkLedger(dest.ledger, Domain.PREFERENCE, key, ph);
        String reason = "already imported";
        if (st == null) {
            if (existing == null) {
                st = Status.IMPORT;
                reason = "new preference override";
            } else if (existing.equals(desired)) {
                st = Status.PRESENT;
                reason = "matches existing preference";
            } else {
                st = Status.CONFLICT;
                reason = "different preference override already set (" + existing + ")";
            }
        }
        PlanRecord rec = new PlanRecord(Domain.PREFERENCE, key, st, reason, ph);
        rec.owner = owner;
        rec.name = prefKey;
        rec.boolValue = desired;
        records.add(rec);
    }

    private static Status classifyLocation(Ledger ledger, Domain domain, String key, String ph,
                                           SavedLoc existing, SavedLoc candidate, WorldIndex worlds) {
        Status replayed = checkLedger(ledger, domain, key, ph);
        if (replayed != null) return replayed;
        if (existing == null) return Status.IMPORT;
        return sameLoc(existing, candidate, worlds) ? Status.PRESENT : Status.CONFLICT;
    }

    private static Status checkLedger(Ledger ledger, Domain domain, String key, String ph) {
        String stored = ledger.get(domain, key);
        if (stored == null) return null;
        return stored.equals(ph) ? Status.REPLAYED : Status.CONFLICT;
    }

    // ==================== Applier ====================

    private ApplyResult applyPlan(Plan plan, Ledger ledger, String runId) throws IOException {
        File dataDir = plugin.getDataFolder();
        YamlConfiguration warpsYml = loadYaml(new File(dataDir, "warps.yml"));
        YamlConfiguration spawnYml = loadYaml(new File(dataDir, "spawn.yml"));
        YamlConfiguration econYml = loadYaml(new File(dataDir, "economy-legacy.yml"));
        YamlConfiguration worthYml = loadYaml(new File(dataDir, "worth.yml"));
        YamlConfiguration kitsYml = loadYaml(new File(dataDir, "kits-legacy.yml"));
        YamlConfiguration kitCdYml = loadYaml(new File(dataDir, "kit-cooldowns.yml"));
        YamlConfiguration infoYml = loadYaml(new File(dataDir, "player-info-legacy.yml"));
        YamlConfiguration settingsYml = loadYaml(new File(dataDir, "player-settings-legacy.yml"));
        YamlConfiguration logoutYml = loadYaml(new File(dataDir, "logout-locations-legacy.yml"));
        YamlConfiguration modYml = loadYaml(new File(dataDir, "moderation-legacy.yml"));

        boolean dirtyWarps = false, dirtySpawn = false, dirtyEcon = false, dirtyWorth = false,
                dirtyKits = false, dirtyKitCd = false, dirtyInfo = false, dirtySettings = false,
                dirtyLogout = false, dirtyMod = false;
        Map<UUID, Properties> dirtyHomes = new LinkedHashMap<UUID, Properties>();

        long applied = 0L, adopted = 0L, conflicts = 0L, skipped = 0L;
        List<ApplyEntry> entries = new ArrayList<ApplyEntry>();

        for (PlanRecord rec : plan.records) {
            if (rec.status == Status.PRESENT) {
                ledger.put(rec.domain, rec.key, rec.payloadSha256);
                adopted++;
                entries.add(new ApplyEntry(rec.domain, rec.key, "ADOPTED", rec.reason));
                continue;
            }
            if (rec.status == Status.CONFLICT) {
                conflicts++;
                entries.add(new ApplyEntry(rec.domain, rec.key, "CONFLICT", rec.reason));
                continue;
            }
            if (rec.status != Status.IMPORT) {
                skipped++;
                entries.add(new ApplyEntry(rec.domain, rec.key, rec.status.name(), rec.reason));
                continue;
            }

            switch (rec.domain) {
                case WARP:
                    writeLegacyLoc(warpsYml.createSection("warps." + rec.name), rec.location);
                    dirtyWarps = true;
                    break;
                case JAIL:
                    writeLegacyLoc(modYml.createSection("jails." + rec.name), rec.location);
                    dirtyMod = true;
                    break;
                case SPAWN:
                    writeLegacyLoc(spawnYml.createSection("spawn"), rec.location);
                    dirtySpawn = true;
                    break;
                case KIT: {
                    List<ItemStack> items = new ArrayList<ItemStack>();
                    for (String spec : rec.stringList) {
                        ItemStack stack = parseEssentialsItem(spec);
                        if (stack != null) items.add(stack);
                    }
                    kitsYml.set("kits." + rec.name + ".cooldown", rec.longValue);
                    kitsYml.set("kits." + rec.name + ".items", items);
                    dirtyKits = true;
                    break;
                }
                case WORTH:
                    worthYml.set("worth." + rec.name.toLowerCase(Locale.ROOT), rec.money.doubleValue());
                    dirtyWorth = true;
                    break;
                case NAME:
                    infoYml.set("players." + rec.owner + ".name", rec.text);
                    dirtyInfo = true;
                    break;
                case BALANCE:
                    econYml.set("accounts." + rec.owner + ".balance", rec.money.toPlainString());
                    if (rec.name != null && !econYml.isSet("accounts." + rec.owner + ".name")) {
                        econYml.set("accounts." + rec.owner + ".name", rec.name);
                    }
                    dirtyEcon = true;
                    break;
                case HOME: {
                    Properties props = dirtyHomes.get(rec.owner);
                    if (props == null) {
                        props = readHomeProperties(dataDir.toPath().resolve("homes").resolve(rec.owner + ".properties"));
                        dirtyHomes.put(rec.owner, props);
                    }
                    String worldToken = rec.location.worldUid != null ? rec.location.worldUid : rec.location.worldName;
                    props.setProperty(rec.name, encodeHomePosition(worldToken, rec.location));
                    break;
                }
                case LOGOUT: {
                    String key = rec.owner.toString();
                    logoutYml.set(key + ".name", rec.name);
                    logoutYml.set(key + ".hidden", false);
                    logoutYml.set(key + ".world", rec.location.worldUid != null ? rec.location.worldUid : rec.location.worldName);
                    logoutYml.set(key + ".quit-at", rec.longValue);
                    logoutYml.set(key + ".x", rec.location.x);
                    logoutYml.set(key + ".y", rec.location.y);
                    logoutYml.set(key + ".z", rec.location.z);
                    logoutYml.set(key + ".yaw", (double) rec.location.yaw);
                    logoutYml.set(key + ".pitch", (double) rec.location.pitch);
                    dirtyLogout = true;
                    if (!infoYml.isSet("players." + rec.owner + ".last-seen")) {
                        infoYml.set("players." + rec.owner + ".last-seen", rec.longValue);
                        writeLegacyLoc(infoYml.createSection("players." + rec.owner + ".last-location"), rec.location);
                        dirtyInfo = true;
                    }
                    break;
                }
                case MAIL: {
                    String path = "players." + rec.owner + ".mail";
                    List<String> list = new ArrayList<String>(infoYml.getStringList(path));
                    if (list.size() < 50 && !list.contains(rec.text)) {
                        list.add(rec.text);
                        infoYml.set(path, list);
                        dirtyInfo = true;
                    }
                    break;
                }
                case NICKNAME:
                    infoYml.set("players." + rec.owner + ".nick", rec.text);
                    dirtyInfo = true;
                    break;
                case IGNORE: {
                    String path = "players." + rec.owner + ".ignores";
                    List<String> list = new ArrayList<String>(infoYml.getStringList(path));
                    String tStr = rec.target.toString();
                    if (!list.contains(tStr)) {
                        list.add(tStr);
                        infoYml.set(path, list);
                        dirtyInfo = true;
                    }
                    break;
                }
                case PREFERENCE:
                    if ("accept-payments".equals(rec.name)) {
                        econYml.set("accounts." + rec.owner + ".pay-disabled", !rec.boolValue);
                        dirtyEcon = true;
                    } else if ("pay-confirm".equals(rec.name)) {
                        econYml.set("accounts." + rec.owner + ".pay-confirm", rec.boolValue);
                        dirtyEcon = true;
                    } else {
                        settingsYml.set("players." + rec.owner + "." + rec.name, rec.boolValue);
                        dirtySettings = true;
                    }
                    break;
                case MUTE:
                    modYml.set("mutes." + rec.owner + ".name", rec.name);
                    modYml.set("mutes." + rec.owner + ".expires", rec.longValue);
                    modYml.set("mutes." + rec.owner + ".reason", rec.text);
                    dirtyMod = true;
                    break;
                case JAILED:
                    modYml.set("jailed." + rec.owner + ".name", rec.name);
                    modYml.set("jailed." + rec.owner + ".jail", rec.text);
                    modYml.set("jailed." + rec.owner + ".expires", rec.longValue);
                    dirtyMod = true;
                    break;
                case KIT_USAGE:
                    kitCdYml.set("cooldowns." + rec.owner + "." + rec.name, rec.longValue);
                    dirtyKitCd = true;
                    break;
                default:
                    break;
            }

            ledger.put(rec.domain, rec.key, rec.payloadSha256);
            applied++;
            entries.add(new ApplyEntry(rec.domain, rec.key, "APPLIED", rec.reason));
        }

        if (dirtyWarps) LegacyCompat.saveYamlAtomically(warpsYml, new File(dataDir, "warps.yml"));
        if (dirtySpawn) LegacyCompat.saveYamlAtomically(spawnYml, new File(dataDir, "spawn.yml"));
        if (dirtyEcon) LegacyCompat.saveYamlAtomically(econYml, new File(dataDir, "economy-legacy.yml"));
        if (dirtyWorth) LegacyCompat.saveYamlAtomically(worthYml, new File(dataDir, "worth.yml"));
        if (dirtyKits) LegacyCompat.saveYamlAtomically(kitsYml, new File(dataDir, "kits-legacy.yml"));
        if (dirtyKitCd) LegacyCompat.saveYamlAtomically(kitCdYml, new File(dataDir, "kit-cooldowns.yml"));
        if (dirtyInfo) LegacyCompat.saveYamlAtomically(infoYml, new File(dataDir, "player-info-legacy.yml"));
        if (dirtySettings) LegacyCompat.saveYamlAtomically(settingsYml, new File(dataDir, "player-settings-legacy.yml"));
        if (dirtyLogout) LegacyCompat.saveYamlAtomically(logoutYml, new File(dataDir, "logout-locations-legacy.yml"));
        if (dirtyMod) LegacyCompat.saveYamlAtomically(modYml, new File(dataDir, "moderation-legacy.yml"));
        for (Map.Entry<UUID, Properties> he : dirtyHomes.entrySet()) {
            saveHomeProperties(dataDir.toPath().resolve("homes").resolve(he.getKey() + ".properties"), he.getValue());
        }

        ledger.appliedRuns.add(runId);
        saveLedger(ledger);
        return new ApplyResult(applied, adopted, conflicts, skipped, entries);
    }

    // ==================== Essentials Kit Item Parser ====================

    @SuppressWarnings("deprecation")
    static ItemStack parseEssentialsItem(String rawLine) {
        if (rawLine == null || rawLine.trim().isEmpty()) return null;
        String[] tokens = rawLine.trim().split("\\s+");
        if (tokens.length == 0) return null;

        String matToken = tokens[0];
        short data = 0;
        int colon = matToken.indexOf(':');
        if (colon > 0 && !matToken.toLowerCase(Locale.ROOT).startsWith("minecraft:")) {
            String dataStr = matToken.substring(colon + 1);
            matToken = matToken.substring(0, colon);
            try {
                data = Short.parseShort(dataStr);
            } catch (NumberFormatException ignored) {
            }
        }
        Material mat = LegacyCompat.matchMaterial(matToken);
        if (mat == null || mat == Material.AIR) return null;

        int amount = 1;
        int idx = 1;
        if (idx < tokens.length && tokens[idx].matches("\\d+")) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(tokens[idx])));
            } catch (NumberFormatException ignored) {
            }
            idx++;
        }

        ItemStack stack = data != 0 ? new ItemStack(mat, amount, data) : new ItemStack(mat, amount);
        ItemMeta meta = stack.getItemMeta();
        boolean metaDirty = false;
        for (int i = idx; i < tokens.length; i++) {
            String tok = tokens[i];
            int sep = tok.indexOf(':');
            if (sep <= 0 || sep >= tok.length() - 1) continue;
            String k = tok.substring(0, sep).toLowerCase(Locale.ROOT);
            String v = tok.substring(sep + 1);
            if ("name".equals(k) && meta != null) {
                meta.setDisplayName(LegacyCompat.color(v.replace('_', ' ')));
                metaDirty = true;
            } else if ("lore".equals(k) && meta != null) {
                String[] parts = v.split("\\|");
                List<String> lore = new ArrayList<String>();
                for (String p : parts) {
                    lore.add(LegacyCompat.color(p.replace('_', ' ')));
                }
                meta.setLore(lore);
                metaDirty = true;
            } else {
                Enchantment ench = LegacyCompat.matchEnchantment(k);
                if (ench != null) {
                    try {
                        int lvl = Integer.parseInt(v);
                        if (lvl > 0) {
                            stack.addUnsafeEnchantment(ench, Math.min(255, lvl));
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        if (metaDirty && meta != null) {
            stack.setItemMeta(meta);
        }
        return stack;
    }

    // ==================== Ledger & Reports ====================

    private Ledger readLedger() {
        Ledger ledger = new Ledger();
        File file = ledgerPath().toFile();
        if (!file.isFile()) return ledger;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ledger.appliedRuns.addAll(yml.getStringList("applied-runs"));
        ConfigurationSection recordsSec = yml.getConfigurationSection("records");
        if (recordsSec != null) {
            for (String dom : recordsSec.getKeys(false)) {
                ConfigurationSection sub = recordsSec.getConfigurationSection(dom);
                if (sub == null) continue;
                for (String encoded : sub.getKeys(false)) {
                    String h = sub.getString(encoded);
                    if (h != null) ledger.hashes.put(dom + "|" + encoded, h);
                }
            }
        }
        return ledger;
    }

    private void saveLedger(Ledger ledger) throws IOException {
        YamlConfiguration yml = new YamlConfiguration();
        List<String> runs = new ArrayList<String>(ledger.appliedRuns);
        Collections.sort(runs);
        yml.set("applied-runs", runs);
        for (Map.Entry<String, String> entry : ledger.hashes.entrySet()) {
            int sep = entry.getKey().indexOf('|');
            if (sep <= 0) continue;
            String dom = entry.getKey().substring(0, sep);
            String encoded = entry.getKey().substring(sep + 1);
            yml.set("records." + dom + "." + encoded, entry.getValue());
        }
        LegacyCompat.saveYamlAtomically(yml, ledgerPath().toFile());
    }

    private static void writePlanJson(Path file, String runId, Settings settings, Plan plan, long plannedAt) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"runId\": \"").append(escapeJson(runId)).append("\",\n");
        sb.append("  \"source\": \"essentials\",\n");
        sb.append("  \"sourcePath\": \"").append(escapeJson(settings.source.toString())).append("\",\n");
        sb.append("  \"sourceSha256\": \"").append(escapeJson(plan.sourceSha256)).append("\",\n");
        sb.append("  \"planSha256\": \"").append(escapeJson(plan.planSha256)).append("\",\n");
        sb.append("  \"plannedAt\": ").append(plannedAt).append(",\n");
        sb.append("  \"importCount\": ").append(plan.count(Status.IMPORT)).append(",\n");
        sb.append("  \"presentCount\": ").append(plan.count(Status.PRESENT)).append(",\n");
        sb.append("  \"replayedCount\": ").append(plan.count(Status.REPLAYED)).append(",\n");
        sb.append("  \"conflictCount\": ").append(plan.count(Status.CONFLICT)).append(",\n");
        sb.append("  \"blockedCount\": ").append(plan.count(Status.BLOCKED)).append(",\n");
        sb.append("  \"skippedCount\": ").append(plan.count(Status.SKIPPED)).append("\n");
        sb.append("}\n");
        writeUtf8Atomically(file, sb.toString());
    }

    private static ReviewedPlan parseReviewedPlan(Path file) throws IOException {
        String content = readUtf8(file);
        Map<String, String> strings = new HashMap<String, String>();
        Matcher ms = JSON_STRING_FIELD.matcher(content);
        while (ms.find()) {
            strings.put(ms.group(1), unescapeJson(ms.group(2)));
        }
        Map<String, Long> longs = new HashMap<String, Long>();
        Matcher ml = JSON_LONG_FIELD.matcher(content);
        while (ml.find()) {
            try {
                longs.put(ml.group(1), Long.parseLong(ml.group(2)));
            } catch (NumberFormatException ignored) {
            }
        }
        String runId = strings.get("runId");
        String sourceSha = strings.get("sourceSha256");
        String planSha = strings.get("planSha256");
        Long plannedAt = longs.get("plannedAt");
        if (runId == null || sourceSha == null || planSha == null || plannedAt == null) {
            return null;
        }
        return new ReviewedPlan(runId, sourceSha, planSha, plannedAt);
    }

    private static void writeRecordsJsonl(Path file, Plan plan) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (PlanRecord r : plan.records) {
            sb.append("{\"domain\":\"").append(r.domain.name())
                    .append("\",\"key\":\"").append(escapeJson(r.key))
                    .append("\",\"status\":\"").append(r.status.name())
                    .append("\",\"reason\":\"").append(escapeJson(r.reason))
                    .append("\",\"sha256\":\"").append(escapeJson(r.payloadSha256))
                    .append("\"}\n");
        }
        writeUtf8Atomically(file, sb.toString());
    }

    private static void writeApplyJsonl(Path file, ApplyResult result) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (ApplyEntry e : result.entries) {
            sb.append("{\"domain\":\"").append(e.domain.name())
                    .append("\",\"key\":\"").append(escapeJson(e.key))
                    .append("\",\"outcome\":\"").append(escapeJson(e.outcome))
                    .append("\",\"reason\":\"").append(escapeJson(e.reason))
                    .append("\"}\n");
        }
        writeUtf8Atomically(file, sb.toString());
    }

    private static void writeSummaryTxt(Path file, String runId, Settings settings, Plan plan, ApplyResult result) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Allium Essentials Import ").append(result == null ? "Dry Run" : "Apply").append(": ").append(runId).append("\n");
        sb.append("Source: ").append(settings.source).append("\n");
        sb.append("Source SHA-256: ").append(plan.sourceSha256).append("\n");
        sb.append("Plan SHA-256: ").append(plan.planSha256).append("\n");
        sb.append("NPC userdata skipped: ").append(plan.npcSkipped)
                .append(", unreadable/non-UUID userdata skipped: ").append(plan.skippedUserFiles).append("\n");
        if (!plan.supportedSigns.isEmpty()) {
            sb.append("Supported Essentials enabledSigns: ").append(joinStrings(plan.supportedSigns, ", ")).append("\n");
        }
        if (!plan.unsupportedSigns.isEmpty()) {
            sb.append("Unsupported Essentials enabledSigns: ").append(joinStrings(plan.unsupportedSigns, ", ")).append("\n");
        }
        sb.append("\nPlan Totals:\n");
        sb.append("  IMPORT:   ").append(plan.count(Status.IMPORT)).append("\n");
        sb.append("  PRESENT:  ").append(plan.count(Status.PRESENT)).append("\n");
        sb.append("  REPLAYED: ").append(plan.count(Status.REPLAYED)).append("\n");
        sb.append("  CONFLICT: ").append(plan.count(Status.CONFLICT)).append("\n");
        sb.append("  BLOCKED:  ").append(plan.count(Status.BLOCKED)).append("\n");
        sb.append("  SKIPPED:  ").append(plan.count(Status.SKIPPED)).append("\n");

        if (result != null) {
            sb.append("\nApply Totals:\n");
            sb.append("  APPLIED:   ").append(result.applied).append("\n");
            sb.append("  ADOPTED:   ").append(result.adopted).append("\n");
            sb.append("  CONFLICTS: ").append(result.conflicts).append("\n");
            sb.append("  SKIPPED:   ").append(result.skipped).append("\n");
        }

        sb.append("\nBy Domain:\n");
        for (Domain d : Domain.values()) {
            long imp = 0, pre = 0, rep = 0, con = 0, blk = 0, skp = 0;
            for (PlanRecord r : plan.records) {
                if (r.domain != d) continue;
                switch (r.status) {
                    case IMPORT: imp++; break;
                    case PRESENT: pre++; break;
                    case REPLAYED: rep++; break;
                    case CONFLICT: con++; break;
                    case BLOCKED: blk++; break;
                    case SKIPPED: skp++; break;
                }
            }
            if (imp + pre + rep + con + blk + skp > 0) {
                sb.append("  ").append(d.name()).append(": import=").append(imp)
                        .append(" present=").append(pre)
                        .append(" replayed=").append(rep)
                        .append(" conflict=").append(con)
                        .append(" blocked=").append(blk)
                        .append(" skipped=").append(skp).append("\n");
            }
        }

        if (!plan.notes.isEmpty()) {
            sb.append("\nNotes:\n");
            for (String note : plan.notes) {
                sb.append("  - ").append(note).append("\n");
            }
        }
        writeUtf8Atomically(file, sb.toString());
    }

    // ==================== Messaging & Thread Helpers ====================

    private void refuse(CommandSender sender, String reason) {
        messages.send(sender, "core.import.essentials-refused",
                "{prefix} &cEssentials import refused: &f{reason}", "{reason}", reason);
    }

    private void tell(final CommandSender sender, final String key, final String fallback, final String... placeholders) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                messages.send(sender, key, fallback, placeholders);
            }
        });
    }

    private void runOnMainThread(Runnable action) {
        if (inlineExecution) {
            action.run();
            return;
        }
        try {
            if (plugin.getServer().isPrimaryThread()) {
                action.run();
            } else {
                plugin.getServer().getScheduler().runTask(plugin, action);
            }
        } catch (RuntimeException unavailable) {
            action.run();
        }
    }

    // ==================== Low-level Helpers ====================

    private static YamlConfiguration loadYaml(File file) {
        return file.isFile() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    private static SavedLoc readLegacyLoc(ConfigurationSection sec) {
        if (sec == null) return null;
        String w = trimToNull(sec.getString("world"));
        if (w == null) return null;
        UUID wUid = parseUuid(w);
        return new SavedLoc(wUid != null ? wUid.toString() : null, w,
                sec.getDouble("x"), sec.getDouble("y"), sec.getDouble("z"),
                (float) sec.getDouble("yaw"), (float) sec.getDouble("pitch"));
    }

    private static void writeLegacyLoc(ConfigurationSection sec, SavedLoc loc) {
        sec.set("world", loc.worldName);
        sec.set("x", loc.x);
        sec.set("y", loc.y);
        sec.set("z", loc.z);
        sec.set("yaw", (double) loc.yaw);
        sec.set("pitch", (double) loc.pitch);
    }

    private static SavedLoc decodeHomePosition(String raw) {
        if (raw == null) return null;
        String[] fields = raw.split("\t", -1);
        if (fields.length != 6) return null;
        try {
            String worldToken = fields[0];
            UUID uid = parseUuid(worldToken);
            return new SavedLoc(uid != null ? uid.toString() : null, worldToken,
                    Double.parseDouble(fields[1]), Double.parseDouble(fields[2]), Double.parseDouble(fields[3]),
                    Float.parseFloat(fields[4]), Float.parseFloat(fields[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String encodeHomePosition(String worldToken, SavedLoc loc) {
        return worldToken + "\t" + loc.x + "\t" + loc.y + "\t" + loc.z + "\t" + loc.yaw + "\t" + loc.pitch;
    }

    private static Properties readHomeProperties(Path file) {
        Properties props = new Properties();
        if (!Files.isRegularFile(file)) return props;
        Reader reader = null;
        try {
            reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
            props.load(reader);
        } catch (IOException ignored) {
        } finally {
            if (reader != null) try { reader.close(); } catch (IOException ignored) {}
        }
        return props;
    }

    private static void saveHomeProperties(Path file, Properties props) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), "homes-", ".tmp");
        try {
            Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8);
            try {
                props.store(writer, null);
            } finally {
                writer.close();
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static SavedLoc toSavedLoc(ResolvedWorld world, RawLocation raw) {
        return new SavedLoc(world.uid, world.name, raw.x, raw.y, raw.z, raw.yaw, raw.pitch);
    }

    private static boolean sameLoc(SavedLoc a, SavedLoc b, WorldIndex worlds) {
        if (a == null || b == null) return false;
        ResolvedWorld wa = worlds.resolve(a.worldUid, a.worldName);
        ResolvedWorld wb = worlds.resolve(b.worldUid, b.worldName);
        boolean sameWorld;
        if (wa != null && wb != null) {
            sameWorld = wa.uid.equals(wb.uid);
        } else {
            sameWorld = a.worldName != null && a.worldName.equalsIgnoreCase(b.worldName);
        }
        return sameWorld
                && Math.abs(a.x - b.x) <= COORD_EPSILON
                && Math.abs(a.y - b.y) <= COORD_EPSILON
                && Math.abs(a.z - b.z) <= COORD_EPSILON
                && Math.abs(normalizeAngle(a.yaw - b.yaw)) <= ANGLE_EPSILON
                && Math.abs(a.pitch - b.pitch) <= ANGLE_EPSILON;
    }

    private static float normalizeAngle(float delta) {
        float d = delta % 360.0f;
        if (d > 180.0f) d -= 360.0f;
        if (d < -180.0f) d += 360.0f;
        return d;
    }

    private static String worldLabel(RawLocation loc) {
        if (loc.worldName != null && loc.world != null && !loc.worldName.equals(loc.world)) {
            return loc.worldName + " (" + loc.world + ")";
        }
        return loc.worldName != null ? loc.worldName : String.valueOf(loc.world);
    }

    private static String statusLabel(Status st) {
        return st.name().toLowerCase(Locale.ROOT);
    }

    private static BigDecimal parseNonNegativeMoney(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            BigDecimal val = new BigDecimal(raw.trim()).setScale(2, RoundingMode.HALF_UP);
            return val.compareTo(BigDecimal.ZERO) < 0 ? null : val;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parsePositiveMoney(String raw) {
        BigDecimal val = parseNonNegativeMoney(raw);
        return val != null && val.compareTo(BigDecimal.ZERO) > 0 ? val : null;
    }

    private static List<Path> sortedYml(Path dir) throws IOException {
        List<Path> files = new ArrayList<Path>();
        DirectoryStream<Path> stream = null;
        try {
            stream = Files.newDirectoryStream(dir);
            for (Path p : stream) {
                if (Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml")) {
                    files.add(p);
                }
            }
        } finally {
            if (stream != null) try { stream.close(); } catch (IOException ignored) {}
        }
        Collections.sort(files, new Comparator<Path>() {
            @Override
            public int compare(Path a, Path b) {
                return a.getFileName().toString().compareTo(b.getFileName().toString());
            }
        });
        return files;
    }

    private static List<String> sortedKeys(ConfigurationSection sec) {
        List<String> keys = new ArrayList<String>(sec.getKeys(false));
        Collections.sort(keys);
        return keys;
    }

    private static String readUtf8(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static void writeUtf8Atomically(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void hashFile(MessageDigest digest, String relPath, byte[] bytes) {
        digest.update(relPath.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(bytes);
        digest.update((byte) 0);
    }

    private static String hash(String input) {
        MessageDigest d = sha256();
        d.update(input.getBytes(StandardCharsets.UTF_8));
        return toHex(d.digest());
    }

    private static String hashLoc(String prefix, String key, SavedLoc loc) {
        return hash(prefix + ":" + key + ":" + loc.worldUid + ":" + loc.worldName + ":"
                + loc.x + ":" + loc.y + ":" + loc.z + ":" + loc.yaw + ":" + loc.pitch);
    }

    private static String hashPlan(String sourceSha, List<PlanRecord> records) {
        MessageDigest d = sha256();
        d.update(sourceSha.getBytes(StandardCharsets.UTF_8));
        d.update((byte) '\n');
        for (PlanRecord r : records) {
            String line = r.domain.name() + "|" + r.key + "|" + r.status.name() + "|" + r.payloadSha256 + "\n";
            d.update(line.getBytes(StandardCharsets.UTF_8));
        }
        return toHex(d.digest());
    }

    private static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2] = digits[v >>> 4];
            hex[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(hex);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static String unescapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case '\\': sb.append('\\'); break;
                    case '"': sb.append('"'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    default: sb.append(n); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return (nl >= 0 ? s.substring(0, nl) : s).trim();
    }

    private static UUID parseUuid(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean boolValue(Object val, boolean def) {
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String) return Boolean.parseBoolean(((String) val).trim());
        return def;
    }

    private static long longValue(Object val, long def) {
        if (val instanceof Number) return ((Number) val).longValue();
        if (val instanceof String) {
            try {
                return Long.parseLong(((String) val).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static String joinStrings(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    // ==================== Data Records (Java 8) ====================

    private static final class Settings {
        final Path source;
        final Map<String, String> worldMappings;

        Settings(Path source, Map<String, String> worldMappings) {
            this.source = source;
            this.worldMappings = worldMappings;
        }
    }

    private static final class ResolvedWorld {
        final String uid;
        final String name;

        ResolvedWorld(String uid, String name) {
            this.uid = uid;
            this.name = name;
        }
    }

    private static final class WorldIndex {
        private final Map<String, ResolvedWorld> byName;
        private final Map<String, ResolvedWorld> byUid;
        private final Map<String, String> mappings;

        private WorldIndex(Map<String, ResolvedWorld> byName, Map<String, ResolvedWorld> byUid,
                           Map<String, String> mappings) {
            this.byName = byName;
            this.byUid = byUid;
            this.mappings = mappings;
        }

        static WorldIndex capture(Server server, Map<String, String> mappings) {
            Map<String, ResolvedWorld> byName = new HashMap<String, ResolvedWorld>();
            Map<String, ResolvedWorld> byUid = new HashMap<String, ResolvedWorld>();
            for (World w : server.getWorlds()) {
                ResolvedWorld rw = new ResolvedWorld(w.getUID().toString(), w.getName());
                byName.put(w.getName().toLowerCase(Locale.ROOT), rw);
                byUid.put(w.getUID().toString().toLowerCase(Locale.ROOT), rw);
            }
            return new WorldIndex(byName, byUid, mappings);
        }

        ResolvedWorld resolve(String rawWorld, String rawWorldName) {
            ResolvedWorld found = lookup(rawWorldName);
            if (found != null) return found;
            return lookup(rawWorld);
        }

        private ResolvedWorld lookup(String token) {
            if (token == null || token.trim().isEmpty()) return null;
            String key = token.trim().toLowerCase(Locale.ROOT);
            String mapped = mappings.get(key);
            if (mapped != null) {
                String mk = mapped.trim().toLowerCase(Locale.ROOT);
                ResolvedWorld w = byName.get(mk);
                if (w != null) return w;
                return byUid.get(mk);
            }
            ResolvedWorld w = byUid.get(key);
            if (w != null) return w;
            return byName.get(key);
        }
    }

    private static final class RawLocation {
        final String world;
        final String worldName;
        final double x, y, z;
        final float yaw, pitch;

        RawLocation(String world, String worldName, double x, double y, double z, float yaw, float pitch) {
            this.world = world;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static final class ParsedLocation {
        final RawLocation location;
        final String error;

        ParsedLocation(RawLocation location, String error) {
            this.location = location;
            this.error = error;
        }

        String errorIn(String fileName) {
            return error == null ? null : fileName + ": " + error;
        }
    }

    private static final class SavedLoc {
        final String worldUid;
        final String worldName;
        final double x, y, z;
        final float yaw, pitch;

        SavedLoc(String worldUid, String worldName, double x, double y, double z, float yaw, float pitch) {
            this.worldUid = worldUid;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static final class RawMail {
        final int index;
        final boolean legacy;
        final boolean read;
        final String senderName;
        final long timestamp;
        final long expire;
        final String message;

        RawMail(int index, boolean legacy, boolean read, String senderName, long timestamp, long expire, String message) {
            this.index = index;
            this.legacy = legacy;
            this.read = read;
            this.senderName = senderName;
            this.timestamp = timestamp;
            this.expire = expire;
            this.message = message;
        }
    }

    private static final class UserFile {
        final String fileName;
        final UUID uuid;
        final boolean npc;
        final String lastAccountName;
        final String moneyRaw;
        final boolean moneyPresent;
        final Map<String, RawLocation> homes;
        final RawLocation logoutLocation;
        final long logoutTimestamp;
        final List<RawMail> mail;
        final String nickname;
        final List<String> ignores;
        final boolean muted;
        final String muteReason;
        final long muteTimestamp;
        final boolean jailed;
        final String jailName;
        final long jailTimestamp;
        final long onlineJailMillis;
        final Boolean acceptingPay;
        final Boolean teleportEnabled;
        final Boolean teleportAuto;
        final Boolean confirmPay;
        final Boolean socialSpy;
        final Boolean msgToggle;
        final Map<String, Long> kitTimestamps;
        final List<String> parseErrors;

        UserFile(String fileName, UUID uuid, boolean npc, String lastAccountName, String moneyRaw,
                 boolean moneyPresent, Map<String, RawLocation> homes, RawLocation logoutLocation,
                 long logoutTimestamp, List<RawMail> mail, String nickname, List<String> ignores,
                 boolean muted, String muteReason, long muteTimestamp, boolean jailed, String jailName,
                 long jailTimestamp, long onlineJailMillis, Boolean acceptingPay, Boolean teleportEnabled,
                 Boolean teleportAuto, Boolean confirmPay, Boolean socialSpy, Boolean msgToggle,
                 Map<String, Long> kitTimestamps, List<String> parseErrors) {
            this.fileName = fileName;
            this.uuid = uuid;
            this.npc = npc;
            this.lastAccountName = lastAccountName;
            this.moneyRaw = moneyRaw;
            this.moneyPresent = moneyPresent;
            this.homes = homes;
            this.logoutLocation = logoutLocation;
            this.logoutTimestamp = logoutTimestamp;
            this.mail = mail;
            this.nickname = nickname;
            this.ignores = ignores;
            this.muted = muted;
            this.muteReason = muteReason;
            this.muteTimestamp = muteTimestamp;
            this.jailed = jailed;
            this.jailName = jailName;
            this.jailTimestamp = jailTimestamp;
            this.onlineJailMillis = onlineJailMillis;
            this.acceptingPay = acceptingPay;
            this.teleportEnabled = teleportEnabled;
            this.teleportAuto = teleportAuto;
            this.confirmPay = confirmPay;
            this.socialSpy = socialSpy;
            this.msgToggle = msgToggle;
            this.kitTimestamps = kitTimestamps;
            this.parseErrors = parseErrors;
        }
    }

    private static final class SkippedFile {
        final String fileName;
        final String reason;

        SkippedFile(String fileName, String reason) {
            this.fileName = fileName;
            this.reason = reason;
        }
    }

    private static final class WarpFile {
        final String fileName;
        final String name;
        final RawLocation location;
        final String error;

        WarpFile(String fileName, String name, RawLocation location, String error) {
            this.fileName = fileName;
            this.name = name;
            this.location = location;
            this.error = error;
        }
    }

    private static final class JailEntry {
        final String name;
        final RawLocation location;
        final String error;

        JailEntry(String name, RawLocation location, String error) {
            this.name = name;
            this.location = location;
            this.error = error;
        }
    }

    private static final class SpawnEntry {
        final String group;
        final RawLocation location;
        final String error;

        SpawnEntry(String group, RawLocation location, String error) {
            this.group = group;
            this.location = location;
            this.error = error;
        }
    }

    private static final class RawKit {
        final String name;
        final long delaySeconds;
        final List<String> rawItems;
        final String error;

        RawKit(String name, long delaySeconds, List<String> rawItems, String error) {
            this.name = name;
            this.delaySeconds = delaySeconds;
            this.rawItems = rawItems;
            this.error = error;
        }
    }

    private static final class RawWorth {
        final String rawKey;
        final String rawPrice;

        RawWorth(String rawKey, String rawPrice) {
            this.rawKey = rawKey;
            this.rawPrice = rawPrice;
        }
    }

    private static final class Snapshot {
        final String sourceSha256;
        final List<UserFile> users;
        final List<SkippedFile> skippedUsers;
        final List<WarpFile> warps;
        final List<JailEntry> jails;
        final List<SpawnEntry> spawns;
        final List<RawKit> kits;
        final List<RawWorth> worth;
        final List<String> enabledSigns;
        final List<String> notes;

        Snapshot(String sourceSha256, List<UserFile> users, List<SkippedFile> skippedUsers,
                 List<WarpFile> warps, List<JailEntry> jails, List<SpawnEntry> spawns,
                 List<RawKit> kits, List<RawWorth> worth, List<String> enabledSigns, List<String> notes) {
            this.sourceSha256 = sourceSha256;
            this.users = users;
            this.skippedUsers = skippedUsers;
            this.warps = warps;
            this.jails = jails;
            this.spawns = spawns;
            this.kits = kits;
            this.worth = worth;
            this.enabledSigns = enabledSigns;
            this.notes = notes;
        }
    }

    private static final class MuteData {
        final String name;
        final long expiresAt;
        final String reason;

        MuteData(String name, long expiresAt, String reason) {
            this.name = name;
            this.expiresAt = expiresAt;
            this.reason = reason == null ? "" : reason;
        }
    }

    private static final class JailedData {
        final String name;
        final String jailName;
        final long expiresAt;

        JailedData(String name, String jailName, long expiresAt) {
            this.name = name;
            this.jailName = jailName == null ? "" : jailName;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Ledger {
        final Set<String> appliedRuns = new HashSet<String>();
        final Map<String, String> hashes = new TreeMap<String, String>();

        String get(Domain domain, String key) {
            return hashes.get(domain.name() + "|" + hash(key));
        }

        void put(Domain domain, String key, String payloadHash) {
            hashes.put(domain.name() + "|" + hash(key), payloadHash);
        }
    }

    private static final class Destination {
        final Ledger ledger;
        final Map<String, SavedLoc> warps;
        final SavedLoc spawn;
        final Map<UUID, Map<String, SavedLoc>> homes;
        final Map<UUID, BigDecimal> balances;
        final Map<UUID, Boolean> payDisabled;
        final Map<UUID, Boolean> payConfirm;
        final Map<String, BigDecimal> worth;
        final Map<String, Long> kitCooldownsDef;
        final Map<String, Long> kitUsageUntil;
        final Map<UUID, String> names;
        final Map<UUID, String> nicknames;
        final Map<UUID, Set<UUID>> ignores;
        final Map<UUID, List<String>> mail;
        final Map<UUID, Map<String, Boolean>> playerSettings;
        final Map<UUID, SavedLoc> logouts;
        final Map<String, SavedLoc> jails;
        final Map<UUID, MuteData> mutes;
        final Map<UUID, JailedData> jailed;

        Destination(Ledger ledger, Map<String, SavedLoc> warps, SavedLoc spawn,
                    Map<UUID, Map<String, SavedLoc>> homes, Map<UUID, BigDecimal> balances,
                    Map<UUID, Boolean> payDisabled, Map<UUID, Boolean> payConfirm,
                    Map<String, BigDecimal> worth, Map<String, Long> kitCooldownsDef,
                    Map<String, Long> kitUsageUntil, Map<UUID, String> names,
                    Map<UUID, String> nicknames, Map<UUID, Set<UUID>> ignores,
                    Map<UUID, List<String>> mail, Map<UUID, Map<String, Boolean>> playerSettings,
                    Map<UUID, SavedLoc> logouts, Map<String, SavedLoc> jails,
                    Map<UUID, MuteData> mutes, Map<UUID, JailedData> jailed) {
            this.ledger = ledger;
            this.warps = warps;
            this.spawn = spawn;
            this.homes = homes;
            this.balances = balances;
            this.payDisabled = payDisabled;
            this.payConfirm = payConfirm;
            this.worth = worth;
            this.kitCooldownsDef = kitCooldownsDef;
            this.kitUsageUntil = kitUsageUntil;
            this.names = names;
            this.nicknames = nicknames;
            this.ignores = ignores;
            this.mail = mail;
            this.playerSettings = playerSettings;
            this.logouts = logouts;
            this.jails = jails;
            this.mutes = mutes;
            this.jailed = jailed;
        }
    }

    private static final class PlanRecord {
        final Domain domain;
        final String key;
        final Status status;
        final String reason;
        final String payloadSha256;
        UUID owner;
        UUID target;
        String name;
        String text;
        SavedLoc location;
        BigDecimal money;
        long longValue;
        Boolean boolValue;
        List<String> stringList;

        PlanRecord(Domain domain, String key, Status status, String reason, String payloadSha256) {
            this.domain = domain;
            this.key = key;
            this.status = status;
            this.reason = reason;
            this.payloadSha256 = payloadSha256;
        }

        static PlanRecord simple(Domain domain, String key, Status status, String reason, String payloadSha256) {
            return new PlanRecord(domain, key, status, reason, payloadSha256);
        }
    }

    private static final class Plan {
        final String sourceSha256;
        final String planSha256;
        final List<PlanRecord> records;
        final List<String> supportedSigns;
        final List<String> unsupportedSigns;
        final List<String> notes;
        final int npcSkipped;
        final int skippedUserFiles;

        Plan(String sourceSha256, String planSha256, List<PlanRecord> records,
             List<String> supportedSigns, List<String> unsupportedSigns, List<String> notes,
             int npcSkipped, int skippedUserFiles) {
            this.sourceSha256 = sourceSha256;
            this.planSha256 = planSha256;
            this.records = records;
            this.supportedSigns = supportedSigns;
            this.unsupportedSigns = unsupportedSigns;
            this.notes = notes;
            this.npcSkipped = npcSkipped;
            this.skippedUserFiles = skippedUserFiles;
        }

        long count(Status status) {
            long c = 0L;
            for (PlanRecord r : records) {
                if (r.status == status) c++;
            }
            return c;
        }
    }

    private static final class ReviewedPlan {
        final String runId;
        final String sourceSha256;
        final String planSha256;
        final long plannedAt;

        ReviewedPlan(String runId, String sourceSha256, String planSha256, long plannedAt) {
            this.runId = runId;
            this.sourceSha256 = sourceSha256;
            this.planSha256 = planSha256;
            this.plannedAt = plannedAt;
        }
    }

    private static final class ApplyEntry {
        final Domain domain;
        final String key;
        final String outcome;
        final String reason;

        ApplyEntry(Domain domain, String key, String outcome, String reason) {
            this.domain = domain;
            this.key = key;
            this.outcome = outcome;
            this.reason = reason;
        }
    }

    private static final class ApplyResult {
        final long applied;
        final long adopted;
        final long conflicts;
        final long skipped;
        final List<ApplyEntry> entries;

        ApplyResult(long applied, long adopted, long conflicts, long skipped, List<ApplyEntry> entries) {
            this.applied = applied;
            this.adopted = adopted;
            this.conflicts = conflicts;
            this.skipped = skipped;
            this.entries = entries;
        }
    }
}
