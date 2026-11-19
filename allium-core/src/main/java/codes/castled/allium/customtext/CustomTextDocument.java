package codes.castled.allium.customtext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code customtext.txt}, in Essentials' {@code custom.txt} layout so existing files carry over:
 * a line {@code #vote,voting} starts the section {@code vote} (also reachable as {@code voting}),
 * lines before the first section are the index, and {@code ##} starts a comment line. Section
 * names are case-insensitive and spaces in them become underscores, as in Essentials.
 */
public final class CustomTextDocument {

    /** Lines per page when a section spans several, as Essentials shows them. */
    public static final int PAGE_SIZE = 9;

    private final List<String> index;
    private final Map<String, List<String>> sections;
    private final Map<String, String> names;

    private CustomTextDocument(List<String> index, Map<String, List<String>> sections, Map<String, String> names) {
        this.index = index;
        this.sections = sections;
        this.names = names;
    }

    public static CustomTextDocument parse(List<String> lines) {
        List<String> index = new ArrayList<>();
        Map<String, List<String>> sections = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        List<String> current = index;
        for (String raw : lines) {
            String line = stripLineEnd(raw);
            if (line.startsWith("##")) {
                continue;
            }
            if (line.startsWith("#")) {
                List<String> section = new ArrayList<>();
                String primary = null;
                for (String title : line.substring(1).split(",")) {
                    String key = title.trim().replace(' ', '_').toLowerCase(Locale.ROOT);
                    if (key.isEmpty()) {
                        continue;
                    }
                    if (primary == null) {
                        primary = key;
                    }
                    sections.putIfAbsent(key, section);
                    names.putIfAbsent(key, primary);
                }
                current = primary == null ? new ArrayList<>() : section;
                continue;
            }
            current.add(line);
        }
        return new CustomTextDocument(trimTrailingBlank(index), freeze(sections), Collections.unmodifiableMap(names));
    }

    /** The lines before the first section. */
    public List<String> index() {
        return index;
    }

    public Optional<List<String>> section(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(sections.get(name.toLowerCase(Locale.ROOT)));
    }

    /** The first name a section was declared with, for {@code voting} its {@code vote}. */
    public Optional<String> primaryName(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(names.get(name.toLowerCase(Locale.ROOT)));
    }

    /** Every section name and alias, in file order, for tab completion. */
    public List<String> sectionNames() {
        return List.copyOf(sections.keySet());
    }

    /** A page of lines; one page shows everything when it fits, as Essentials does. */
    public record Page(List<String> lines, int page, int pages) {
    }

    public static Page page(List<String> lines, int requested) {
        int pages = Math.max(1, (lines.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(1, Math.min(requested, pages));
        int from = (page - 1) * PAGE_SIZE;
        return new Page(lines.subList(Math.min(from, lines.size()), Math.min(from + PAGE_SIZE, lines.size())), page, pages);
    }

    private static Map<String, List<String>> freeze(Map<String, List<String>> sections) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Map<List<String>, List<String>> frozen = new java.util.IdentityHashMap<>();
        sections.forEach((key, value) -> out.put(key, frozen.computeIfAbsent(value, CustomTextDocument::trimTrailingBlank)));
        return Collections.unmodifiableMap(out);
    }

    private static List<String> trimTrailingBlank(List<String> lines) {
        int end = lines.size();
        while (end > 0 && lines.get(end - 1).isBlank()) {
            end--;
        }
        return List.copyOf(lines.subList(0, end));
    }

    private static String stripLineEnd(String raw) {
        if (raw == null) {
            return "";
        }
        int end = raw.length();
        while (end > 0 && (raw.charAt(end - 1) == '\r' || raw.charAt(end - 1) == '\n')) {
            end--;
        }
        return raw.substring(0, end);
    }
}
