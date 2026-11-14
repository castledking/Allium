package codes.castled.allium.moderation;

import com.google.gson.JsonParser;
import java.util.Set;
import java.util.UUID;

/** Read-only native-file audit; never imports or mutates either ban store. */
final class NativeBanAudit {
    private NativeBanAudit() {}
    static int unknownCount(String json, Set<UUID> known) {
        int count = 0;
        for (var entry : JsonParser.parseString(json).getAsJsonArray()) {
            var object = entry.getAsJsonObject();
            if (!object.has("uuid") || !known.contains(UUID.fromString(object.get("uuid").getAsString()))) count++;
        }
        return count;
    }
}
