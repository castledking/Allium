package codes.castled.allium.modguard;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ChannelWire {

    private ChannelWire() {
    }

    static String canonical(String channel) {
        if (channel == null) {
            return "";
        }
        return switch (channel) {
            case "MC|Brand" -> "minecraft:brand";
            case "REGISTER" -> "minecraft:register";
            case "FML|HS", "l:fmlhs" -> "fml:handshake";
            default -> channel;
        };
    }

    static boolean readable(String channel) {
        return Set.of(
                "minecraft:register",
                "minecraft:brand",
                "fml:handshake",
                "lunar:apollo",
                "apollo:json"
        ).contains(canonical(channel));
    }

    static List<String> registrations(byte[] data) {
        if (data == null || data.length == 0) {
            return List.of();
        }
        if (data.length > 131072) {
            throw new IllegalArgumentException("registration too large");
        }
        String text = Wire.utf8(data, 131072);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        int start = 0;
        int count = 0;
        for (int i = 0; i <= text.length(); ++i) {
            if (i != text.length() && text.charAt(i) != '\0') {
                continue;
            }
            if (i == text.length() && start == i) {
                break;
            }
            String value = text.substring(start, i);
            start = i + 1;
            if (value.isEmpty() || value.length() > 256 || ++count > 512 || !value.matches("[A-Za-z0-9_.:|/-]+")) {
                continue;
            }
            result.add(value);
        }
        return List.copyOf(result);
    }
}
