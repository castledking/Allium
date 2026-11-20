package codes.castled.allium.tool.powertool;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Scope for powertool cooldown keys.
 */
public enum PowerToolCooldownScope {
    PLAYER("player"),
    ITEM("item"),
    GLOBAL("global");

    private final String id;

    PowerToolCooldownScope(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    @Nullable
    public static PowerToolCooldownScope parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        for (PowerToolCooldownScope scope : values()) {
            if (scope.id.equals(lower)) {
                return scope;
            }
        }
        return null;
    }
}
