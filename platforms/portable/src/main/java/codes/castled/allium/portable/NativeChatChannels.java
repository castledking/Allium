package codes.castled.allium.portable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/**
 * Atomic file-backed chat channel state and message formatting/routing for native Fabric
 * (1.14–26.3) and NeoForge (1.20.2–26.3).
 */
public final class NativeChatChannels {
    public enum Channel {
        GLOBAL("global", "Global"),
        LOCAL("local", "Local"),
        STAFF("staff", "Staff");

        public final String id;
        public final String title;

        Channel(String id, String title) {
            this.id = id;
            this.title = title;
        }

        public static Channel fromName(String raw) {
            if (raw == null) return null;
            String clean = raw.trim().toLowerCase(Locale.ROOT);
            if ("g".equals(clean) || "global".equals(clean) || "default".equals(clean) || "shout".equals(clean)) {
                return GLOBAL;
            }
            if ("l".equals(clean) || "local".equals(clean)) {
                return LOCAL;
            }
            if ("a".equals(clean) || "ac".equals(clean) || "staff".equals(clean)
                    || "staffchat".equals(clean) || "staff-chat".equals(clean)) {
                return STAFF;
            }
            return null;
        }
    }

    public static final class RoutedMessage {
        public final Channel channel;
        public final String message;
        public final boolean question;

        public RoutedMessage(Channel channel, String message, boolean question) {
            this.channel = channel;
            this.message = message;
            this.question = question;
        }
    }

    private final Path file;

    public NativeChatChannels(Path dataDirectory) {
        this.file = dataDirectory.resolve("channels.properties");
    }

    public static Channel defaultChannel(NativeConfig config) {
        if (config == null) return Channel.GLOBAL;
        String raw = config.raw().getString("chat.channels.default-write-channel", "default");
        Channel parsed = Channel.fromName(raw);
        return parsed == null || parsed == Channel.STAFF ? Channel.GLOBAL : parsed;
    }

    public static int localRadius(NativeConfig config) {
        if (config == null) return 100;
        return Math.max(1, config.raw().getInt("chat.channels.local-channel.radius", 100));
    }

    public synchronized Channel activeChannel(UUID playerId, NativeConfig config) {
        if (playerId == null) return defaultChannel(config);
        Properties props = load();
        Channel parsed = Channel.fromName(props.getProperty("player." + playerId));
        return parsed != null ? parsed : defaultChannel(config);
    }

    public synchronized boolean setActiveChannel(UUID playerId, Channel channel) {
        if (playerId == null || channel == null) return false;
        Properties props = load();
        props.setProperty("player." + playerId, channel.id);
        return save(props);
    }

    /**
     * Resolves shortcut prefixes ({@code #} for staff chat, {@code >} for global shout,
     * and configured question prefix {@code ?}) against the player's active channel.
     */
    public static RoutedMessage resolveOutgoing(Channel active, boolean isStaff,
                                                NativeConfig config, String rawMessage) {
        String msg = rawMessage == null ? "" : rawMessage;
        if (isStaff && msg.startsWith("#") && msg.length() > 1 && !msg.substring(1).trim().isEmpty()) {
            return new RoutedMessage(Channel.STAFF, msg.substring(1).trim(), false);
        }
        if (msg.startsWith(">") && msg.length() > 1 && !msg.substring(1).trim().isEmpty()) {
            return new RoutedMessage(Channel.GLOBAL, msg.substring(1).trim(), false);
        }
        boolean questionEnabled = config == null || config.raw().getBoolean("chat.question.enabled", true);
        String qPrefix = config == null ? "?" : config.raw().getString("chat.question.prefix", "?");
        if (questionEnabled && qPrefix != null && !qPrefix.isEmpty()
                && msg.startsWith(qPrefix) && msg.length() > qPrefix.length()) {
            String rest = msg.substring(qPrefix.length()).trim();
            if (!rest.isEmpty()) {
                return new RoutedMessage(Channel.GLOBAL, rest, true);
            }
        }
        Channel target = active == null ? defaultChannel(config) : active;
        if (target == Channel.STAFF && !isStaff) {
            target = defaultChannel(config);
        }
        return new RoutedMessage(target, msg, false);
    }

    public static String formatChat(NativeConfig config, RoutedMessage routed,
                                    String playerName, String displayName, boolean allowMessageColors) {
        String template;
        if (routed.question) {
            template = config == null
                    ? "&8[&e?&8] &f{displayname}&8: &e{message}"
                    : config.raw().getString("chat.question.format", "&8[&e?&8] &f{displayname}&8: &e{message}");
        } else if (routed.channel == Channel.STAFF) {
            template = config == null
                    ? "&8[&cStaff&8] &f{displayname}&8: &f{message}"
                    : config.raw().getString("chat.channels.list.staff-chat.format",
                            "&8[&cStaff&8] &f{displayname}&8: &f{message}");
        } else if (routed.channel == Channel.LOCAL) {
            template = config == null
                    ? "&8[&7Local&8] &f{displayname}&8: &7{message}"
                    : config.raw().getString("chat.channels.local-channel.format",
                            "&8[&7Local&8] &f{displayname}&8: &7{message}");
        } else {
            template = config == null
                    ? "&7{displayname}&8: &f{message}"
                    : config.raw().getString("chat.channels.list.default.format",
                            "&7{displayname}&8: &f{message}");
        }
        return renderTemplate(template, playerName, displayName, routed.message, allowMessageColors);
    }

    public static String formatAction(NativeConfig config, String playerName, String displayName,
                                      String action, boolean allowMessageColors) {
        String template = config == null
                ? "&7* &f{displayname} &7{message}"
                : config.raw().getString("chat.action-format", "&7* &f{displayname} &7{message}");
        return renderTemplate(template, playerName, displayName, action, allowMessageColors);
    }

    private static String renderTemplate(String template, String playerName, String displayName,
                                         String body, boolean allowMessageColors) {
        String safeName = playerName == null ? "" : playerName;
        String safeDisplay = displayName == null || displayName.isEmpty() ? safeName : displayName;
        String formattedBody = allowMessageColors
                ? NativePlayerInfo.translateColorCodes(body == null ? "" : body)
                : (body == null ? "" : body);
        // Translate colors in template/displayname first, then inject message so player text
        // does not get unwanted & color translation unless explicitly permitted.
        String token = "\u0000MSG\u0000";
        String rendered = template
                .replace("<prefix>", "")
                .replace("{prefix}", "")
                .replace("<suffix>", "")
                .replace("{suffix}", "")
                .replace("<player>", safeName)
                .replace("<name>", safeName)
                .replace("{player}", safeName)
                .replace("{name}", safeName)
                .replace("%name%", safeName)
                .replace("<display_name>", safeDisplay)
                .replace("<displayname>", safeDisplay)
                .replace("{display_name}", safeDisplay)
                .replace("{displayname}", safeDisplay)
                .replace("<message>", token)
                .replace("{message}", token);
        return NativePlayerInfo.translateColorCodes(rendered).replace(token, formattedBody);
    }

    private Properties load() {
        Properties props = new Properties();
        if (!Files.isRegularFile(file)) return props;
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException ignored) {
        }
        return props;
    }

    private boolean save(Properties props) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try {
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    props.store(out, "Allium native chat channels");
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
