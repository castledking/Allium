package codes.castled.allium.discord;

import codes.castled.allium.pack.Json;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One post to a staff feed: a Discord embed. Text that players or staff typed (a reason, a report)
 * can never ping anyone: only the feed's configured roles are allowed to be mentioned.
 */
public record StaffPost(String title, int color, List<Field> fields, @Nullable String description, Instant timestamp) {

    public static final int TITLE_LIMIT = 256;
    public static final int FIELD_NAME_LIMIT = 256;
    public static final int FIELD_VALUE_LIMIT = 1024;
    public static final int DESCRIPTION_LIMIT = 4096;
    private static final Pattern ROLE_ID = Pattern.compile("[0-9]{15,22}");

    /** One embed field; blank values are left out. */
    public record Field(String name, String value, boolean inline) {
    }

    public StaffPost {
        title = cut(title, TITLE_LIMIT);
        List<Field> kept = new ArrayList<>();
        for (Field field : fields) {
            if (field.value() == null || field.value().isBlank()) continue;
            kept.add(new Field(cut(field.name(), FIELD_NAME_LIMIT), cut(field.value(), FIELD_VALUE_LIMIT), field.inline()));
        }
        fields = List.copyOf(kept);
        description = description == null || description.isBlank() ? null : cut(description, DESCRIPTION_LIMIT);
    }

    /** {@code <@&id>} for each valid role id, space separated; empty when there are none. */
    public static String mentions(List<String> roleIds) {
        StringBuilder out = new StringBuilder();
        for (String id : roleIds) {
            if (!ROLE_ID.matcher(id).matches()) continue;
            if (out.length() > 0) out.append(' ');
            out.append("<@&").append(id).append('>');
        }
        return out.toString();
    }

    public static boolean isRoleId(String id) {
        return id != null && ROLE_ID.matcher(id).matches();
    }

    /** The body of a Discord webhook execute request for this post. */
    public String webhookPayload(String username, @Nullable String avatarUrl, @Nullable String footer, List<String> roleIds) {
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", title);
        embed.put("color", color & 0xFFFFFF);
        if (description != null) embed.put("description", description);
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (Field field : fields) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", field.name());
            entry.put("value", field.value());
            entry.put("inline", field.inline());
            rendered.add(entry);
        }
        if (!rendered.isEmpty()) embed.put("fields", rendered);
        if (footer != null && !footer.isBlank()) embed.put("footer", Map.of("text", cut(footer, 2048)));
        embed.put("timestamp", timestamp.toString());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", cut(username == null || username.isBlank() ? "Allium" : username, 80));
        if (avatarUrl != null && !avatarUrl.isBlank()) body.put("avatar_url", avatarUrl);
        List<String> roles = roleIds.stream().filter(StaffPost::isRoleId).toList();
        String content = mentions(roles);
        if (!content.isEmpty()) body.put("content", content);
        body.put("embeds", List.of(embed));
        Map<String, Object> allowed = new LinkedHashMap<>();
        allowed.put("parse", List.of());
        allowed.put("roles", roles);
        body.put("allowed_mentions", allowed);
        return Json.write(body);
    }

    static String cut(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }
}
