package codes.castled.allium.portable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure Java 8 zero-dependency YAML subset reader for {@code plugins/Allium/config.yml} and
 * {@code plugins/Allium/lang/*.yml} on native Fabric (1.14–26.3) and NeoForge (1.20.2–26.3).
 *
 * <p>Flattens nested mappings into dotted paths (e.g. {@code discord.channels.moderation.webhook}),
 * supports single/double quoted scalars, comments, inline {@code [a, b]} lists, and block
 * {@code - item} lists.
 */
public final class NativeYaml {
    private final Map<String, String> scalars;
    private final Map<String, List<String>> lists;

    private NativeYaml(Map<String, String> scalars, Map<String, List<String>> lists) {
        this.scalars = scalars;
        this.lists = lists;
    }

    public static NativeYaml empty() {
        return new NativeYaml(Collections.<String, String>emptyMap(), Collections.<String, List<String>>emptyMap());
    }

    public static NativeYaml load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) return empty();
        return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    public static NativeYaml parse(List<String> lines) {
        Map<String, String> scalars = new LinkedHashMap<String, String>();
        Map<String, List<String>> lists = new LinkedHashMap<String, List<String>>();
        List<Integer> indents = new ArrayList<Integer>();
        List<String> path = new ArrayList<String>();
        String activeListKey = null;
        int activeListIndent = -1;

        for (String rawLine : lines) {
            if (rawLine == null) continue;
            int indent = countLeadingSpaces(rawLine);
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            // Block list item: "- value"
            if (trimmed.startsWith("- ") || "-".equals(trimmed)) {
                String itemRaw = trimmed.length() > 1 ? trimmed.substring(1).trim() : "";
                while (!indents.isEmpty() && indent < indents.get(indents.size() - 1)) {
                    indents.remove(indents.size() - 1);
                    path.remove(path.size() - 1);
                }
                String targetKey = (activeListKey != null && indent >= activeListIndent)
                        ? activeListKey
                        : joinPath(path);
                if (!targetKey.isEmpty()) {
                    List<String> list = lists.get(targetKey);
                    if (list == null) {
                        list = new ArrayList<String>();
                        lists.put(targetKey, list);
                    }
                    String val = unquote(stripInlineComment(itemRaw));
                    if (!val.isEmpty()) list.add(val);
                }
                continue;
            }

            activeListKey = null;
            activeListIndent = -1;

            int colon = findKeyColon(trimmed);
            if (colon <= 0) continue;

            String key = unquote(trimmed.substring(0, colon).trim());
            if (key.isEmpty()) continue;
            String rest = trimmed.substring(colon + 1).trim();

            while (!indents.isEmpty() && indent <= indents.get(indents.size() - 1)) {
                indents.remove(indents.size() - 1);
                path.remove(path.size() - 1);
            }
            indents.add(indent);
            path.add(key);
            String fullKey = joinPath(path);

            String valuePart = stripInlineComment(rest);
            if (valuePart.isEmpty() || "{}".equals(valuePart)) {
                activeListKey = fullKey;
                activeListIndent = indent;
            } else if (valuePart.startsWith("[") && valuePart.endsWith("]")) {
                List<String> items = parseInlineList(valuePart.substring(1, valuePart.length() - 1));
                lists.put(fullKey, items);
            } else {
                scalars.put(fullKey, unquote(valuePart));
            }
        }
        return new NativeYaml(scalars, lists);
    }

    public String getString(String key, String fallback) {
        String v = scalars.get(key);
        return v == null ? fallback : v;
    }

    public boolean getBoolean(String key, boolean fallback) {
        String v = scalars.get(key);
        if (v == null) return fallback;
        String lower = v.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(lower) || "yes".equals(lower) || "on".equals(lower)) return true;
        if ("false".equals(lower) || "no".equals(lower) || "off".equals(lower)) return false;
        return fallback;
    }

    public int getInt(String key, int fallback) {
        String v = scalars.get(key);
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public long getLong(String key, long fallback) {
        String v = scalars.get(key);
        if (v == null) return fallback;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public List<String> getStringList(String key) {
        List<String> list = lists.get(key);
        return list == null ? Collections.<String>emptyList() : Collections.unmodifiableList(list);
    }

    public boolean contains(String key) {
        return scalars.containsKey(key) || lists.containsKey(key);
    }

    public int size() {
        return scalars.size() + lists.size();
    }

    private static int countLeadingSpaces(String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') count++;
            else if (c == '\t') count += 2;
            else break;
        }
        return count;
    }

    private static int findKeyColon(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle && (i == 0 || line.charAt(i - 1) != '\\')) {
                inDouble = !inDouble;
            } else if (c == ':' && !inSingle && !inDouble) {
                if (i + 1 == line.length() || Character.isWhitespace(line.charAt(i + 1))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String stripInlineComment(String raw) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle && (i == 0 || raw.charAt(i - 1) != '\\')) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble && (i == 0 || Character.isWhitespace(raw.charAt(i - 1)))) {
                return raw.substring(0, i).trim();
            }
        }
        return raw.trim();
    }

    private static List<String> parseInlineList(String inside) {
        String trimmed = inside.trim();
        if (trimmed.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                current.append(c);
            } else if (c == '"' && !inSingle && (i == 0 || trimmed.charAt(i - 1) != '\\')) {
                inDouble = !inDouble;
                current.append(c);
            } else if (c == ',' && !inSingle && !inDouble) {
                String val = unquote(current.toString().trim());
                if (!val.isEmpty()) out.add(val);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String last = unquote(current.toString().trim());
        if (!last.isEmpty()) out.add(last);
        return out;
    }

    private static String unquote(String value) {
        if (value == null) return "";
        String t = value.trim();
        if (t.length() >= 2 && t.startsWith("'") && t.endsWith("'")) {
            return t.substring(1, t.length() - 1).replace("''", "'");
        }
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            String inner = t.substring(1, t.length() - 1);
            StringBuilder sb = new StringBuilder(inner.length());
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '\\' && i + 1 < inner.length()) {
                    char next = inner.charAt(++i);
                    switch (next) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        default: sb.append(next); break;
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        return t;
    }

    private static String joinPath(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('.');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
