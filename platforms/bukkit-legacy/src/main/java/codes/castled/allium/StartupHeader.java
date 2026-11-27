package codes.castled.allium;

import codes.castled.allium.legacy.LegacyCompat;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles parsing and display of localized startup headers from {@code startups.txt}.
 *
 * <p>Format: each entry is keyed by {@code {locale}{number}[...content...]}.
 * The combined startup text is built from localized messages (author tag,
 * plugin tag, startup details header, two-column details body, and boot time)
 * and inserted via the {@code ${startup}} placeholder.</p>
 *
 * <p>Compiled with Java 8 bytecode so both the 1.20+ modern runtime and the
 * 1.12.2-1.19.4 compatibility runtime render the exact same startup prompt.</p>
 */
public final class StartupHeader {

    private static final Pattern ENTRY_KEY_PATTERN = Pattern.compile("^(\\w+?)\\d+\\[$");
    private static final Pattern CLOSING_BRACKET = Pattern.compile("^\\]$");
    private static final Pattern AMP_HEX_PATTERN = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final Pattern HASH_HEX_PATTERN = Pattern.compile("#([0-9a-fA-F]{6})");
    private static final Pattern LEGACY_CODE_PATTERN = Pattern.compile("[$&]([0-9a-fk-orxA-FK-ORX])");
    private static final Random RANDOM = new Random();
    private static final int COL_WIDTH = 26;

    /** A single row of up to two label/value columns in the startup details block. */
    public static final class DetailRow {
        private final String leftLabel;
        private final String leftValue;
        private final String rightLabel;
        private final String rightValue;

        public DetailRow(String leftLabel, String leftValue, String rightLabel, String rightValue) {
            this.leftLabel = leftLabel == null ? "" : leftLabel;
            this.leftValue = leftValue == null ? "" : leftValue;
            this.rightLabel = rightLabel == null ? "" : rightLabel;
            this.rightValue = rightValue == null ? "" : rightValue;
        }

        public DetailRow(String leftLabel, String leftValue) {
            this(leftLabel, leftValue, "", "");
        }
    }

    /** Immutable values inserted into the selected {@code startups.txt} template. */
    public static final class StartupInfo {
        private final String locale;
        private final String version;
        private final String authorTag;
        private final String pluginTag;
        private final String startupDetailsHeader;
        private final String bootFinished;
        private final List<DetailRow> detailRows;
        private final boolean hexColorSupported;

        private StartupInfo(Builder builder) {
            this.locale = builder.locale == null || builder.locale.isEmpty() ? "en_US" : builder.locale;
            this.version = builder.version == null ? "" : builder.version;
            this.authorTag = builder.authorTag == null ? "&7by castledking" : builder.authorTag;
            this.pluginTag = builder.pluginTag == null
                    ? "&dThe modern essentials suite for Minecraft servers"
                    : builder.pluginTag;
            this.startupDetailsHeader = builder.startupDetailsHeader == null
                    ? "&fStartup details: "
                    : builder.startupDetailsHeader;
            this.bootFinished = builder.bootFinished == null ? "" : builder.bootFinished;
            this.detailRows = Collections.unmodifiableList(new ArrayList<DetailRow>(builder.detailRows));
            this.hexColorSupported = builder.hexColorSupported;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String locale = "en_US";
            private String version = "";
            private String authorTag = "&7by castledking";
            private String pluginTag = "&dThe modern essentials suite for Minecraft servers";
            private String startupDetailsHeader = "&fStartup details: ";
            private String bootFinished = "";
            private final List<DetailRow> detailRows = new ArrayList<DetailRow>();
            private boolean hexColorSupported = true;

            public Builder locale(String locale) {
                this.locale = locale;
                return this;
            }

            public Builder version(String version) {
                this.version = version;
                return this;
            }

            public Builder authorTag(String authorTag) {
                this.authorTag = authorTag;
                return this;
            }

            public Builder pluginTag(String pluginTag) {
                this.pluginTag = pluginTag;
                return this;
            }

            public Builder startupDetailsHeader(String startupDetailsHeader) {
                this.startupDetailsHeader = startupDetailsHeader;
                return this;
            }

            public Builder bootFinished(String bootFinished) {
                this.bootFinished = bootFinished;
                return this;
            }

            public Builder addRow(String leftLabel, String leftValue, String rightLabel, String rightValue) {
                this.detailRows.add(new DetailRow(leftLabel, leftValue, rightLabel, rightValue));
                return this;
            }

            public Builder addRow(String leftLabel, String leftValue) {
                this.detailRows.add(new DetailRow(leftLabel, leftValue));
                return this;
            }

            public Builder hexColorSupported(boolean hexColorSupported) {
                this.hexColorSupported = hexColorSupported;
                return this;
            }

            public StartupInfo build() {
                return new StartupInfo(this);
            }
        }
    }

