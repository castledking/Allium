package codes.castled.allium.pack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * How a client of a given resource pack format reads {@code pack.mcmeta}, transcribed from the
 * game code of the validated lanes rather than from documentation:
 *
 * <ul>
 *   <li>format &lt;= 17 (1.20.1 client): only {@code pack.pack_format} is read; the pack is
 *       compatible when it equals the client's format. Overlays and newer fields are ignored.</li>
 *   <li>18..64 (1.20.2 to 1.21.8; 1.21.8 server {@code PackMetadataSection},
 *       {@code OverlayMetadataSection}, {@code Pack#getDeclaredPackVersions}): {@code pack_format}
 *       is required, {@code supported_formats} is lenient and falls back to {@code pack_format}
 *       when it does not contain it, overlays need {@code formats} and {@code directory}.</li>
 *   <li>65 and up (1.21.9+; 26.3 {@code PackFormat.IntermediaryFormat#validate}, resources'
 *       last pre-minor format 64): {@code min_format}/{@code max_format}, with the old fields
 *       required while a pack or any overlay still reaches format 64 or lower.</li>
 * </ul>
 */
public final class PackFormatRules {

    /** Last resource pack format without minor versions (26.3 {@code PackFormat#lastPreMinorVersion}). */
    public static final int LAST_PRE_MINOR = 64;
    private static final Pattern OVERLAY_DIRECTORY = Pattern.compile("[-_a-zA-Z0-9.]+");

    private PackFormatRules() {
    }

    public record Format(int major, int minor) implements Comparable<Format> {
        public static Format parse(String raw) {
            String[] parts = raw.trim().split("\\.");
            if (parts.length > 2) {
                throw new IllegalArgumentException("format must be <major> or <major>.<minor>: " + raw);
            }
            return new Format(Integer.parseInt(parts[0]), parts.length > 1 ? Integer.parseInt(parts[1]) : 0);
        }

        @Override
        public int compareTo(Format other) {
            int c = Integer.compare(major, other.major);
            return c != 0 ? c : Integer.compare(minor, other.minor);
        }

        @Override
        public String toString() {
            return minor == Integer.MAX_VALUE ? major + ".*" : major + "." + minor;
        }
    }

    /**
     * @param overlays applicable overlay directories in declared order; later entries win
     * @param declared the format range the client derives for the pack, for reports
     */
    public record Result(boolean compatible, String declared, List<String> overlays, List<String> errors, List<String> notes) {
        public Result {
            overlays = List.copyOf(overlays);
            errors = List.copyOf(errors);
            notes = List.copyOf(notes);
        }
    }

    public static Result evaluate(Map<String, Object> mcmeta, Format client) {
        List<String> errors = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Object packValue = mcmeta.get("pack");
        if (!(packValue instanceof Map<?, ?> rawPack)) {
            errors.add("pack.mcmeta has no pack section");
            return new Result(false, "none", List.of(), errors, notes);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> pack = (Map<String, Object>) rawPack;
        if (!pack.containsKey("description")) {
            errors.add("pack.description is required");
        }
        if (client.major() <= 17) {
            return singleFormat(pack, client, errors, notes, mcmeta.containsKey("overlays"));
        }
        if (client.major() <= LAST_PRE_MINOR) {
            return rangeFormat(pack, mcmeta.get("overlays"), client, errors, notes);
        }
        return minorFormat(pack, mcmeta.get("overlays"), client, errors, notes);
    }

    private static Result singleFormat(Map<String, Object> pack, Format client, List<String> errors, List<String> notes, boolean hasOverlays) {
        Optional<Integer> format = integer(pack.get("pack_format"));
        if (format.isEmpty()) {
            errors.add("pack.pack_format is required");
            return new Result(false, "none", List.of(), errors, notes);
        }
        if (hasOverlays) {
            notes.add("overlays are ignored by format " + client.major() + " clients");
        }
        boolean compatible = format.get() == client.major();
        if (!compatible) {
            errors.add("pack_format " + format.get() + " is not this client's format " + client.major());
        }
        return new Result(compatible && errors.isEmpty(), String.valueOf(format.get()), List.of(), errors, notes);
    }

    private static Result rangeFormat(Map<String, Object> pack, Object overlaysValue, Format client, List<String> errors, List<String> notes) {
        Optional<Integer> format = integer(pack.get("pack_format"));
        if (format.isEmpty()) {
            errors.add("pack.pack_format is required");
            return new Result(false, "none", List.of(), errors, notes);
        }
        int[] declared = {format.get(), format.get()};
        if (pack.containsKey("supported_formats")) {
            Optional<int[]> supported = intRange(pack.get("supported_formats"));
            if (supported.isEmpty()) {
                notes.add("supported_formats is unreadable and ignored (lenient field)");
            } else if (supported.get()[0] > format.get() || supported.get()[1] < format.get()) {
                errors.add("supported_formats does not contain pack_format; the client falls back to " + format.get());
            } else {
                declared = supported.get();
            }
        }
        List<String> overlays = new ArrayList<>();
        if (overlaysValue != null) {
            List<Object> entries = overlayEntries(overlaysValue, errors);
            for (Object raw : entries) {
                if (!(raw instanceof Map<?, ?> entry)) {
                    errors.add("overlay entry must be an object");
                    continue;
                }
                Optional<int[]> formats = intRange(entry.get("formats"));
                Object directory = entry.get("directory");
                if (formats.isEmpty()) {
                    errors.add("overlay entry needs a formats range: " + entry);
                } else if (!(directory instanceof String dir) || !OVERLAY_DIRECTORY.matcher(dir).matches()) {
                    errors.add("overlay directory is missing or not accepted: " + directory);
                } else if (formats.get()[0] <= client.major() && client.major() <= formats.get()[1]) {
                    overlays.add(dir);
                }
            }
        }
        boolean compatible = declared[0] <= client.major() && client.major() <= declared[1];
        if (!compatible) {
            errors.add("declared formats " + declared[0] + ".." + declared[1] + " do not include " + client.major());
        }
        return new Result(compatible && errors.isEmpty(), declared[0] + ".." + declared[1],
                errors.isEmpty() ? overlays : List.of(), errors, notes);
    }

    private static Result minorFormat(Map<String, Object> pack, Object overlaysValue, Format client, List<String> errors, List<String> notes) {
        Intermediary root = new Intermediary(
                format(pack.get("min_format"), 0, "pack.min_format", errors),
                format(pack.get("max_format"), Integer.MAX_VALUE, "pack.max_format", errors),
                integer(pack.get("pack_format")),
                pack.containsKey("supported_formats") ? intRange(pack.get("supported_formats")) : Optional.empty());
        if (pack.containsKey("supported_formats") && root.supported.isEmpty()) {
            errors.add("pack.supported_formats is unreadable");
        }
        Format[] range = root.validate(true, false, "Pack", "supported_formats", errors);
        List<String> overlays = new ArrayList<>();
        if (overlaysValue != null) {
            List<Object> entries = overlayEntries(overlaysValue, errors);
            List<Intermediary> parsed = new ArrayList<>();
            List<String> directories = new ArrayList<>();
            for (Object raw : entries) {
                if (!(raw instanceof Map<?, ?> entry)) {
                    errors.add("overlay entry must be an object");
                    continue;
                }
                Object directory = entry.get("directory");
                if (!(directory instanceof String dir) || !OVERLAY_DIRECTORY.matcher(dir).matches()) {
                    errors.add("overlay directory is missing or not accepted: " + directory);
                    continue;
                }
                Optional<Format> min = format(entry.get("min_format"), 0, "overlay " + dir + " min_format", errors);
                Optional<int[]> formats = entry.containsKey("formats") ? intRange(entry.get("formats")) : Optional.empty();
                if (entry.containsKey("formats") && formats.isEmpty()) {
                    errors.add("overlay " + dir + " formats is unreadable");
                }
                parsed.add(new Intermediary(min, format(entry.get("max_format"), Integer.MAX_VALUE, "overlay " + dir + " max_format", errors),
                        min.map(Format::major), formats));
                directories.add(dir);
            }
            int minVersion = parsed.stream().mapToInt(Intermediary::effectiveMinMajor).min().orElse(Integer.MAX_VALUE);
            for (int i = 0; i < parsed.size(); i++) {
                Intermediary entry = parsed.get(i);
                if (entry.min.isEmpty() && entry.max.isEmpty() && entry.supported.isEmpty()) {
                    notes.add("overlay " + directories.get(i) + " has no format information and is skipped");
                    continue;
                }
                Format[] entryRange = entry.validate(false, minVersion <= LAST_PRE_MINOR,
                        "Overlay \"" + directories.get(i) + "\"", "formats", errors);
                if (entryRange != null && within(client, entryRange)) {
                    overlays.add(directories.get(i));
                }
            }
        }
        boolean compatible = range != null && within(client, range);
        if (range != null && !compatible) {
            errors.add("declared formats " + range[0] + ".." + range[1] + " do not include " + client);
        }
        return new Result(compatible && errors.isEmpty(), range == null ? "invalid" : range[0] + ".." + range[1],
                errors.isEmpty() ? overlays : List.of(), errors, notes);
    }

    private static boolean within(Format client, Format[] range) {
        return client.compareTo(range[0]) >= 0 && client.compareTo(range[1]) <= 0;
    }

    /** Mirror of 26.3 {@code PackFormat.IntermediaryFormat}. */
    private record Intermediary(Optional<Format> min, Optional<Format> max, Optional<Integer> format, Optional<int[]> supported) {

        int effectiveMinMajor() {
            if (min.isPresent()) {
                return supported.isPresent() ? Math.min(min.get().major(), supported.get()[0]) : min.get().major();
            }
            return supported.isPresent() ? supported.get()[0] : Integer.MAX_VALUE;
        }

        Format[] validate(boolean hasPackFormatField, boolean requireOldField, String context, String oldField, List<String> errors) {
            if (min.isPresent() != max.isPresent()) {
                errors.add(context + " must declare both min_format and max_format");
                return null;
            }
            if (requireOldField && supported.isEmpty()) {
                errors.add(context + " is missing " + oldField + ", which every overlay needs while any overlay reaches format "
                        + LAST_PRE_MINOR + " or lower");
                return null;
            }
            if (min.isPresent()) {
                return validateNew(hasPackFormatField, requireOldField, context, oldField, errors);
            }
            if (supported.isPresent()) {
                int lo = supported.get()[0];
                int hi = supported.get()[1];
                if (hi > LAST_PRE_MINOR) {
                    errors.add(context + " reaches past format " + LAST_PRE_MINOR + " without min_format and max_format");
                    return null;
                }
                if (hasPackFormatField && !packFormatFits(lo, hi, context, errors)) {
                    return null;
                }
                return new Format[]{new Format(lo, 0), new Format(hi, 0)};
            }
            if (hasPackFormatField && format.isPresent()) {
                if (format.get() > LAST_PRE_MINOR) {
                    errors.add(context + " declares format " + format.get() + " without min_format and max_format");
                    return null;
                }
                return new Format[]{new Format(format.get(), 0), new Format(format.get(), 0)};
            }
            errors.add(context + " has no format information");
            return null;
        }

        private Format[] validateNew(boolean hasPackFormatField, boolean requireOldField, String context, String oldField, List<String> errors) {
            int majorMin = min.get().major();
            int majorMax = max.get().major();
            if (min.get().compareTo(max.get()) > 0) {
                errors.add(context + " min_format " + min.get() + " is greater than max_format " + max.get());
                return null;
            }
            if (majorMin > LAST_PRE_MINOR && !requireOldField) {
                if (supported.isPresent()) {
                    errors.add(context + " must not declare " + oldField + " when every supported format is above " + LAST_PRE_MINOR);
                    return null;
                }
                if (hasPackFormatField && format.isPresent() && !packFormatFits(majorMin, majorMax, context, errors)) {
                    return null;
                }
            } else {
                if (supported.isEmpty()) {
                    errors.add(context + " reaches format " + majorMin + " and needs " + oldField + " [" + majorMin + ", "
                            + LAST_PRE_MINOR + "] for older clients");
                    return null;
                }
                if (supported.get()[0] != majorMin) {
                    errors.add(context + " " + oldField + " starts at " + supported.get()[0] + " but min_format is " + min.get());
                    return null;
                }
                if (supported.get()[1] != majorMax && supported.get()[1] != LAST_PRE_MINOR) {
                    errors.add(context + " " + oldField + " ends at " + supported.get()[1] + " but max_format is " + max.get());
                    return null;
                }
                if (hasPackFormatField) {
                    if (format.isEmpty()) {
                        errors.add(context + " needs pack_format for clients up to format " + LAST_PRE_MINOR);
                        return null;
                    }
                    if (!packFormatFits(majorMin, majorMax, context, errors)) {
                        return null;
                    }
                }
            }
            return new Format[]{min.get(), max.get()};
        }

        private boolean packFormatFits(int lo, int hi, String context, List<String> errors) {
            int main = format.orElseThrow();
            if (main < lo || main > hi) {
                errors.add(context + " supports " + lo + ".." + hi + " but pack_format is " + main);
                return false;
            }
            if (main < 15) {
                errors.add(context + " multi-version packs cannot declare pack_format below 15");
                return false;
            }
            return true;
        }
    }

    private static List<Object> overlayEntries(Object overlaysValue, List<String> errors) {
        if (!(overlaysValue instanceof Map<?, ?> section) || !(section.get("entries") instanceof List<?> entries)) {
            errors.add("overlays must be an object with an entries list");
            return List.of();
        }
        return new ArrayList<>(entries);
    }

    private static Optional<Integer> integer(Object value) {
        return value instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ? Optional.of(l.intValue()) : Optional.empty();
    }

    /** {@code InclusiveRange<Integer>}: an int, a two-element list, or {min_inclusive, max_inclusive}. */
    static Optional<int[]> intRange(Object value) {
        if (value instanceof Long) {
            int v = integer(value).orElseThrow();
            return Optional.of(new int[]{v, v});
        }
        if (value instanceof List<?> list && list.size() == 2) {
            Optional<Integer> lo = integer(list.get(0));
            Optional<Integer> hi = integer(list.get(1));
            if (lo.isPresent() && hi.isPresent() && lo.get() <= hi.get()) {
                return Optional.of(new int[]{lo.get(), hi.get()});
            }
        }
        if (value instanceof Map<?, ?> map) {
            Optional<Integer> lo = integer(map.get("min_inclusive"));
            Optional<Integer> hi = integer(map.get("max_inclusive"));
            if (lo.isPresent() && hi.isPresent() && lo.get() <= hi.get()) {
                return Optional.of(new int[]{lo.get(), hi.get()});
            }
        }
        return Optional.empty();
    }

    /** 26.3 {@code PackFormat} codec: an int or a list of 1+ non-negative ints; a missing minor takes {@code defaultMinor}. */
    private static Optional<Format> format(Object value, int defaultMinor, String field, List<String> errors) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Long) {
            int v = integer(value).orElse(-1);
            if (v >= 0) {
                return Optional.of(new Format(v, defaultMinor));
            }
        }
        if (value instanceof List<?> list && !list.isEmpty() && list.size() <= 256) {
            Optional<Integer> major = integer(list.get(0));
            Optional<Integer> minor = list.size() > 1 ? integer(list.get(1)) : Optional.of(defaultMinor);
            if (major.isPresent() && minor.isPresent() && major.get() >= 0 && minor.get() >= 0) {
                return Optional.of(new Format(major.get(), minor.get()));
            }
        }
        errors.add(field + " must be a non-negative integer or [major, minor]: " + String.valueOf(value).toLowerCase(Locale.ROOT));
        return Optional.empty();
    }
}
