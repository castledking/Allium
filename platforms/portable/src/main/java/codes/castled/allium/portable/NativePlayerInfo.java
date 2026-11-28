package codes.castled.allium.portable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Atomic flatfile player metadata, nicknames, playtime, AFK session state, and mailboxes
 * shared across native Fabric (1.14–26.3) and NeoForge (1.20.2–26.3) adapters.
 */
public final class NativePlayerInfo {
    public static final int MAX_MAILBOX_SIZE = 50;

    public static final class SessionState {
        private final Map<UUID, Long> sessionStart = new ConcurrentHashMap<UUID, Long>();
        private final Map<UUID, String> afkReasons = new ConcurrentHashMap<UUID, String>();
        private final LongSupplier clock;

        public SessionState() {
            this(System::currentTimeMillis);
        }

        public SessionState(LongSupplier clock) {
            this.clock = clock;
        }

        public long now() {
            return clock.getAsLong();
        }

        public void touchSession(UUID id) {
            if (id != null) {
                sessionStart.putIfAbsent(id, clock.getAsLong());
            }
        }

        public long currentSessionMillis(UUID id) {
            Long start = sessionStart.get(id);
            return start == null ? 0L : Math.max(0L, clock.getAsLong() - start.longValue());
        }

        public long finishSession(UUID id) {
            Long start = sessionStart.remove(id);
            afkReasons.remove(id);
            return start == null ? 0L : Math.max(0L, clock.getAsLong() - start.longValue());
        }

        public boolean isAfk(UUID id) {
            return id != null && afkReasons.containsKey(id);
        }

        public String afkReason(UUID id) {
            return id == null ? null : afkReasons.get(id);
        }

        /** Toggles AFK state; returns {@code true} if the player is now AFK, {@code false} if no longer AFK. */
        public boolean toggleAfk(UUID id, String reason) {
            if (afkReasons.containsKey(id)) {
                afkReasons.remove(id);
                return false;
            }
            afkReasons.put(id, reason == null ? "" : reason.trim());
            return true;
        }

        public void clearAfk(UUID id) {
            if (id != null) afkReasons.remove(id);
        }
    }

    private static final Object LOCK = new Object();
    private final Path playersFile;
    private final Path mailDir;

    public NativePlayerInfo(Path dataDirectory) {
        this.playersFile = dataDirectory.resolve("players.properties");
        this.mailDir = dataDirectory.resolve("mail");
    }

    public void recordPresence(UUID id, String name, Homes.Position location, long nowMillis) {
        if (id == null) return;
        synchronized (LOCK) {
            try {
                Properties props = read(playersFile);
                if (name != null && !name.isEmpty()) props.setProperty("name." + id, name);
                props.setProperty("seen." + id, Long.toString(nowMillis));
                if (location != null) props.setProperty("loc." + id, location.encode());
                save(playersFile, props, "players-");
            } catch (IOException ignored) {
            }
        }
    }

    public void addPlaytime(UUID id, long millis) {
        if (id == null || millis <= 0L) return;
        synchronized (LOCK) {
            try {
                Properties props = read(playersFile);
                long current = parseLong(props.getProperty("playtime." + id), 0L);
                props.setProperty("playtime." + id, Long.toString(current + millis));
                save(playersFile, props, "players-");
            } catch (IOException ignored) {
            }
        }
    }

    public long savedPlaytimeMillis(UUID id) {
        synchronized (LOCK) {
            try {
                return parseLong(read(playersFile).getProperty("playtime." + id), 0L);
            } catch (IOException failure) {
                return 0L;
            }
        }
    }

    public long lastSeenMillis(UUID id) {
        synchronized (LOCK) {
            try {
                return parseLong(read(playersFile).getProperty("seen." + id), 0L);
            } catch (IOException failure) {
                return 0L;
            }
        }
    }

    public Homes.Position lastLocation(UUID id) {
        synchronized (LOCK) {
            try {
                String raw = read(playersFile).getProperty("loc." + id);
                return raw == null ? null : Homes.Position.decode(raw);
            } catch (IOException | IllegalArgumentException failure) {
                return null;
            }
        }
    }

    public String nameOf(UUID id) {
        synchronized (LOCK) {
            try {
                String n = read(playersFile).getProperty("name." + id);
                return n == null || n.isEmpty() ? id.toString() : n;
            } catch (IOException failure) {
                return id.toString();
            }
        }
    }

