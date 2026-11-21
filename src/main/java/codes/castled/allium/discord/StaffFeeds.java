package codes.castled.allium.discord;

import codes.castled.allium.PluginStart;
import codes.castled.allium.discord.DiscordFeeds.Feed;
import codes.castled.allium.discord.DiscordFeeds.Route;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Posts the staff feeds ({@link DiscordFeeds}) to Discord: moderation actions, staff notes,
 * {@code /report} and {@code /escalate}. A post goes through DiscordSRV's bot when DiscordSRV is
 * connected and the feed names a channel, else to the feed's webhook. Posting never waits: webhook
 * requests run on Java's HTTP client, a rate limit is retried once, and a failure is logged (at
 * most once a minute per feed) and dropped.
 */
public final class StaffFeeds {

    private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)");
    private static final long WARN_EVERY_MILLIS = 60_000L;

    private final PluginStart plugin;
    private final Map<String, Long> warned = new ConcurrentHashMap<>();
    private volatile DiscordFeeds settings;
    @Nullable
    private volatile DiscordSrvRoute discordSrv;
    @Nullable
    private HttpClient http;

    public StaffFeeds(PluginStart plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Re-reads {@code discord:} and looks for DiscordSRV again. Server thread. */
    public void reload() {
        settings = DiscordFeeds.read(plugin.getConfig(), plugin.logger()::warning);
        DiscordSrvRoute route = null;
        try {
            if (plugin.getServer().getPluginManager().isPluginEnabled("DiscordSRV")) route = new DiscordSrvRoute();
        } catch (LinkageError incompatible) {
            plugin.logger().warning("DiscordSRV is installed but its API could not be loaded; staff feeds use their webhooks: " + incompatible);
        }
        discordSrv = route;
    }

    public DiscordFeeds settings() {
        return settings;
    }

    /** Whether a moderation action posts anywhere. */
    public boolean posts(String action) {
        Route route = settings.route(Feed.MODERATION);
        return route.enabled() && route.posts(action);
    }

    /**
     * Posts a saved moderation action to the moderation feed (when it is on for that action), or a
     * staff note ({@code note}) to the notes feed. Any thread.
     */
    public void moderation(StaffPosts.Notice notice) {
        if ("note".equals(notice.action())) {
            post(Feed.NOTES, StaffPosts.note(notice.staff(), notice.player(), notice.reason() == null ? "" : notice.reason(), Instant.now()));
        } else if (posts(notice.action())) {
            post(Feed.MODERATION, StaffPosts.moderation(notice, Instant.now()));
        }
    }

    /** Posts {@code post} to {@code feed}. Any thread. */
    public void post(Feed feed, StaffPost post) {
        DiscordFeeds current = settings;
        Route route = current.route(feed);
        if (!route.enabled()) return;
        String footer = current.serverName().isEmpty() ? null : current.serverName();
        DiscordSrvRoute bot = discordSrv;
        if (!route.channel().isEmpty() && bot != null
                && bot.send(route.channel(), post, footer, route.mentions(), reason -> warn(feed, "DiscordSRV could not post it: " + reason))) {
            return;
        }
        if (!route.webhook().isEmpty()) {
            send(feed, route.webhook(), post.webhookPayload(current.webhookName(), current.webhookAvatar(), footer, route.mentions()), true);
        } else {
            warn(feed, bot == null ? "DiscordSRV is not running and the feed has no webhook"
                    : "DiscordSRV is not connected or has no channel '" + route.channel() + "', and the feed has no webhook");
        }
    }

    private void send(Feed feed, String url, String body, boolean retry) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Allium (Minecraft server plugin)")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        } catch (IllegalArgumentException invalid) {
            warn(feed, "its webhook URL is invalid");
            return;
        }
        client().sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            if (error != null) {
                warn(feed, "the webhook could not be reached: " + error.getMessage());
                return;
            }
            int status = response.statusCode();
            if (status == 429 && retry) {
                plugin.getScheduler().runAsyncLater(() -> send(feed, url, body, false), retryAfter(response.body()));
            } else if (status < 200 || status >= 300) {
                warn(feed, "the webhook answered HTTP " + status);
            }
        });
    }

    private synchronized HttpClient client() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        }
        return http;
    }

    /** Discord's {@code retry_after} (seconds) in milliseconds, between 1 and 10 seconds. */
    static long retryAfter(@Nullable String body) {
        if (body != null) {
            Matcher m = RETRY_AFTER.matcher(body);
            if (m.find()) {
                try {
                    return Math.max(1_000L, Math.min(10_000L, (long) (Double.parseDouble(m.group(1)) * 1000.0D)));
                } catch (NumberFormatException ignored) {
                    // Fall through.
                }
            }
        }
        return 2_000L;
    }

    private void warn(Feed feed, String reason) {
        long now = System.currentTimeMillis();
        Long last = warned.get(feed.key());
        if (last != null && now - last < WARN_EVERY_MILLIS) return;
        warned.put(feed.key(), now);
        plugin.logger().warning("Discord " + feed.key() + " feed: a post was dropped because " + reason + ".");
    }
}
