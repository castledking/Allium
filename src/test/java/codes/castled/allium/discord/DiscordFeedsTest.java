package codes.castled.allium.discord;

import codes.castled.allium.discord.DiscordFeeds.Feed;
import codes.castled.allium.pack.Json;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordFeedsTest {

    private static DiscordFeeds read(String yaml, List<String> warnings) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return DiscordFeeds.read(config, warnings::add);
    }

    @Test
    void theBundledConfigLeavesEveryFeedOffWithoutWarnings() throws Exception {
        YamlConfiguration bundled;
        try (var in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("config.yml"))) {
            bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<String> warnings = new ArrayList<>();
        DiscordFeeds feeds = DiscordFeeds.read(bundled, warnings::add);
        assertEquals(List.of(), warnings);
        for (Feed feed : Feed.values()) assertFalse(feeds.route(feed).enabled(), feed.key());
        assertEquals("Allium", feeds.webhookName());
        assertEquals(DiscordFeeds.MODERATION_ACTIONS.size(), feeds.route(Feed.MODERATION).actions().size());
    }

    @Test
    void channelsWebhooksMentionsAndActionsAreRead() throws Exception {
        List<String> warnings = new ArrayList<>();
        DiscordFeeds feeds = read("""
                discord:
                  server-name: Survival
                  channels:
                    moderation:
                      channel: staff-log
                      actions: [ban, TEMPBAN, kick, explode]
                    reports:
                      webhook: https://discord.com/api/webhooks/1/abc
                      mentions: ['123456789012345678', '<@&223456789012345678>', 'mods']
                    notes:
                      webhook: http://example.com/hook
                    escalations:
                      webhook: http://127.0.0.1:8080/hook
                """, warnings);
        assertEquals("Survival", feeds.serverName());
        assertEquals("staff-log", feeds.route(Feed.MODERATION).channel());
        assertTrue(feeds.route(Feed.MODERATION).posts("tempban"));
        assertFalse(feeds.route(Feed.MODERATION).posts("mute"));
        assertEquals(List.of("123456789012345678", "223456789012345678"), feeds.route(Feed.REPORTS).mentions());
        assertFalse(feeds.route(Feed.NOTES).enabled(), "plain http to another host is refused");
        assertEquals("http://127.0.0.1:8080/hook", feeds.route(Feed.ESCALATIONS).webhook(), "a local relay may use http");
        String all = String.join("\n", warnings);
        assertEquals(3, warnings.size(), all);
        assertTrue(all.contains("'explode' is not one of"), all);
        assertTrue(all.contains("'mods' is not a role ID"), all);
        assertTrue(all.contains("notes.webhook is not an https URL"), all);
    }

    @Test
    void preRecodeEscalationKeysStillWork() throws Exception {
        DiscordFeeds feeds = read("""
                server-name: Legacy Survival
                discord:
                  escalate_webhook: https://discord.com/api/webhooks/9/xyz
                  escalate_mentions: ['323456789012345678']
                  bot_name: Server Escalation
                  bot_avatar: https://example.com/a.png
                """, new ArrayList<>());
        assertEquals("https://discord.com/api/webhooks/9/xyz", feeds.route(Feed.ESCALATIONS).webhook());
        assertEquals(List.of("323456789012345678"), feeds.route(Feed.ESCALATIONS).mentions());
        assertEquals("Server Escalation", feeds.webhookName());
        assertEquals("https://example.com/a.png", feeds.webhookAvatar());
        assertEquals("Legacy Survival", feeds.serverName());
    }

    @Test
    void onlyTheFeedsRolesCanPingAndTypedTextStaysText() {
        StaffPost post = StaffPosts.report("Alex", "Steve", "@everyone <@&999999999999999999> griefed", "world 1, 64, 2",
                Instant.parse("2026-11-21T10:00:00Z"));
        Map<String, Object> body = Json.parseObject(post.webhookPayload("Allium", "", "Survival", List.of("123456789012345678", "bad")));
        assertEquals("<@&123456789012345678>", body.get("content"));
        Map<?, ?> allowed = (Map<?, ?>) body.get("allowed_mentions");
        assertEquals(List.of(), allowed.get("parse"));
        assertEquals(List.of("123456789012345678"), allowed.get("roles"));
        Map<?, ?> embed = (Map<?, ?>) ((List<?>) body.get("embeds")).get(0);
        assertEquals("Report: Steve", embed.get("title"));
        assertEquals("@everyone <@&999999999999999999> griefed", embed.get("description"));
        assertEquals(Map.of("text", "Survival"), embed.get("footer"));
        assertEquals("2026-11-21T10:00:00Z", embed.get("timestamp"));
        List<?> fields = (List<?>) embed.get("fields");
        assertEquals(3, fields.size());
    }

    @Test
    void moderationPostsShowWhenATemporaryActionEnds() {
        StaffPost tempban = StaffPosts.moderation(new StaffPosts.Notice("tempban", "Mod", "Steve", "griefing", 1_800_000_000_000L, null),
                Instant.EPOCH);
        assertEquals("Temporarily banned: Steve", tempban.title());
        assertTrue(tempban.fields().stream().anyMatch(f -> f.name().equals("Expires") && f.value().equals("<t:1800000000:f> (<t:1800000000:R>)")));
        StaffPost unban = StaffPosts.moderation(new StaffPosts.Notice("unban", "Mod", "Steve", null, null, null), Instant.EPOCH);
        assertEquals(List.of("Player", "Staff"), unban.fields().stream().map(StaffPost.Field::name).toList(), "an empty reason is left out");
        StaffPost jail = StaffPosts.moderation(new StaffPosts.Notice("jail", "Mod", "Steve", "x", null, "cell"), Instant.EPOCH);
        assertTrue(jail.fields().stream().anyMatch(f -> f.name().equals("Jail") && f.value().equals("cell")));
        assertEquals(StaffPost.FIELD_VALUE_LIMIT, new StaffPost("t", 0, List.of(new StaffPost.Field("n", "x".repeat(5000), false)),
                null, Instant.EPOCH).fields().get(0).value().length());
    }

    @Test
    void discordsRetryAfterIsRespectedWithinBounds() {
        assertEquals(1_500L, StaffFeeds.retryAfter("{\"message\":\"You are being rate limited.\",\"retry_after\":1.5,\"global\":false}"));
        assertEquals(10_000L, StaffFeeds.retryAfter("{\"retry_after\": 60}"));
        assertEquals(2_000L, StaffFeeds.retryAfter("nope"));
    }
}
