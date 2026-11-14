package codes.castled.allium.travel;

import java.util.Locale;

/** Shared, locale-independent names for homes and warps. */
public final class TravelNames {
    public static final int MAX_LENGTH = 32;

    private TravelNames() {
    }

    /** Accepts ASCII only; spaces, Unicode lookalikes and implicit trimming are forbidden. */
    public static String normalize(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("name must be 1..32 ASCII letters, digits, underscores or hyphens");
        }
        return name.toLowerCase(Locale.ROOT);
    }
}
