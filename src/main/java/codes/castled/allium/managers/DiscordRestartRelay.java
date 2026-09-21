package codes.castled.allium.managers;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import github.scarsz.discordsrv.dependencies.jda.api.EmbedBuilder;
import github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.lang.Lang;
import codes.castled.allium.util.SchedulerAdapter;

import org.bukkit.configuration.file.YamlConfiguration;

import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Relays auto-restart countdown messages to Discord via the DiscordSRV bot.
 * Reads channel mappings from {@code plugins/DiscordSRV/config.yml} and sends
 * embeds to the same text channel used for global chat.
 */
public final class DiscordRestartRelay {

    /**
     * Discord rate-limits channel edits (topic included) to roughly 2 per 10 minutes
     * per channel. Anything faster gets queued by JDA and lands late, so non-forced
     * topic updates are throttled to this interval.
     */
    private static final long TOPIC_MIN_INTERVAL_MS = 300_000L;

    /** Discord's hard cap on channel topic length. */
    private static final int TOPIC_MAX_LENGTH = 1024;

    private final PluginStart plugin;
    private TextChannel cachedChannel;

    private volatile long lastTopicUpdate;

    public DiscordRestartRelay(PluginStart plugin) {
        this.plugin = plugin;
    }

    /**
     * Resolves and caches the Discord text channel from DiscordSRV's channel mapping.
     * Uses DiscordSRV's API to get the main text channel (the first channel configured),
     * which is the same channel used for global chat relay.
     */
    public TextChannel resolveChannel() {
        if (cachedChannel != null && cachedChannel.canTalk()) {
            return cachedChannel;
        }

        DiscordSRV discordSrv = DiscordSRV.getPlugin();
        if (discordSrv == null) {
            return null;
        }

        // Primary: get the main text channel directly from DiscordSRV API
        try {
            TextChannel tc = discordSrv.getMainTextChannel();
            if (tc != null && tc.canTalk()) {
                cachedChannel = tc;
                return cachedChannel;
            }
        } catch (Throwable ignored) {
        }

        // Fallback 1: try "global" game channel name
        try {
            Object obj = discordSrv.getDestinationTextChannelForGameChannelName("global");
            if (obj instanceof TextChannel tc && tc.canTalk()) {
                cachedChannel = tc;
                return cachedChannel;
            }
        } catch (Throwable ignored) {
        }

        // Fallback 2: parse raw config to find the first channel mapping
        try {
            YamlConfiguration dsrvConfig = loadDiscordSrvConfig();
            if (dsrvConfig != null) {
                String channelsStr = dsrvConfig.getString("Channels", "");
                Map<?, ?> channels = null;
                if (dsrvConfig.isConfigurationSection("Channels")) {
                    channels = dsrvConfig.getConfigurationSection("Channels").getValues(false);
                }
                if (channels == null && !channelsStr.isEmpty()) {
                    channels = parseInlineMap(channelsStr);
                }
                if (channels != null) {
                    for (Object value : channels.values()) {
                        String discordChannelId = String.valueOf(value).replaceAll("[^0-9]", "");
                        if (!discordChannelId.isEmpty()) {
                            TextChannel tc = discordSrv.getJda().getTextChannelById(discordChannelId);
                            if (tc != null && tc.canTalk()) {
                                cachedChannel = tc;
                                return cachedChannel;
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "[DiscordRestartRelay] Failed to resolve channel from raw config: " + t.getMessage());
        }

        return null;
    }

    private YamlConfiguration loadDiscordSrvConfig() {
        File file = new File(plugin.getDataFolder().getParentFile(), "DiscordSRV/config.yml");
        if (!file.exists()) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(file);
             InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[DiscordRestartRelay] Failed to read DiscordSRV config: " + e.getMessage());
            return null;
        }
    }

    private Map<String, String> parseInlineMap(String inline) {
        Map<String, String> result = new LinkedHashMap<>();
        String stripped = inline.trim();
        if (stripped.startsWith("{") && stripped.endsWith("}")) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }
        for (String pair : stripped.split(",")) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                String key = kv[0].trim().replace("\"", "");
                String val = kv[1].trim().replace("\"", "");
                result.put(key, val);
            }
        }
        return result;
    }