    public UUID findKnownId(String name) {
        if (name == null || name.isEmpty()) return null;
        synchronized (LOCK) {
            try {
                Properties props = read(playersFile);
                for (String key : props.stringPropertyNames()) {
                    if (!key.startsWith("name.")) continue;
                    if (name.equalsIgnoreCase(props.getProperty(key))) {
                        try {
                            return UUID.fromString(key.substring("name.".length()));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                return null;
            } catch (IOException failure) {
                return null;
            }
        }
    }

    public Set<UUID> knownIds() {
        synchronized (LOCK) {
            try {
                Properties props = read(playersFile);
                Set<UUID> out = new LinkedHashSet<UUID>();
                for (String key : props.stringPropertyNames()) {
                    if (!key.startsWith("name.")) continue;
                    try {
                        out.add(UUID.fromString(key.substring("name.".length())));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                return out;
            } catch (IOException failure) {
                return Collections.emptySet();
            }
        }
    }

    public List<String> knownNames() {
        synchronized (LOCK) {
            try {
                Properties props = read(playersFile);
                List<String> out = new ArrayList<String>();
                for (String key : props.stringPropertyNames()) {
                    if (!key.startsWith("name.")) continue;
                    String n = props.getProperty(key);
                    if (n != null && !n.isEmpty()) out.add(n);
                }
                Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
                return out;
            } catch (IOException failure) {
                return Collections.emptyList();
            }
        }
    }

    public String nicknameOf(UUID id) {
        synchronized (LOCK) {
            try {
                String nick = read(playersFile).getProperty("nick." + id);
                return nick == null || nick.isEmpty() ? null : nick;
            } catch (IOException failure) {
                return null;
            }
        }
    }

    public boolean setNickname(UUID id, String name, String rawNick) {
        synchronized (LOCK) {
            try {
                Properties props = read(playersFile);
                if (name != null && !name.isEmpty()) props.setProperty("name." + id, name);
                if (rawNick == null || rawNick.isEmpty()) {
                    props.remove("nick." + id);
                } else {
                    props.setProperty("nick." + id, rawNick);
                }
                save(playersFile, props, "players-");
                return true;
            } catch (IOException failure) {
                return false;
            }
        }
    }

    // ==================== Mail ====================

    public boolean sendMail(UUID recipient, String fromName, String message) {
        if (recipient == null || message == null || message.trim().isEmpty()) return false;
        synchronized (LOCK) {
            try {
                Path file = mailFile(recipient);
                Properties props = read(file);
                int count = parseInt(props.getProperty("count"), 0);
                if (count >= MAX_MAILBOX_SIZE) return false;
                String sender = (fromName == null || fromName.isEmpty()) ? "Console" : fromName;
                props.setProperty("m." + count, sender + ": " + message.trim());
                props.setProperty("count", Integer.toString(count + 1));
                save(file, props, "mail-");
                return true;
            } catch (IOException failure) {
                return false;
            }
        }
    }

    public List<String> readMail(UUID owner) {
        synchronized (LOCK) {
            try {
                Properties props = read(mailFile(owner));
                int count = parseInt(props.getProperty("count"), 0);
                if (count <= 0) return Collections.emptyList();
                List<String> out = new ArrayList<String>(count);
                for (int i = 0; i < count; i++) {
                    String entry = props.getProperty("m." + i);
                    if (entry != null && !entry.isEmpty()) out.add(entry);
                }
                return out;
            } catch (IOException failure) {
                return Collections.emptyList();
            }
        }
    }

    public int clearMail(UUID owner) {
        synchronized (LOCK) {
            try {
                Path file = mailFile(owner);
                Properties props = read(file);
                int count = parseInt(props.getProperty("count"), 0);
                if (count <= 0) return 0;
                Files.deleteIfExists(file);
                return count;
            } catch (IOException failure) {
                return 0;
            }
        }
    }

    // ==================== Formatting Helpers ====================

    public static String stripColorCodes(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c == '&' || c == '\u00A7') && i + 1 < raw.length() && isColorCode(raw.charAt(i + 1))) {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static String translateColorCodes(String raw) {
        if (raw == null) return "";
        char[] chars = raw.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '&' && isColorCode(chars[i + 1])) {
                chars[i] = '\u00A7';
                chars[i + 1] = Character.toLowerCase(chars[i + 1]);
            }
        }
        return new String(chars);
    }

    public static String formatDuration(long millis) {
        if (millis <= 0L) return "0s";
        long seconds = millis / 1000L;
        long days = seconds / 86400L;
        seconds %= 86400L;
        long hours = seconds / 3600L;
        seconds %= 3600L;
        long minutes = seconds / 60L;
        seconds %= 60L;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0 || sb.length() == 0) sb.append(seconds).append("s");
        return sb.toString().trim();
    }

    private static boolean isColorCode(char c) {
        char lower = Character.toLowerCase(c);
        return (lower >= '0' && lower <= '9')
                || (lower >= 'a' && lower <= 'f')
                || (lower >= 'k' && lower <= 'o')
                || lower == 'r';
    }

    private Path mailFile(UUID owner) {
        return mailDir.resolve(owner + ".properties");
    }

    private static long parseLong(String raw, long def) {
        if (raw == null) return def;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    private static int parseInt(String raw, int def) {
        if (raw == null) return def;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    private static Properties read(Path file) throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (NoSuchFileException missing) {
            return props;
        }
        return props;
    }

    private static void save(Path file, Properties props, String prefix) throws IOException {
        Path dir = file.getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, prefix, ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                props.store(writer, null);
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
