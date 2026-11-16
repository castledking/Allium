package codes.castled.allium.moderation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Platform-neutral GUI layout and audit delta helpers for {@code /invsee} and {@code /enderchest}.
 *
 * <p>{@code /invsee} uses a 45-slot chest GUI:
 * <ul>
 *   <li>GUI slots {@code 0..26} map to main storage slots {@code 9..35}.</li>
 *   <li>GUI slots {@code 27..35} map to hotbar slots {@code 0..8}.</li>
 *   <li>GUI slots {@code 36..39} map to armor slots: Helmet ({@code 39}), Chestplate ({@code 38}),
 *       Leggings ({@code 37}), Boots ({@code 36}).</li>
 *   <li>GUI slot {@code 40} maps to offhand ({@code 40}).</li>
 *   <li>GUI slots {@code 41..44} are locked UI filler panes and are never writable.</li>
 * </ul>
 *
 * <p>{@code /enderchest} uses a 27-slot chest GUI mapping {@code 0..26} directly to ender chest
 * slots {@code 0..26}.
 */
public final class InspectionLayout {

    public static final int INVSEE_GUI_SIZE = 45;
    public static final int INVSEE_PLAYER_SLOT_COUNT = 41;
    public static final int INVSEE_LOCKED_START = 41;
    public static final int INVSEE_LOCKED_END = 44;
    public static final int ENDERCHEST_GUI_SIZE = 27;
    public static final int MAX_GUI_TITLE_LENGTH = 32;
    public static final int MAX_AUDIT_DETAIL_LENGTH = 512;

    private InspectionLayout() {
    }

    public enum InspectionType {
        INVSEE,
        ENDERCHEST
    }

    public enum InspectionMode {
        SELF,
        ONLINE,
        OFFLINE
    }

    public record ItemSummary(String material, int amount, boolean hasMeta) {
        public ItemSummary {
            material = (material == null || material.isBlank()) ? "AIR" : material.trim().toUpperCase(Locale.ROOT);
            if (amount <= 0 || "AIR".equals(material)) {
                material = "AIR";
                amount = 0;
                hasMeta = false;
            }
        }

        public static final ItemSummary EMPTY = new ItemSummary("AIR", 0, false);

        public boolean isEmpty() {
            return amount <= 0 || "AIR".equals(material);
        }

        public String compact() {
            if (isEmpty()) {
                return "EMPTY";
            }
            return amount + "x" + material + (hasMeta ? "*" : "");
        }
    }

    public record SlotDelta(int playerSlot, ItemSummary before, ItemSummary after) {
        public SlotDelta {
            before = before == null ? ItemSummary.EMPTY : before;
            after = after == null ? ItemSummary.EMPTY : after;
        }
    }

    public static int guiSize(InspectionType type) {
        Objects.requireNonNull(type, "type");
        return type == InspectionType.INVSEE ? INVSEE_GUI_SIZE : ENDERCHEST_GUI_SIZE;
    }

    public static int slotCount(InspectionType type) {
        Objects.requireNonNull(type, "type");
        return type == InspectionType.INVSEE ? INVSEE_PLAYER_SLOT_COUNT : ENDERCHEST_GUI_SIZE;
    }

    public static boolean isLockedUiSlot(InspectionType type, int guiSlot) {
        Objects.requireNonNull(type, "type");
        return type == InspectionType.INVSEE && guiSlot >= INVSEE_LOCKED_START && guiSlot <= INVSEE_LOCKED_END;
    }

    /**
     * Maps a top-GUI slot index to the underlying target slot ({@code 0..40} for invsee,
     * {@code 0..26} for enderchest), or {@code -1} if locked/out of range.
     */
    public static int toPlayerSlot(InspectionType type, int guiSlot) {
        Objects.requireNonNull(type, "type");
        if (guiSlot < 0 || guiSlot >= guiSize(type) || isLockedUiSlot(type, guiSlot)) {
            return -1;
        }
        if (type == InspectionType.ENDERCHEST) {
            return guiSlot;
        }
        if (guiSlot < 27) {
            return guiSlot + 9;
        }
        if (guiSlot < 36) {
            return guiSlot - 27;
        }
        return switch (guiSlot) {
            case 36 -> 39; // Helmet
            case 37 -> 38; // Chestplate
            case 38 -> 37; // Leggings
            case 39 -> 36; // Boots
            case 40 -> 40; // Offhand
            default -> -1;
        };
    }

    /**
     * Inverse mapping from target slot ({@code 0..40} for invsee, {@code 0..26} for enderchest)
     * to top-GUI slot index, or {@code -1} if out of range.
     */
    public static int toGuiSlot(InspectionType type, int playerSlot) {
        Objects.requireNonNull(type, "type");
        if (playerSlot < 0 || playerSlot >= slotCount(type)) {
            return -1;
        }
        if (type == InspectionType.ENDERCHEST) {
            return playerSlot;
        }
        if (playerSlot <= 8) {
            return playerSlot + 27;
        }
        if (playerSlot <= 35) {
            return playerSlot - 9;
        }
        return switch (playerSlot) {
            case 39 -> 36; // Helmet
            case 38 -> 37; // Chestplate
            case 37 -> 38; // Leggings
            case 36 -> 39; // Boots
            case 40 -> 40; // Offhand
            default -> -1;
        };
    }

