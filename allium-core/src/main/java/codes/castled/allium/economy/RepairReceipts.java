package codes.castled.allium.economy;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The paid-repair receipt kept in the player's own data: the workflow IDs whose repair is saved
 * with the player's inventory, comma-separated. A HELD reservation is captured exactly when the
 * saved file lists its ID, and released when the complete saved file proves it absent.
 */
public final class RepairReceipts {

    private RepairReceipts() {
    }

    /** The IDs in {@code raw}; malformed entries make the whole receipt unreadable. */
    public static Set<UUID> parse(@Nullable String raw) {
        Set<UUID> out = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String part : raw.split(",", -1)) {
            out.add(UUID.fromString(part));
        }
        return out;
    }

    /** The receipt text for {@code ids}, or null when there are none (the key is then removed). */
    @Nullable
    public static String format(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (UUID id : ids) {
            if (!out.isEmpty()) out.append(',');
            out.append(id);
        }
        return out.toString();
    }
}
