package codes.castled.allium.essentials;

import java.util.Locale;

/**
 * Pure filtering and cap helpers for {@code /clearinventory [player] [material] [maxCount]}.
 */
public final class InventoryClearFilter {

    public static final int UNLIMITED = Integer.MAX_VALUE;

    private InventoryClearFilter() {
    }

    public static Integer parseMaxCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= 1 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String normalizeMaterialToken(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("minecraft:")) {
            trimmed = trimmed.substring("minecraft:".length());
        }
        return trimmed.replace('-', '_');
    }

    public static boolean matchesMaterialKey(String candidateKey, String filterToken) {
        if (filterToken == null || filterToken.isBlank()) {
            return true;
        }
        if (candidateKey == null || candidateKey.isBlank()) {
            return false;
        }
        return normalizeMaterialToken(candidateKey).equals(normalizeMaterialToken(filterToken));
    }

    public static boolean isAllToken(String raw) {
        return raw != null && ("*".equals(raw.trim()) || "all".equalsIgnoreCase(raw.trim()));
    }
}
