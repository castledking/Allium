package codes.castled.allium.discord;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@code discord:} in config.yml: where each staff feed posts. A feed goes to {@code channel}
 * through DiscordSRV when DiscordSRV runs (a channel ID, or the name of a channel in DiscordSRV's own
 * {@code Channels} setting), else to {@code webhook}; a feed with neither is off.
 *
 * <p>Pre-recode Allium's {@code discord.escalate_webhook}, {@code discord.escalate_mentions},
 * {@code discord.bot_name}, {@code discord.bot_avatar} and {@code server-name} still work as the
 * escalation webhook, its role pings, the webhook name and avatar and the footer.
 */
public record DiscordFeeds(String serverName, String webhookName, String webhookAvatar, Map<Feed, Route> routes) {

    public enum Feed {
        /** Bans, temporary and IP bans, unbans, mutes, unmutes, kicks, warnings, jails, freezes. */
        MODERATION("moderation"),
        NOTES("notes"),
        REPORTS("reports"),
        ESCALATIONS("escalations");

        private final String key;

        Feed(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /** Moderation actions a feed may post; {@code actions} narrows the moderation feed to some of them. */
    public static final List<String> MODERATION_ACTIONS = List.of("ban", "tempban", "ipban", "unban", "mute", "unmute", "kick",
            "warn", "jail", "unjail", "freeze", "unfreeze");

    /**
     * @param channel  a channel ID or DiscordSRV channel name, or empty
     * @param webhook  a webhook URL, or empty
     * @param mentions role IDs pinged with each post
     * @param actions  for the moderation feed, which actions it posts
     */
    public record Route(String channel, String webhook, List<String> mentions, Set<String> actions) {
        public Route {
            mentions = List.copyOf(mentions);
            actions = Set.copyOf(actions);
        }

        public boolean enabled() {
            return !channel.isEmpty() || !webhook.isEmpty();
        }

        public boolean posts(String action) {
            return actions.contains(action.toLowerCase(Locale.ROOT));
        }

        static final Route OFF = new Route("", "", List.of(), Set.of());
    }

    public DiscordFeeds {
        routes = Collections.unmodifiableMap(new EnumMap<>(routes));
    }

    public Route route(Feed feed) {
        return routes.getOrDefault(feed, Route.OFF);
    }

    public static DiscordFeeds read(ConfigurationSection root, Consumer<String> warn) {
        ConfigurationSection discord = root.getConfigurationSection("discord");
        String serverName = string(discord, "server-name", root.getString("server-name", ""));
        String webhookName = string(discord, "webhook-name", discord == null ? "Allium" : discord.getString("bot_name", "Allium"));
        String webhookAvatar = string(discord, "webhook-avatar", discord == null ? "" : discord.getString("bot_avatar", ""));
        Map<Feed, Route> routes = new EnumMap<>(Feed.class);
        ConfigurationSection channels = discord == null ? null : discord.getConfigurationSection("channels");
        for (Feed feed : Feed.values()) {
            ConfigurationSection section = channels == null ? null : channels.getConfigurationSection(feed.key);
            String channel = section == null ? "" : section.getString("channel", "").trim();
            String webhook = section == null ? "" : section.getString("webhook", "").trim();
            List<String> mentions = section == null ? List.of() : section.getStringList("mentions");
            if (feed == Feed.ESCALATIONS && discord != null) {
                if (webhook.isEmpty()) webhook = discord.getString("escalate_webhook", "").trim();
                if (mentions.isEmpty()) mentions = discord.getStringList("escalate_mentions");
            }
            webhook = webhook(feed, webhook, warn);
            List<String> roles = new ArrayList<>();
            for (String raw : mentions) {
                String id = raw == null ? "" : raw.trim().replaceAll("^<@&|>$", "");
                if (StaffPost.isRoleId(id)) roles.add(id);
                else warn.accept("discord.channels." + feed.key + ".mentions: '" + raw + "' is not a role ID; it is left out.");
            }
            Set<String> actions = new LinkedHashSet<>();
            if (feed == Feed.MODERATION) {
                List<String> listed = section == null || !section.isList("actions") ? MODERATION_ACTIONS : section.getStringList("actions");
                for (String action : listed) {
                    String key = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
                    if (MODERATION_ACTIONS.contains(key)) actions.add(key);
                    else warn.accept("discord.channels.moderation.actions: '" + action + "' is not one of "
                            + String.join(", ", MODERATION_ACTIONS) + ".");
                }
            }
            routes.put(feed, new Route(channel, webhook, roles, actions));
        }
        return new DiscordFeeds(serverName, webhookName.isBlank() ? "Allium" : webhookName, webhookAvatar, routes);
    }

    /** A webhook URL Allium will post to: https, or http to this machine (a local relay); else empty. */
    private static String webhook(Feed feed, String raw, Consumer<String> warn) {
        if (raw.isEmpty()) return "";
        try {
            URI uri = URI.create(raw);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]");
            if ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()) && local) return raw;
        } catch (IllegalArgumentException invalid) {
            // Reported below.
        }
        warn.accept("discord.channels." + feed.key + ".webhook is not an https URL; the feed's webhook is off.");
        return "";
    }

    private static String string(@Nullable ConfigurationSection section, String key, String fallback) {
        String value = section == null ? null : section.getString(key);
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback.trim()) : value.trim();
    }
}
