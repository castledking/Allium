package codes.castled.allium.discord;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The embeds each staff feed posts. They are for staff in Discord, so they are in English, like the console. */
public final class StaffPosts {

    /** A moderation action that was saved: what, by whom, on whom, why and until when. */
    public record Notice(String action, String staff, String player, @Nullable String reason, @Nullable Long expiresAt,
                         @Nullable String jail) {
    }

    private static final Map<String, String> TITLES = Map.ofEntries(
            Map.entry("ban", "Banned"), Map.entry("tempban", "Temporarily banned"), Map.entry("ipban", "IP banned"),
            Map.entry("unban", "Unbanned"), Map.entry("mute", "Muted"), Map.entry("unmute", "Unmuted"), Map.entry("kick", "Kicked"),
            Map.entry("warn", "Warned"), Map.entry("jail", "Jailed"), Map.entry("unjail", "Released from jail"),
            Map.entry("freeze", "Frozen"), Map.entry("unfreeze", "Unfrozen"));
    private static final Map<String, Integer> COLORS = Map.ofEntries(
            Map.entry("ban", 0xC0392B), Map.entry("tempban", 0xE74C3C), Map.entry("ipban", 0x922B21), Map.entry("unban", 0x27AE60),
            Map.entry("mute", 0xE67E22), Map.entry("unmute", 0x2ECC71), Map.entry("kick", 0xF39C12), Map.entry("warn", 0xF1C40F),
            Map.entry("jail", 0x8E44AD), Map.entry("unjail", 0x27AE60), Map.entry("freeze", 0x3498DB), Map.entry("unfreeze", 0x5DADE2));
    static final int NOTE_COLOR = 0x95A5A6;
    static final int REPORT_COLOR = 0x9B59B6;
    static final int ESCALATION_COLOR = 0xC0392B;

    private StaffPosts() {
    }

    public static StaffPost moderation(Notice notice, Instant now) {
        String action = notice.action().toLowerCase(Locale.ROOT);
        List<StaffPost.Field> fields = new ArrayList<>();
        fields.add(new StaffPost.Field("Player", notice.player(), true));
        fields.add(new StaffPost.Field("Staff", notice.staff(), true));
        if (notice.jail() != null) fields.add(new StaffPost.Field("Jail", notice.jail(), true));
        if (notice.expiresAt() != null) fields.add(new StaffPost.Field("Expires", timestamp(notice.expiresAt()), false));
        fields.add(new StaffPost.Field("Reason", notice.reason() == null ? "" : notice.reason(), false));
        return new StaffPost(TITLES.getOrDefault(action, action) + ": " + notice.player(), COLORS.getOrDefault(action, 0x7F8C8D),
                fields, null, now);
    }

    public static StaffPost note(String staff, String player, String note, Instant now) {
        return new StaffPost("Staff note: " + player, NOTE_COLOR, List.of(
                new StaffPost.Field("Player", player, true),
                new StaffPost.Field("Staff", staff, true)), note, now);
    }

    /** A {@code /report}: about a player, or (with {@code player} null) about anything. */
    public static StaffPost report(String reporter, @Nullable String player, String text, @Nullable String where, Instant now) {
        List<StaffPost.Field> fields = new ArrayList<>();
        fields.add(new StaffPost.Field("Reporter", reporter, true));
        if (player != null) fields.add(new StaffPost.Field("Player", player, true));
        fields.add(new StaffPost.Field("Where", where == null ? "" : where, true));
        return new StaffPost(player == null ? "Report from " + reporter : "Report: " + player, REPORT_COLOR, fields, text, now);
    }

    public static StaffPost escalation(String staff, String target, String reason, Instant now) {
        return new StaffPost("Escalation: " + target, ESCALATION_COLOR, List.of(
                new StaffPost.Field("Target", target, true),
                new StaffPost.Field("From", staff, true)), reason, now);
    }

    /** Discord shows {@code <t:seconds:f>} in each reader's own time zone, then how long until then. */
    static String timestamp(long epochMillis) {
        long seconds = epochMillis / 1000L;
        return "<t:" + seconds + ":f> (<t:" + seconds + ":R>)";
    }
}
