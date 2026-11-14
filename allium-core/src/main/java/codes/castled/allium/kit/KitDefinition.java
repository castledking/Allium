package codes.castled.allium.kit;

import codes.castled.allium.travel.TravelNames;

import java.util.List;
import java.util.Objects;

/**
 * Immutable definition of a kit from {@code plugins/Allium/kits/*.yml}.
 *
 * <p>Kit names follow {@link TravelNames#normalize(String)} (1..32 ASCII letters, digits,
 * underscores or hyphens, normalized to lowercase). Paid kits ({@code cost > 0}) are retained
 * as metadata so the definition can be rejected or denied until M3 economy reservations exist.
 */
public record KitDefinition(
        String name,
        boolean enabled,
        long cooldownSeconds,
        boolean oneTime,
        double cost,
        List<String> items,
        String file
) {
    public static final int MAX_ITEMS = 36;
    public static final long MAX_COOLDOWN_SECONDS = 365L * 86_400L;

    public KitDefinition {
        name = TravelNames.normalize(name);
        if (cooldownSeconds < 0 || cooldownSeconds > MAX_COOLDOWN_SECONDS) {
            throw new IllegalArgumentException("cooldownSeconds must be between 0 and " + MAX_COOLDOWN_SECONDS);
        }
        if (!Double.isFinite(cost) || cost < 0.0D) {
            throw new IllegalArgumentException("cost must be a finite non-negative number");
        }
        Objects.requireNonNull(items, "items");
        if (items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("kit must define 1.." + MAX_ITEMS + " item entries");
        }
        for (String spec : items) {
            if (spec == null || spec.isBlank()) {
                throw new IllegalArgumentException("kit item entry must not be blank");
            }
        }
        items = List.copyOf(items);
        file = Objects.requireNonNull(file, "file");
    }

    public long cooldownMillis() {
        return cooldownSeconds * 1000L;
    }

    public boolean paid() {
        return cost > 0.0D;
    }

    public String permissionNode() {
        return "allium.kits." + name;
    }
}
