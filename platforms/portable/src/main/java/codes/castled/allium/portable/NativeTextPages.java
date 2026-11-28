package codes.castled.allium.portable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Pure Java 8 Essentials-compatible text page reader ({@code info.txt}, {@code rules.txt},
 * {@code motd.txt}, {@code custom.txt}, {@code broadcasts.txt}) shared across all native
 * Fabric (1.14–26.3) and NeoForge (1.20.2–26.3) adapters.
 *
 * <p>Supports {@code #section,alias} headers, {@code ##} comments, player-specific files such as
 * {@code info_steve.txt}, 9-line pagination, {@code &} color codes, and {@code {player}},
 * {@code {displayname}}, {@code {online}}, {@code {max}}, and {@code {server}} placeholders.
 */
public final class NativeTextPages {
    public static final int PAGE_SIZE = 9;
    private static final Pattern FORMAT_TAG = Pattern.compile(
            "<(?:/?(?:click|hover|gradient|rainbow)(?::(?:\"[^\"]*\"|'[^']*'|[^\"'<>])*+)?|#[0-9a-fA-F]{6})>",
            Pattern.CASE_INSENSITIVE);

    public static final class Page {
        public final List<String> lines;
        public final int page;
        public final int pages;

        public Page(List<String> lines, int page, int pages) {
            this.lines = lines;
            this.page = page;
            this.pages = pages;
        }
    }

    public static final class Document {
        private final List<String> index;
        private final Map<String, List<String>> sections;
        private final Map<String, String> primaryNames;

        private Document(List<String> index, Map<String, List<String>> sections, Map<String, String> primaryNames) {
            this.index = index;
            this.sections = sections;
            this.primaryNames = primaryNames;
        }

        public static Document parse(List<String> rawLines) {
            List<String> index = new ArrayList<String>();
            Map<String, List<String>> sections = new LinkedHashMap<String, List<String>>();
            Map<String, String> names = new LinkedHashMap<String, String>();
            List<String> current = index;
            for (String raw : rawLines) {
                String line = stripLineEnd(raw);
                if (line.startsWith("##")) {
                    continue;
                }
                if (line.startsWith("#")) {
                    List<String> section = new ArrayList<String>();
                    String primary = null;
                    for (String title : line.substring(1).split(",")) {
                        String key = title.trim().replace(' ', '_').toLowerCase(Locale.ROOT);
                        if (key.isEmpty()) continue;
                        if (primary == null) primary = key;
                        if (!sections.containsKey(key)) sections.put(key, section);
                        if (!names.containsKey(key)) names.put(key, primary);
                    }
                    current = primary == null ? new ArrayList<String>() : section;
                    continue;
                }
                current.add(line);
            }
            Map<String, List<String>> frozenSections = new LinkedHashMap<String, List<String>>();
            Map<List<String>, List<String>> cache = new IdentityHashMap<List<String>, List<String>>();
            for (Map.Entry<String, List<String>> entry : sections.entrySet()) {
                List<String> trimmed = cache.get(entry.getValue());
                if (trimmed == null) {
                    trimmed = trimTrailingBlank(entry.getValue());
                    cache.put(entry.getValue(), trimmed);
                }
                frozenSections.put(entry.getKey(), trimmed);
            }
            return new Document(trimTrailingBlank(index),
                    Collections.unmodifiableMap(frozenSections),
                    Collections.unmodifiableMap(names));
        }

        public List<String> index() {
            return index;
        }

        public List<String> section(String name) {
            return name == null ? null : sections.get(name.toLowerCase(Locale.ROOT));
        }

        public String primaryName(String name) {
            return name == null ? null : primaryNames.get(name.toLowerCase(Locale.ROOT));
        }

        public List<String> sectionNames() {
            return Collections.unmodifiableList(new ArrayList<String>(sections.keySet()));
        }

        public List<String> primarySectionNames() {
            List<String> out = new ArrayList<String>();
            for (String name : sections.keySet()) {
                if (name.equals(primaryNames.get(name))) {
                    out.add(name);
                }
            }
            return out;
        }

        public List<String> nonEmptyPrimarySections() {
            List<String> out = new ArrayList<String>();
            for (String name : primarySectionNames()) {
                List<String> lines = sections.get(name);
                if (lines != null && !lines.isEmpty()) out.add(name);
            }
            return out;
        }
    }

    public static final class BroadcastState {
        private final AtomicInteger cursor = new AtomicInteger(0);

        public String next(List<String> names) {
            if (names == null || names.isEmpty()) return null;
            int idx = Math.floorMod(cursor.getAndIncrement(), names.size());
            return names.get(idx);
        }
    }

    private final Path dataDirectory;

    public NativeTextPages(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public static Page paginate(List<String> lines, int requested) {
        int pages = Math.max(1, (lines.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(1, Math.min(requested, pages));
        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, lines.size());
        return new Page(new ArrayList<String>(lines.subList(Math.min(from, lines.size()), to)), page, pages);
    }

    public static String renderLine(String line, String playerName, String displayName,
                                    int online, int maxPlayers, String serverName) {
        if (line == null) return "";
        String stripped = FORMAT_TAG.matcher(line).replaceAll("");
        String safePlayer = playerName == null ? "Console" : playerName;
        String safeDisplay = displayName == null || displayName.isEmpty() ? safePlayer : displayName;
        String safeServer = serverName == null || serverName.isEmpty() ? "Minecraft" : serverName;
        String filled = stripped
                .replace("{server}", safeServer)
                .replace("{online}", Integer.toString(online))
                .replace("{max}", Integer.toString(maxPlayers))
                .replace("{displayname}", safeDisplay)
                .replace("{player}", safePlayer);
        return NativePlayerInfo.translateColorCodes(filled);
    }

    public Document read(String filename, String viewerName) {
        Path file = resolveFile(filename, viewerName);
        if (!Files.isRegularFile(file)) {
            ensureDefaultFile(filename, dataDirectory.resolve(filename));
            file = resolveFile(filename, viewerName);
        }
        if (!Files.isRegularFile(file)) return null;
        try {
            return Document.parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            return null;
        }
    }

    private Path resolveFile(String filename, String viewerName) {
        Path file = dataDirectory.resolve(filename);
        if ("custom.txt".equals(filename) && !Files.isRegularFile(file)) {
            Path legacy = dataDirectory.resolve("customtext.txt");
            if (Files.isRegularFile(legacy)) file = legacy;
        }
        if (viewerName != null) {
            String lower = viewerName.toLowerCase(Locale.ROOT);
            if (lower.matches("[a-z0-9_.-]{1,32}")) {
                Path personal = dataDirectory.resolve(filename.replace(".txt", "_" + lower + ".txt"));
                if (Files.isRegularFile(personal)) return personal;
            }
        }
        return file;
    }

    private void ensureDefaultFile(String filename, Path target) {
        String content = defaultContent(filename);
        if (content == null || Files.exists(target)) return;
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), filename, ".tmp");
            try {
                Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException ignored) {
        }
    }

    private static String defaultContent(String filename) {
        if ("rules.txt".equals(filename)) {
            return "## Edit rules.txt to customize /rules. Use #section to add chapters.\n"
                    + "&6Server Rules:\n"
                    + "&71. &fBe respectful to other players.\n"
                    + "&72. &fNo griefing, cheating, or exploiting.\n"
                    + "&73. &fHave fun!\n";
        }
        if ("motd.txt".equals(filename)) {
            return "## Edit motd.txt to customize /motd.\n"
                    + "&6Welcome, &f{displayname}&6!\n"
                    + "&7Players online: &f{online}&7/&f{max}\n"
                    + "&7Type &f/rules &7or &f/info &7to get started.\n";
        }
        if ("info.txt".equals(filename)) {
            return "## Edit info.txt to customize /info. Add #section,alias headers for chapters.\n"
                    + "&6Server Information\n"
                    + "&7Use &f/rules &7for server rules or &f/allium help &7for commands.\n";
        }
        if ("custom.txt".equals(filename)) {
            return "## Edit custom.txt to add custom text chapters for /customtext <section>.\n"
                    + "&6Custom Text\n"
                    + "&7Add &f#section &7headers in custom.txt to create pages.\n";
        }
        if ("broadcasts.txt".equals(filename)) {
            return "## Add #name sections in broadcasts.txt for /broadcasts send <name> and /broadcasts next.\n"
                    + "#welcome\n"
                    + "&6[Tip] &fUse &e/sethome &fand &e/home &fto save your base!\n";
        }
        return null;
    }

    private static List<String> trimTrailingBlank(List<String> lines) {
        int end = lines.size();
        while (end > 0 && isBlank(lines.get(end - 1))) {
            end--;
        }
        return Collections.unmodifiableList(new ArrayList<String>(lines.subList(0, end)));
    }

    private static boolean isBlank(String s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) return false;
        }
        return true;
    }

    private static String stripLineEnd(String raw) {
        if (raw == null) return "";
        int end = raw.length();
        while (end > 0 && (raw.charAt(end - 1) == '\r' || raw.charAt(end - 1) == '\n')) {
            end--;
        }
        return raw.substring(0, end);
    }
}
