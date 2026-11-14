package codes.castled.allium.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The kit delivery receipts a player carries in their own persistent data
 * ({@code allium:kit_receipts}), saved in the same {@code playerdata/<uuid>.dat} file as their
 * inventory. After a crash, the inventory on disk either has both the delivered kit items and
 * the {@code K/<deliveryId>/<token>/<kitName>} receipt, or neither:
 * <ul>
 *   <li>With the receipt, join recovery finishes the claim (recording cooldown and one-time usage)
 *       without delivering the items a second time.</li>
 *   <li>Without the receipt, the items never reached disk and join recovery releases the
 *       {@code CLAIMING} row without burning the player's cooldown or one-time kit status.</li>
 * </ul>
 */
public final class KitReceipts {

    public sealed interface Entry permits Claimed, Unknown {
        String encode();
    }

    public record Claimed(UUID deliveryId, UUID token, String kitName) implements Entry {
        public Claimed {
            Objects.requireNonNull(deliveryId, "deliveryId");
            Objects.requireNonNull(token, "token");
            kitName = KitNames.validate(kitName);
        }

        @Override
        public String encode() {
            return "K/" + deliveryId + "/" + token + "/" + kitName;
        }
    }

    public record Unknown(String raw) implements Entry {
        public Unknown {
            Objects.requireNonNull(raw, "raw");
        }

        @Override
        public String encode() {
            return raw;
        }
    }

    private KitReceipts() {
    }

    public static List<Entry> parse(String raw) {
        List<Entry> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(";")) {
            if (!part.isBlank()) {
                out.add(parseOne(part.trim()));
            }
        }
        return out;
    }

    private static Entry parseOne(String part) {
        String[] fields = part.split("/", -1);
        try {
            if (fields.length == 4 && "K".equals(fields[0])) {
                return new Claimed(
                        UUID.fromString(fields[1]),
                        UUID.fromString(fields[2]),
                        KitNames.validate(fields[3])
                );
            }
        } catch (IllegalArgumentException ignored) {
            // Kept untouched as Unknown for operator inspection
        }
        return new Unknown(part);
    }

    public static String format(List<Entry> entries) {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            if (!out.isEmpty()) {
                out.append(';');
            }
            out.append(entry.encode());
        }
        return out.toString();
    }
}