    public static String slotLabel(InspectionType type, int playerSlot) {
        Objects.requireNonNull(type, "type");
        if (type == InspectionType.ENDERCHEST) {
            return (playerSlot >= 0 && playerSlot < ENDERCHEST_GUI_SIZE) ? "ec[" + playerSlot + "]" : "unknown[" + playerSlot + "]";
        }
        if (playerSlot >= 0 && playerSlot <= 8) {
            return "hotbar[" + playerSlot + "]";
        }
        if (playerSlot >= 9 && playerSlot <= 35) {
            return "inv[" + playerSlot + "]";
        }
        return switch (playerSlot) {
            case 39 -> "armor[helmet]";
            case 38 -> "armor[chestplate]";
            case 37 -> "armor[leggings]";
            case 36 -> "armor[boots]";
            case 40 -> "offhand";
            default -> "unknown[" + playerSlot + "]";
        };
    }

    public static String sanitizeToken(String raw, int maxLen) {
        if (raw == null || raw.isEmpty() || maxLen <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), maxLen));
        boolean lastWasSpace = false;
        for (int i = 0; i < raw.length() && sb.length() < maxLen; i++) {
            char c = raw.charAt(i);
            if (c >= 0x20 && c != 0x7F && c != '\n' && c != '\r' && c != '\t') {
                boolean space = (c == ' ');
                if (!space || !lastWasSpace) {
                    sb.append(c);
                }
                lastWasSpace = space;
            } else if (Character.isWhitespace(c)) {
                if (!lastWasSpace && sb.length() > 0) {
                    sb.append(' ');
                    lastWasSpace = true;
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * Builds a sanitized, bounded audit detail string fitting cleanly within {@code VARCHAR(512)}.
     */
    public static String formatAuditDetail(
            InspectionType type,
            InspectionMode mode,
            boolean editable,
            String reasonOrContext,
            List<SlotDelta> deltas
    ) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mode, "mode");
        StringBuilder sb = new StringBuilder();
        sb.append("type=").append(type.name())
                .append(" mode=").append(mode.name())
                .append(" editable=").append(editable);
        String cleanContext = sanitizeToken(reasonOrContext, 128);
        if (!cleanContext.isEmpty()) {
            sb.append(" info=").append(cleanContext);
        }
        if (deltas != null && !deltas.isEmpty()) {
            List<String> parts = new ArrayList<>();
            int included = 0;
            String header = sb + " changes=";
            int budget = MAX_AUDIT_DETAIL_LENGTH - header.length();
            for (SlotDelta delta : deltas) {
                String entry = slotLabel(type, delta.playerSlot()) + ":"
                        + delta.before().compact() + "->" + delta.after().compact();
                int needed = (included == 0 ? 0 : 1) + entry.length();
                if (budget - needed < 20 && included < deltas.size()) {
                    break;
                }
                if (needed <= budget) {
                    parts.add(entry);
                    budget -= needed;
                    included++;
                } else {
                    break;
                }
            }
            sb.append(" changes=").append(String.join(",", parts));
            int remaining = deltas.size() - included;
            if (remaining > 0) {
                String suffix = ",+" + remaining + "more";
                if (sb.length() + suffix.length() <= MAX_AUDIT_DETAIL_LENGTH) {
                    sb.append(suffix);
                }
            }
        }
        return sb.length() <= MAX_AUDIT_DETAIL_LENGTH ? sb.toString() : sb.substring(0, MAX_AUDIT_DETAIL_LENGTH);
    }

    /**
     * Formats an inspection chest GUI title fitting within 32 characters while always preserving
     * offline and read-only/edit indicators even for 16-character player names.
     */
    public static String formatGuiTitle(
            InspectionType type,
            InspectionMode mode,
            boolean editable,
            String targetName,
            String prefix
    ) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mode, "mode");
        String safePrefix = prefix == null ? "" : prefix;
        String safeTarget = sanitizeToken(targetName, 16);
        if (safeTarget.isEmpty()) {
            safeTarget = "Player";
        }
        String fullType = type == InspectionType.INVSEE ? "Inventory" : "Ender Chest";
        String shortType = type == InspectionType.INVSEE ? "Inv" : "EC";
        String fullOffline = mode == InspectionMode.OFFLINE ? " [Offline]" : "";
        String shortOffline = mode == InspectionMode.OFFLINE ? " [Off]" : "";
        String fullMode = editable ? " (Edit)" : " (Read-Only)";
        String shortMode = editable ? " (Edit)" : " (RO)";

        String full = safePrefix + fullType + ": " + safeTarget + fullOffline + fullMode;
        if (full.length() <= MAX_GUI_TITLE_LENGTH) {
            return full;
        }
        String med = safePrefix + shortType + ": " + safeTarget + fullOffline + fullMode;
        if (med.length() <= MAX_GUI_TITLE_LENGTH) {
            return med;
        }
        String compactPrefix = safePrefix + shortType + ": ";
        String compactSuffix = shortOffline + shortMode;
        String compact = compactPrefix + safeTarget + compactSuffix;
        if (compact.length() <= MAX_GUI_TITLE_LENGTH) {
            return compact;
        }
        int nameBudget = Math.max(1, MAX_GUI_TITLE_LENGTH - compactPrefix.length() - compactSuffix.length());
        String trimmedTarget = safeTarget.length() > nameBudget ? safeTarget.substring(0, nameBudget) : safeTarget;
        String result = compactPrefix + trimmedTarget + compactSuffix;
        return result.length() <= MAX_GUI_TITLE_LENGTH ? result : result.substring(0, MAX_GUI_TITLE_LENGTH);
    }
}