    private StartupHeader() {}

    /**
     * Loads {@code startups.txt} from {@code plugin}, renders a random matching header,
     * and writes it to the server console sender.
     */
    public static void display(Plugin plugin, StartupInfo info) {
        if (plugin == null || info == null) {
            return;
        }
        try {
            InputStream stream = plugin.getResource("startups.txt");
            if (stream == null) {
                return;
            }
            String message = renderRandom(stream, info, RANDOM);
            if (message == null) {
                return;
            }
            ConsoleCommandSender console = resolveConsole(plugin);
            if (console != null) {
                for (String line : splitLines(message)) {
                    console.sendMessage(line);
                }
            }
        } catch (Throwable t) {
            try {
                plugin.getLogger().warning("Failed to display startup header: " + t.getMessage());
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Parses {@code startups.txt} from {@code stream}, chooses a header for {@code info.locale}
     * using {@code random}, applies template replacements, and translates color codes.
     */
    public static String renderRandom(InputStream stream, StartupInfo info, Random random) {
        if (stream == null || info == null) {
            return null;
        }
        Map<String, List<String>> entries = parseStartups(stream);
        List<String> localeEntries = selectEntries(entries, info.locale);
        if (localeEntries == null || localeEntries.isEmpty()) {
            return null;
        }
        Random rng = random != null ? random : RANDOM;
        String header = localeEntries.get(rng.nextInt(localeEntries.size()));
        header = applyReplacements(header, info);
        return translateColors(header, info.hexColorSupported);
    }

    /**
     * Splits a rendered startup header into individual console lines so Paper/Adventure
     * serializes each line's leading color codes independently instead of deduplicating
     * repeated colors across newline boundaries.
     */
    static String[] splitLines(String message) {
        String normalized = message.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.split("\n", -1);
    }

    /** Parses {@code startups.txt} into a map of locale prefix -> list of content blocks. */
    public static Map<String, List<String>> parseStartups(InputStream stream) {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        if (stream == null) {
            return result;
        }
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            try {
                String currentLocale = null;
                StringBuilder currentContent = new StringBuilder();
                boolean insideEntry = false;

                String line;
                while ((line = reader.readLine()) != null) {
                    if (!insideEntry) {
                        Matcher keyMatcher = ENTRY_KEY_PATTERN.matcher(line.trim());
                        if (keyMatcher.matches()) {
                            currentLocale = keyMatcher.group(1);
                            currentContent.setLength(0);
                            insideEntry = true;
                        }
                    } else {
                        if (CLOSING_BRACKET.matcher(line.trim()).matches()) {
                            List<String> list = result.get(currentLocale);
                            if (list == null) {
                                list = new ArrayList<String>();
                                result.put(currentLocale, list);
                            }
                            list.add(currentContent.toString());
                            insideEntry = false;
                            currentLocale = null;
                        } else {
                            currentContent.append(line).append("\n");
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static List<String> selectEntries(Map<String, List<String>> entries, String langCode) {
        List<String> localeEntries = entries.get(langCode);
        if (localeEntries == null || localeEntries.isEmpty()) {
            int underscore = langCode.indexOf('_');
            if (underscore > 0) {
                localeEntries = entries.get(langCode.substring(0, underscore));
            }
        }
        if (localeEntries == null || localeEntries.isEmpty()) {
            for (List<String> list : entries.values()) {
                if (list != null && !list.isEmpty()) {
                    return list;
                }
            }
        }
        return localeEntries;
    }

    private static String applyReplacements(String header, StartupInfo info) {
        header = header.replace("${project.version}", info.version);
        header = header.replace("${version}", info.version);
        header = header.replace("${startup}", buildStartupBlock(info));
        return header;
    }

    private static String buildStartupBlock(StartupInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append(info.authorTag).append("\n&r\n");
        sb.append(info.pluginTag).append("\n&r\n");
        sb.append(info.startupDetailsHeader).append("\n&r\n");
        sb.append(buildDetailsBody(info.detailRows)).append("\n&r\n");
        sb.append(info.bootFinished);
        return sb.toString();
    }

    private static String buildDetailsBody(List<DetailRow> rows) {
        StringBuilder details = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            DetailRow row = rows.get(i);
            if (i > 0) {
                details.append("\n");
            }
            details.append(formatLine(row.leftLabel, row.leftValue, row.rightLabel, row.rightValue));
        }
        return details.toString();
    }

    /** Formats a two-column detail line with consistent alignment (matching GriefPrevention3D). */
    public static String formatLine(String leftLabel, String leftValue, String rightLabel, String rightValue) {
        String leftText = leftLabel + " " + leftValue;
        String paddedLeft = pad(leftText, COL_WIDTH);
        StringBuilder sb = new StringBuilder();
        sb.append("&7").append(paddedLeft);
        if (rightLabel != null && !rightLabel.isEmpty()) {
            sb.append(" &7").append(rightLabel).append(" ").append(rightValue);
        }
        return sb.toString();
    }

    /** Pads {@code text} with spaces to {@code width}, excluding Minecraft color codes from width calculation. */
    public static String pad(String text, int width) {
        String stripped = text.replaceAll("[&§$][0-9a-fk-orxA-FK-ORX]", "")
                .replaceAll("&#[0-9a-fA-F]{6}", "")
                .replaceAll("#[0-9a-fA-F]{6}", "");
        int padding = width - stripped.length();
        if (padding <= 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        for (int i = 0; i < padding; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Translates {@code &#RRGGBB}, {@code #RRGGBB}, {@code &}, and {@code $} color codes
     * to Minecraft legacy {@code §} format (stripping hex colors on pre-1.16 servers).
     */
    public static String translateColors(String text, boolean hexSupported) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (hexSupported) {
            text = replaceHexPattern(AMP_HEX_PATTERN, text);
            text = replaceHexPattern(HASH_HEX_PATTERN, text);
        } else {
            text = AMP_HEX_PATTERN.matcher(text).replaceAll("");
            text = HASH_HEX_PATTERN.matcher(text).replaceAll("");
        }
        return LEGACY_CODE_PATTERN.matcher(text).replaceAll("\u00A7$1");
    }

    private static String replaceHexPattern(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1).toLowerCase();
            String legacy = "\u00A7x"
                    + "\u00A7" + hex.charAt(0)
                    + "\u00A7" + hex.charAt(1)
                    + "\u00A7" + hex.charAt(2)
                    + "\u00A7" + hex.charAt(3)
                    + "\u00A7" + hex.charAt(4)
                    + "\u00A7" + hex.charAt(5);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(legacy));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** Returns a concise server implementation + Minecraft version string, e.g. {@code "Paper 1.20.6"}. */
    public static String detectPlatformVersion(Server server) {
        String platform = detectPlatformName(server);
        String bukkitVersion = server != null ? server.getBukkitVersion() : null;
        ServerVersion parsed = ServerVersion.parse(bukkitVersion);
        if (parsed.major > 0) {
            String numeric = parsed.patch > 0
                    ? parsed.major + "." + parsed.minor + "." + parsed.patch
                    : parsed.major + "." + parsed.minor;
            return platform + " " + numeric;
        }
        return bukkitVersion != null && !bukkitVersion.isEmpty() ? platform + " " + bukkitVersion : platform;
    }

    /** Returns {@code true} if the server supports 1.16+ {@code §x} RGB hex colors. */
    public static boolean supportsHexColors(Server server) {
        if (server == null) {
            return true;
        }
        ServerVersion parsed = ServerVersion.parse(server.getBukkitVersion());
        return parsed.major <= 0 || parsed.isAtLeast(1, 16, 0);
    }

    private static String detectPlatformName(Server server) {
        try {
            if (server != null && "Canvas".equalsIgnoreCase(server.getName())) {
                return "Canvas";
            }
        } catch (Throwable ignored) {
        }
        if (classExists("io.papermc.paper.threadedregions.RegionizedServer")
                || classExists("io.papermc.folia.FoliaConfig")
                || classExists("io.folia.Folia")) {
            return "Folia";
        }
        if (classExists("org.purpurmc.purpur.PurpurConfig") || classExists("net.pl3x.purpur.PurpurConfig")) {
            return "Purpur";
        }
        String detected = LegacyCompat.platformName(server);
        if ("CraftBukkit".equals(detected) && classExists("org.spigotmc.SpigotConfig")) {
            return "Spigot";
        }
        return detected;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ConsoleCommandSender resolveConsole(Plugin plugin) {
        try {
            Server server = plugin.getServer();
            if (server != null && server.getConsoleSender() != null) {
                return server.getConsoleSender();
            }
        } catch (Throwable ignored) {
        }
        try {
            return Bukkit.getConsoleSender();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
