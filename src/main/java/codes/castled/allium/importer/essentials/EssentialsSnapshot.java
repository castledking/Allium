package codes.castled.allium.importer.essentials;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Immutable read-only snapshot of an Essentials/EssentialsX plugin folder ({@code userdata/*.yml},
 * {@code warps/*.yml}, {@code jail.yml}/{@code jails.yml}, {@code spawn.yml}, {@code kits.yml},
 * {@code worth.yml}, and {@code config.yml} {@code enabledSigns}). Never mutates source files.
 */
public record EssentialsSnapshot(
        String sourceSha256,
        List<UserFile> users,
        List<SkippedFile> skippedUserFiles,
        List<WarpFile> warps,
        List<JailEntry> jails,
        List<SpawnEntry> spawns,
        List<RawKit> kits,
        List<RawWorth> worth,
        List<String> enabledSigns,
        List<String> notes
) {

    public record RawLocation(
            @Nullable String world,
            @Nullable String worldName,
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) {}

    public record RawMail(
            int index,
            boolean legacy,
            boolean read,
            @Nullable String senderName,
            @Nullable String senderUuid,
            long timestamp,
            long expire,
            @Nullable String message
    ) {}

    public record UserFile(
            String fileName,
            UUID uuid,
            boolean npc,
            @Nullable String lastAccountName,
            @Nullable String moneyRaw,
            boolean moneyPresent,
            Map<String, RawLocation> homes,
            @Nullable RawLocation logoutLocation,
            long logoutTimestamp,
            @Nullable RawLocation lastLocation,
            List<RawMail> mail,
            @Nullable String nickname,
            List<String> ignores,
            boolean muted,
            @Nullable String muteReason,
            long muteTimestamp,
            boolean jailed,
            @Nullable String jailName,
            long jailTimestamp,
            long onlineJailMillis,
            @Nullable Boolean acceptingPay,
            @Nullable Boolean teleportEnabled,
            @Nullable Boolean teleportAuto,
            @Nullable Boolean confirmPay,
            @Nullable Boolean socialSpy,
            @Nullable Boolean msgToggle,
            Map<String, Long> kitTimestamps,
            List<String> parseErrors
    ) {}

    public record SkippedFile(String fileName, String reason) {}

    public record WarpFile(String fileName, String name, @Nullable RawLocation location, @Nullable String error) {}

    public record JailEntry(String name, @Nullable RawLocation location, @Nullable String error) {}

    public record SpawnEntry(String group, @Nullable RawLocation location, @Nullable String error) {}

    public record RawKit(String name, long delaySeconds, List<String> rawItems, @Nullable String error) {}

    public record RawWorth(String rawKey, String rawPrice) {}

    public static EssentialsSnapshot read(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Essentials directory not found: " + root);
        }
        MessageDigest digest = sha256();
        List<UserFile> users = new ArrayList<>();
        List<SkippedFile> skippedUsers = new ArrayList<>();
        List<WarpFile> warps = new ArrayList<>();
        List<JailEntry> jails = new ArrayList<>();
        List<SpawnEntry> spawns = new ArrayList<>();
        List<RawKit> kits = new ArrayList<>();
        List<RawWorth> worth = new ArrayList<>();
        List<String> enabledSigns = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        Path userdataDir = root.resolve("userdata");
        if (Files.isDirectory(userdataDir)) {
            for (Path file : sortedYml(userdataDir)) {
                String name = file.getFileName().toString();
                byte[] raw = Files.readAllBytes(file);
                hashFile(digest, "userdata/" + name, raw);
                String stem = name.substring(0, name.length() - 4);
                UUID uuid;
                try {
                    uuid = UUID.fromString(stem);
                } catch (IllegalArgumentException notUuid) {
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
                String warpName = yaml.getString("name");
                if (warpName == null || warpName.isBlank()) warpName = stem;
                ParsedLocation parsed = parseLocation(yaml, "");
                warps.add(new WarpFile(name, warpName, parsed.location(), parsed.errorIn("warps/" + name)));
            }
        }

        // EssentialsX uses jail.yml (singular); older forks sometimes use jails.yml.
        Path jailsFile = Files.isRegularFile(root.resolve("jail.yml")) ? root.resolve("jail.yml") : root.resolve("jails.yml");
        if (Files.isRegularFile(jailsFile)) {
            byte[] raw = Files.readAllBytes(jailsFile);
            hashFile(digest, jailsFile.getFileName().toString(), raw);
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(new String(raw, StandardCharsets.UTF_8));
                ConfigurationSection section = yaml.getConfigurationSection("jails");
                if (section != null) {
                    for (String jailName : section.getKeys(false).stream().sorted().toList()) {
                        ParsedLocation parsed = parseLocation(yaml, "jails." + jailName);
                        jails.add(new JailEntry(jailName, parsed.location(), parsed.errorIn(jailsFile.getFileName().toString())));
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
                    for (String group : section.getKeys(false).stream().sorted().toList()) {
                        ParsedLocation parsed = parseLocation(yaml, "spawns." + group);
                        spawns.add(new SpawnEntry(group, parsed.location(), parsed.errorIn("spawn.yml")));
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
                    if (sign != null && !sign.isBlank()) enabledSigns.add(sign.trim().toLowerCase(Locale.ROOT));
                }
            } catch (InvalidConfigurationException malformed) {
                notes.add("config.yml could not be parsed for enabledSigns: " + firstLine(malformed.getMessage()));
            }
        }

        // Kits (kits.yml in EssentialsX; older Essentials kept kits under config.yml)
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

        // Worth (worth.yml)
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

        return new EssentialsSnapshot(
                HexFormat.of().formatHex(digest.digest()),
                List.copyOf(users),
                List.copyOf(skippedUsers),
                List.copyOf(warps),
                List.copyOf(jails),
                List.copyOf(spawns),
                List.copyOf(kits),
                List.copyOf(worth),
                List.copyOf(enabledSigns),
                List.copyOf(notes)
        );
    }

    private static void parseKitsSection(@Nullable ConfigurationSection section, List<RawKit> out) {
        if (section == null) return;
        for (String kitName : section.getKeys(false).stream().sorted().toList()) {
            ConfigurationSection kitSec = section.getConfigurationSection(kitName);
            if (kitSec == null) {
                out.add(new RawKit(kitName, 0L, List.of(), "kits." + kitName + " must be a YAML section"));
                continue;
            }
            long delay = kitSec.getLong("delay", 0L);
            List<String> items = new ArrayList<>();
            List<?> rawItems = kitSec.getList("items");
            if (rawItems != null) {
                for (Object elem : rawItems) {
                    if (elem != null) {
                        String line = String.valueOf(elem).trim();
                        if (!line.isEmpty()) items.add(line);
                    }
                }
            }
            out.add(new RawKit(kitName, delay, List.copyOf(items), null));
        }
    }

    private static void parseWorthSection(@Nullable ConfigurationSection section, List<RawWorth> out) {
        if (section == null) return;
        for (String key : section.getKeys(false).stream().sorted().toList()) {
            Object raw = section.get(key);
            if (raw instanceof ConfigurationSection sub) {
                var subKeys = sub.getKeys(false);
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
        List<String> errors = new ArrayList<>();
        boolean npc = yaml.getBoolean("npc", false);
        String lastAccountName = trimToNull(yaml.getString("last-account-name"));
        if (lastAccountName == null) lastAccountName = trimToNull(yaml.getString("lastAccountName"));

        boolean moneyPresent = yaml.contains("money");
        String moneyRaw = null;
        if (moneyPresent) {
            Object raw = yaml.get("money");
            if (raw != null) moneyRaw = String.valueOf(raw).trim();
        }

        Map<String, RawLocation> homes = new LinkedHashMap<>();
        ConfigurationSection homesSection = yaml.getConfigurationSection("homes");
        if (homesSection != null) {
            for (String homeName : homesSection.getKeys(false).stream().sorted().toList()) {
                RawLocation loc = readUserLocation(yaml, "homes." + homeName, errors);
                if (loc != null) homes.put(homeName, loc);
            }
        }

        RawLocation logoutLoc = readUserLocation(yaml, "logoutlocation", errors);
        long logoutTs = yaml.getLong("timestamps.logout", 0L);
        RawLocation lastLoc = readUserLocation(yaml, "lastlocation", errors);

        List<RawMail> mail = parseMail(yaml, errors);
        String nickname = trimToNull(yaml.getString("nickname"));

        List<String> ignores = new ArrayList<>();
        if (yaml.isList("ignore")) {
            for (Object item : yaml.getList("ignore", List.of())) {
                if (item != null) ignores.add(String.valueOf(item).trim());
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

        Map<String, Long> kitTimestamps = new LinkedHashMap<>();
        ConfigurationSection kitsTsSection = yaml.getConfigurationSection("timestamps.kits");
        if (kitsTsSection != null) {
            for (String kitKey : kitsTsSection.getKeys(false).stream().sorted().toList()) {
                long ts = kitsTsSection.getLong(kitKey, 0L);
                if (ts > 0L) {
                    kitTimestamps.put(kitKey.trim().toLowerCase(Locale.ROOT), ts);
                }
            }
        }

        return new UserFile(
                fileName, uuid, npc, lastAccountName, moneyRaw, moneyPresent,
                Map.copyOf(homes), logoutLoc, logoutTs, lastLoc,
                List.copyOf(mail), nickname, List.copyOf(ignores),
                muted, muteReason, muteTs, jailed, jailName, jailTs, onlineJail,
                acceptingPay, teleportEnabled, teleportAuto, confirmPay, socialSpy, msgToggle,
                Map.copyOf(kitTimestamps),
                List.copyOf(errors)
        );
    }

    private static List<RawMail> parseMail(YamlConfiguration yaml, List<String> errors) {
        List<?> rawList = yaml.getList("mail");
        if (rawList == null || rawList.isEmpty()) return List.of();
        List<RawMail> out = new ArrayList<>();
        for (int i = 0; i < rawList.size(); i++) {
            Object entry = rawList.get(i);
            if (entry instanceof String legacyText) {
                if (!legacyText.isBlank()) {
                    out.add(new RawMail(i, true, false, null, null, 0L, 0L, legacyText));
                }
            } else if (entry instanceof Map<?, ?> map) {
                boolean legacy = boolValue(map.get("legacy"), false);
                boolean read = boolValue(map.get("read"), false);
                String msg = map.get("message") == null ? null : String.valueOf(map.get("message"));
                String senderName = map.get("sender-name") == null ? null : trimToNull(String.valueOf(map.get("sender-name")));
                String senderUuid = map.get("sender-uuid") == null ? null : trimToNull(String.valueOf(map.get("sender-uuid")));
                if ("null".equalsIgnoreCase(senderUuid)) senderUuid = null;
                long ts = longValue(map.get("timestamp"), 0L);
                long expire = longValue(map.get("expire"), 0L);
                if (msg == null || msg.isBlank()) {
                    errors.add("mail[" + i + "] has empty message");
                } else {
                    out.add(new RawMail(i, legacy, read, senderName, senderUuid, ts, expire, msg));
                }
            } else {
                errors.add("mail[" + i + "] has unsupported entry type");
            }
        }
        return out;
    }

    private record ParsedLocation(@Nullable RawLocation location, @Nullable String error) {
        @Nullable String errorIn(String fileName) {
            return error == null ? null : fileName + ": " + error;
        }
    }

    @Nullable
    private static RawLocation readUserLocation(YamlConfiguration yaml, String path, List<String> errors) {
        if (!yaml.contains(path)) return null;
        ParsedLocation parsed = parseLocation(yaml, path);
        if (parsed.error() != null) errors.add(parsed.error());
        return parsed.location();
    }

    private static ParsedLocation parseLocation(YamlConfiguration yaml, String path) {
        ConfigurationSection section = path.isEmpty() ? yaml : yaml.getConfigurationSection(path);
        if (section == null) return new ParsedLocation(null, path + " must be a location section");
        String prefix = path.isEmpty() ? "" : path + ".";
        String world = trimToNull(section.getString("world"));
        String worldName = trimToNull(section.getString("world-name"));
        if (world == null && worldName == null) {
            return new ParsedLocation(null, prefix + "world or " + prefix + "world-name is required");
        }
        try {
            double x = locationNumber(section, "x", true);
            double y = locationNumber(section, "y", true);
            double z = locationNumber(section, "z", true);
            float yaw = locationRotation(section, "yaw");
            float pitch = locationRotation(section, "pitch");
            return new ParsedLocation(new RawLocation(world, worldName, x, y, z, yaw, pitch), null);
        } catch (IllegalArgumentException invalid) {
            return new ParsedLocation(null, prefix + invalid.getMessage());
        }
    }

    private static double locationNumber(ConfigurationSection section, String key, boolean required) {
        Object raw = section.get(key);
        if (raw == null) {
            if (required) throw new IllegalArgumentException(key + " is required");
            return 0;
        }
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            if (Double.isFinite(value)) return value;
        }
        throw new IllegalArgumentException(key + " must be a finite number");
    }

    private static float locationRotation(ConfigurationSection section, String key) {
        float value = (float) locationNumber(section, key, false);
        if (!Float.isFinite(value)) throw new IllegalArgumentException(key + " is outside the finite float range");
        return value;
    }

    @Nullable
    private static Boolean optionalBoolean(YamlConfiguration yaml, String primary, String alternate, List<String> errors) {
        String key = yaml.contains(primary) ? primary : (yaml.contains(alternate) ? alternate : null);
        if (key == null) return null;
        Object raw = yaml.get(key);
        if (raw instanceof Boolean b) return b;
        errors.add(key + " is not a boolean (" + raw + ")");
        return null;
    }

    private static boolean boolValue(@Nullable Object v, boolean fallback) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return fallback;
    }

    private static long longValue(@Nullable Object v, long fallback) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static List<Path> sortedYml(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        }
    }

    private static void hashFile(MessageDigest digest, String relPath, byte[] raw) {
        digest.update(relPath.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(raw);
        digest.update((byte) 0);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    @Nullable
    private static String trimToNull(@Nullable String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String firstLine(@Nullable String message) {
        if (message == null || message.isBlank()) return "unreadable";
        return message.lines().findFirst().orElse("unreadable");
    }
}