    /**
     * Builds the restart embed. Shared by the one-shot and edit-in-place paths so
     * the countdown and the final message look identical.
     */
    private MessageEmbed buildEmbed(String title, String description, Color embedColor) {
        Lang lang = plugin.getLangManager();
        String stoppedMessage = stripMinecraftFormatting(lang.get("autorestart.server-stopped-message"));

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle(title);
        embed.setDescription(description);
        embed.setColor(embedColor);
        embed.setFooter(stoppedMessage);
        embed.setTimestamp(Instant.now());
        return embed.build();
    }

    /**
     * Sends a brand new restart embed to the resolved Discord channel.
     * Used for one-off announcements (vote results) and for the final
     * "restarting now" notice, which must not overwrite the countdown.
     * Runs asynchronously via {@link SchedulerAdapter#runAsync(Runnable)}.
     */
    public void sendRestartEmbed(String title, String description, Color embedColor) {
        SchedulerAdapter.runAsync(() -> {
            try {
                TextChannel channel = resolveChannel();
                if (channel == null) {
                    return;
                }
                channel.sendMessageEmbeds(buildEmbed(title, description, embedColor)).queue(
                        msg -> {},
                        failure -> plugin.getLogger().log(Level.WARNING,
                                "[DiscordRestartRelay] Failed to send embed: " + failure.getMessage())
                );
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "[DiscordRestartRelay] Error sending embed: " + t.getMessage());
            }
        });
    }

    /**
     * Resets per-restart state so a new cycle starts with a fresh topic-throttle budget.
     */
    public void resetForNewCycle() {
        lastTopicUpdate = 0L;
    }

    /**
     * Sets the Discord channel description (topic). Non-forced calls are throttled to
     * {@link #TOPIC_MIN_INTERVAL_MS} because of Discord's channel-edit rate limit; the
     * final "restarting now" topic passes {@code force} so it always goes through.
     * The topic is left as-is on shutdown - DiscordSRV overwrites it again on next boot.
     */
    public void updateChannelTopic(String topic, boolean force) {
        if (topic == null || topic.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastTopicUpdate < TOPIC_MIN_INTERVAL_MS) {
            return;
        }
        lastTopicUpdate = now;
        String trimmed = topic.length() > TOPIC_MAX_LENGTH ? topic.substring(0, TOPIC_MAX_LENGTH) : topic;
        SchedulerAdapter.runAsync(() -> {
            try {
                TextChannel channel = resolveChannel();
                if (channel == null) {
                    return;
                }
                channel.getManager().setTopic(trimmed).queue(
                        ok -> {},
                        failure -> plugin.getLogger().log(Level.WARNING,
                                "[DiscordRestartRelay] Failed to set channel topic: " + failure.getMessage())
                );
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "[DiscordRestartRelay] Error setting channel topic: " + t.getMessage());
            }
        });
    }

    public void clearCache() {
        cachedChannel = null;
    }

    /**
     * Strips Minecraft formatting codes ({@code §c}, {@code &6}, etc.) from a string
     * so they display as plain text in Discord embeds.
     */
    public static String stripMinecraftFormatting(String text) {
        if (text == null) return "";
        return text.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "").trim();
    }

    /**
     * Parses a hex color string (with or without leading '#') into a {@link Color}.
     * Returns yellow as default if parsing fails.
     */
    public static Color parseHexColor(String hex) {
        if (hex == null || hex.isBlank()) {
            return Color.YELLOW;
        }
        try {
            String cleaned = hex.trim().replaceFirst("^#", "");
            int rgb = Integer.parseInt(cleaned, 16);
            return new Color(rgb);
        } catch (NumberFormatException e) {
            return Color.YELLOW;
        }
    }
}
