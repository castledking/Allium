package codes.castled.clientgate;

import java.util.Collection;
import java.util.Map;

/** Tiny JSON writer (no external deps). */
final class Json {
    private Json() {}

    static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof Boolean || o instanceof Number) { sb.append(o); return; }
        if (o instanceof Map<?, ?> m) {
            sb.append('{'); boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(','); first = false;
                str(sb, String.valueOf(e.getKey())); sb.append(':'); write(sb, e.getValue());
            }
            sb.append('}'); return;
        }
        if (o instanceof Collection<?> c) {
            sb.append('['); boolean first = true;
            for (Object x : c) { if (!first) sb.append(','); first = false; write(sb, x); }
            sb.append(']'); return;
        }
        str(sb, String.valueOf(o));
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch)); else sb.append(ch); }
            }
        }
        sb.append('"');
    }
}
