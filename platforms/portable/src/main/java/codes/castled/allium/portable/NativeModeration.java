package codes.castled.allium.portable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Atomic file-backed moderation store for native Fabric (1.14–26.3) and NeoForge (1.20.2–26.3):
 * mutes, jails, jailed players (with return locations), warnings, and staff notes, plus transient
 * per-server freeze and vanish session state.
 */
public final class NativeModeration {
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([smhdw])", Pattern.CASE_INSENSITIVE);
    private static final long MAX_DURATION_MS = 3650L * 86400L * 1000L;

    private final Path dataDirectory;
    private final Path moderationFile;
    private final Path warningsDir;
    private final Path notesDir;

    public static final class MuteRecord {
        public final UUID playerId;
        public final String playerName;
        public final String actor;
        public final String reason;
        public final long expiresAtMillis;

        public MuteRecord(UUID playerId, String playerName, String actor, String reason, long expiresAtMillis) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.actor = actor;
            this.reason = reason;
            this.expiresAtMillis = expiresAtMillis;
        }

        public boolean isPermanent() {
            return expiresAtMillis <= 0L;
        }

        public boolean isExpired(long nowMillis) {
            return expiresAtMillis > 0L && nowMillis >= expiresAtMillis;
        }

        public long remainingMillis(long nowMillis) {
            if (isPermanent()) return 0L;
            return Math.max(0L, expiresAtMillis - nowMillis);
        }
    }

    public static final class JailRecord {
        public final UUID playerId;
        public final String playerName;
        public final String jailName;
        public final String actor;
        public final String reason;
        public final long expiresAtMillis;
        public final Homes.Position returnPosition;

        public JailRecord(UUID playerId, String playerName, String jailName, String actor,
                          String reason, long expiresAtMillis, Homes.Position returnPosition) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.jailName = jailName;
            this.actor = actor;
            this.reason = reason;
            this.expiresAtMillis = expiresAtMillis;
            this.returnPosition = returnPosition;
        }

        public boolean isPermanent() {
            return expiresAtMillis <= 0L;
        }

        public boolean isExpired(long nowMillis) {
            return expiresAtMillis > 0L && nowMillis >= expiresAtMillis;
        }

        public long remainingMillis(long nowMillis) {
            if (isPermanent()) return 0L;
            return Math.max(0L, expiresAtMillis - nowMillis);
        }
    }

    public static final class LogEntry {
        public final int id;
        public final long createdAtMillis;
        public final String actor;
        public final String text;

        public LogEntry(int id, long createdAtMillis, String actor, String text) {
            this.id = id;
            this.createdAtMillis = createdAtMillis;
            this.actor = actor;
            this.text = text;
        }
    }

    public static final class SessionState {
        private final Set<UUID> frozen = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());
        private final Set<UUID> vanished = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());

        public boolean toggleFreeze(UUID playerId) {
            if (frozen.remove(playerId)) return false;
            frozen.add(playerId);
            return true;
        }

        public boolean isFrozen(UUID playerId) {
            return playerId != null && frozen.contains(playerId);
        }

        public void setFrozen(UUID playerId, boolean state) {
            if (playerId == null) return;
            if (state) frozen.add(playerId);
            else frozen.remove(playerId);
        }

        public boolean toggleVanish(UUID playerId) {
            if (vanished.remove(playerId)) return false;
            vanished.add(playerId);
            return true;
        }

        public boolean isVanished(UUID playerId) {
            return playerId != null && vanished.contains(playerId);
        }

        public void setVanished(UUID playerId, boolean state) {
            if (playerId == null) return;
            if (state) vanished.add(playerId);
            else vanished.remove(playerId);
        }

        public Set<UUID> vanishedIds() {
            return Collections.unmodifiableSet(vanished);
        }
    }

    public NativeModeration(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        this.moderationFile = dataDirectory.resolve("moderation.properties");
        this.warningsDir = dataDirectory.resolve("warnings");
        this.notesDir = dataDirectory.resolve("notes");
    }

    // ==================== Duration Parsing ====================

    /**
     * Parses a duration such as {@code 30s}, {@code 10m}, {@code 2h}, {@code 7d}, {@code 1w},
     * {@code 1d12h}, or {@code perm}/{@code permanent}.
     *
     * @return {@code 0L} for permanent, positive milliseconds for timed, or {@code -1L} if invalid.
     */
    public static long parseDurationMillis(String raw) {
        if (raw == null) return -1L;
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) return -1L;
        if ("perm".equals(trimmed) || "permanent".equals(trimmed) || "forever".equals(trimmed)) {
            return 0L;
        }
        Matcher m = DURATION_PART.matcher(trimmed);
        long total = 0L;
        int matchedLen = 0;
        while (m.find()) {
            if (m.start() != matchedLen) return -1L;
            matchedLen = m.end();
            long value;
            try {
                value = Long.parseLong(m.group(1));
            } catch (NumberFormatException overflow) {
                return -1L;
            }
            if (value <= 0L) return -1L;
            long mult;
            switch (m.group(2).toLowerCase(Locale.ROOT)) {
                case "s": mult = 1000L; break;
                case "m": mult = 60L * 1000L; break;
                case "h": mult = 3600L * 1000L; break;
                case "d": mult = 86400L * 1000L; break;
                case "w": mult = 7L * 86400L * 1000L; break;
                default: return -1L;
            }
            if (value > MAX_DURATION_MS / mult) {
                total = MAX_DURATION_MS;
            } else {
                total = Math.min(MAX_DURATION_MS, total + value * mult);
            }
        }
        return matchedLen == trimmed.length() && total > 0L ? total : -1L;
    }

    public static String formatDuration(long durationMillis) {
        return durationMillis <= 0L ? "permanent" : NativePlayerInfo.formatDuration(durationMillis);
    }

    // ==================== Mutes ====================

    public synchronized boolean mute(UUID playerId, String playerName, String actor,
                                     long durationMillis, String reason, long nowMillis) {
        Properties props = load(moderationFile);
        String prefix = "mute." + playerId + ".";
        long until = durationMillis > 0L ? nowMillis + durationMillis : 0L;
        props.setProperty(prefix + "name", playerName == null ? playerId.toString() : playerName);
        props.setProperty(prefix + "by", actor == null ? "Console" : actor);
        props.setProperty(prefix + "reason", reason == null || reason.trim().isEmpty() ? "Muted by staff." : reason.trim());
        props.setProperty(prefix + "until", Long.toString(until));
        return save(moderationFile, props, "Allium native moderation");
    }

    public synchronized boolean unmute(UUID playerId) {
        Properties props = load(moderationFile);
        String prefix = "mute." + playerId + ".";
        boolean existed = props.containsKey(prefix + "until") || props.containsKey(prefix + "reason");
        if (!existed) return false;
        removePrefix(props, prefix);
        return save(moderationFile, props, "Allium native moderation");
    }

    public synchronized MuteRecord activeMute(UUID playerId, long nowMillis) {
        Properties props = load(moderationFile);
        String prefix = "mute." + playerId + ".";
        String rawUntil = props.getProperty(prefix + "until");
        if (rawUntil == null) return null;
        long until = parseLong(rawUntil, 0L);
        if (until > 0L && nowMillis >= until) {
            removePrefix(props, prefix);
            save(moderationFile, props, "Allium native moderation");
            return null;
        }
        return new MuteRecord(
                playerId,
                props.getProperty(prefix + "name", playerId.toString()),
                props.getProperty(prefix + "by", "Console"),
                props.getProperty(prefix + "reason", "Muted by staff."),
                until
        );
    }

    // ==================== Jails ====================

    public synchronized boolean setJail(String name, Homes.Position pos) {
        String key = normalizeKey(name);
        if (key == null || pos == null) return false;
        Properties props = load(moderationFile);
        props.setProperty("jail." + key + ".name", name.trim());
        props.setProperty("jail." + key + ".pos", encodePos(pos));
        return save(moderationFile, props, "Allium native moderation");
    }

    public synchronized boolean deleteJail(String name) {
        String key = normalizeKey(name);
        if (key == null) return false;
        Properties props = load(moderationFile);
        String prefix = "jail." + key + ".";
        if (!props.containsKey(prefix + "pos")) return false;
        removePrefix(props, prefix);
        return save(moderationFile, props, "Allium native moderation");
    }

    public synchronized Homes.Position jailPosition(String name) {
        String key = normalizeKey(name);
        if (key == null) return null;
        Properties props = load(moderationFile);
        return decodePos(props.getProperty("jail." + key + ".pos"));
    }

    public synchronized String jailDisplayName(String name) {
        String key = normalizeKey(name);
        if (key == null) return null;
        Properties props = load(moderationFile);
        if (!props.containsKey("jail." + key + ".pos")) return null;
        return props.getProperty("jail." + key + ".name", key);
    }

    public synchronized List<String> jailNames() {
        Properties props = load(moderationFile);
        Set<String> names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("jail.") && key.endsWith(".pos")) {
                String id = key.substring("jail.".length(), key.length() - ".pos".length());
                names.add(props.getProperty("jail." + id + ".name", id));
            }
        }
        return new ArrayList<String>(names);
    }

    public synchronized boolean jail(UUID playerId, String playerName, String jailName, String actor,
                                     long durationMillis, String reason, Homes.Position returnPos, long nowMillis) {
        String display = jailDisplayName(jailName);
        if (display == null) return false;
        Properties props = load(moderationFile);
        String prefix = "jailed." + playerId + ".";
        long until = durationMillis > 0L ? nowMillis + durationMillis : 0L;
        props.setProperty(prefix + "name", playerName == null ? playerId.toString() : playerName);
        props.setProperty(prefix + "jail", display);
        props.setProperty(prefix + "by", actor == null ? "Console" : actor);
        props.setProperty(prefix + "reason", reason == null || reason.trim().isEmpty() ? "Jailed by staff." : reason.trim());
        props.setProperty(prefix + "until", Long.toString(until));
        if (returnPos != null) {
            props.setProperty(prefix + "return", encodePos(returnPos));
        }
        return save(moderationFile, props, "Allium native moderation");
    }

    public synchronized JailRecord unjail(UUID playerId) {
        Properties props = load(moderationFile);
        String prefix = "jailed." + playerId + ".";
        if (!props.containsKey(prefix + "jail")) return null;
        JailRecord existing = readJailRecord(props, playerId);
        removePrefix(props, prefix);
        save(moderationFile, props, "Allium native moderation");
        return existing;
    }

    public synchronized JailRecord activeJail(UUID playerId, long nowMillis) {
        Properties props = load(moderationFile);
        String prefix = "jailed." + playerId + ".";
        if (!props.containsKey(prefix + "jail")) return null;
        JailRecord rec = readJailRecord(props, playerId);
        if (rec.isExpired(nowMillis)) {
            removePrefix(props, prefix);
            save(moderationFile, props, "Allium native moderation");
            return null;
        }
        return rec;
    }

    public synchronized List<JailRecord> jailedPlayers(long nowMillis) {
        Properties props = load(moderationFile);
        List<JailRecord> list = new ArrayList<JailRecord>();
        boolean dirty = false;
        for (String key : new ArrayList<String>(props.stringPropertyNames())) {
            if (!key.startsWith("jailed.") || !key.endsWith(".jail")) continue;
            String rawId = key.substring("jailed.".length(), key.length() - ".jail".length());
            UUID id;
            try {
                id = UUID.fromString(rawId);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            JailRecord rec = readJailRecord(props, id);
            if (rec.isExpired(nowMillis)) {
                removePrefix(props, "jailed." + id + ".");
                dirty = true;
            } else {
                list.add(rec);
            }
        }
        if (dirty) {
            save(moderationFile, props, "Allium native moderation");
        }
        Collections.sort(list, new Comparator<JailRecord>() {
            @Override
            public int compare(JailRecord a, JailRecord b) {
                return a.playerName.compareToIgnoreCase(b.playerName);
            }
        });
        return list;
    }

    private static JailRecord readJailRecord(Properties props, UUID id) {
        String prefix = "jailed." + id + ".";
        return new JailRecord(
                id,
                props.getProperty(prefix + "name", id.toString()),
                props.getProperty(prefix + "jail", ""),
                props.getProperty(prefix + "by", "Console"),
                props.getProperty(prefix + "reason", "Jailed by staff."),
                parseLong(props.getProperty(prefix + "until"), 0L),
                decodePos(props.getProperty(prefix + "return"))
        );
    }

    // ==================== Warnings & Notes ====================

    public synchronized LogEntry addWarning(UUID playerId, String actor, String reason, long nowMillis) {
        return appendLog(warningsDir.resolve(playerId + ".properties"), actor, reason, nowMillis);
    }

    public synchronized List<LogEntry> warnings(UUID playerId) {
        return readLog(warningsDir.resolve(playerId + ".properties"));
    }

    public synchronized LogEntry addNote(UUID playerId, String actor, String text, long nowMillis) {
        return appendLog(notesDir.resolve(playerId + ".properties"), actor, text, nowMillis);
    }

    public synchronized List<LogEntry> notes(UUID playerId) {
        return readLog(notesDir.resolve(playerId + ".properties"));
    }

    public synchronized boolean removeNote(UUID playerId, int oneBasedIndex) {
        Path file = notesDir.resolve(playerId + ".properties");
        List<LogEntry> entries = readLog(file);
        if (oneBasedIndex < 1 || oneBasedIndex > entries.size()) return false;
        entries.remove(oneBasedIndex - 1);
        return writeLog(file, entries);
    }

    private LogEntry appendLog(Path file, String actor, String text, long nowMillis) {
        List<LogEntry> entries = readLog(file);
        LogEntry added = new LogEntry(entries.size() + 1, nowMillis,
                actor == null ? "Console" : actor, text == null ? "" : text.trim());
        entries.add(added);
        return writeLog(file, entries) ? added : null;
    }

    private List<LogEntry> readLog(Path file) {
        Properties props = load(file);
        int count = (int) parseLong(props.getProperty("count"), 0L);
        List<LogEntry> entries = new ArrayList<LogEntry>(Math.max(0, count));
        for (int i = 1; i <= count; i++) {
            String text = props.getProperty("entry." + i + ".text");
            if (text == null) continue;
            long at = parseLong(props.getProperty("entry." + i + ".at"), 0L);
            String by = props.getProperty("entry." + i + ".by", "Console");
            entries.add(new LogEntry(entries.size() + 1, at, by, text));
        }
        return entries;
    }

    private boolean writeLog(Path file, List<LogEntry> entries) {
        Properties props = new Properties();
        props.setProperty("count", Integer.toString(entries.size()));
        for (int i = 0; i < entries.size(); i++) {
            LogEntry e = entries.get(i);
            int idx = i + 1;
            props.setProperty("entry." + idx + ".at", Long.toString(e.createdAtMillis));
            props.setProperty("entry." + idx + ".by", e.actor);
            props.setProperty("entry." + idx + ".text", e.text);
        }
        return save(file, props, "Allium native moderation log");
    }

    // ==================== Utilities ====================

    private static String normalizeKey(String raw) {
        if (raw == null) return null;
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        if (clean.isEmpty() || !clean.matches("[a-z0-9_-]{1,32}")) return null;
        return clean;
    }

    private static void removePrefix(Properties props, String prefix) {
        for (String key : new ArrayList<String>(props.stringPropertyNames())) {
            if (key.startsWith(prefix)) {
                props.remove(key);
            }
        }
    }

    private static String encodePos(Homes.Position pos) {
        return pos.world + ";" + pos.x + ";" + pos.y + ";" + pos.z + ";" + pos.yaw + ";" + pos.pitch;
    }

    private static Homes.Position decodePos(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] parts = raw.split(";");
        if (parts.length != 6) return null;
        try {
            return new Homes.Position(
                    parts[0],
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]),
                    Float.parseFloat(parts[4]),
                    Float.parseFloat(parts[5])
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null) return fallback;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Properties load(Path file) {
        Properties props = new Properties();
        if (!Files.isRegularFile(file)) return props;
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException ignored) {
        }
        return props;
    }

    private static boolean save(Path file, Properties props, String comment) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try {
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    props.store(out, comment);
                }
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }
}
