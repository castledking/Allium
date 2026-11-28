package codes.castled.allium.portable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Pure Java 8 asynchronous Discord webhook relay for native Fabric (1.14–26.3) and NeoForge
 * (1.20.2–26.3) servers. Zero external dependencies.
 *
 * <p>Reads {@code discord.*} and {@code chat.channels.*} from {@code plugins/Allium/config.yml}
 * to relay global chat, staff chat, join/quit notices, moderation actions, staff notes, and reports.
 */
public final class NativeDiscordRelay {
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 5000;

    private final ExecutorService executor;
    private final boolean syncMode;

    public NativeDiscordRelay() {
        this(false);
    }

    public NativeDiscordRelay(boolean syncMode) {
        this.syncMode = syncMode;
        this.executor = syncMode ? null : Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "Allium-Discord-Relay");
                t.setDaemon(true);
                return t;
            }
        });
    }

    public void postChat(NativeConfig config, String channelId, UUID playerId,
                         String playerName, String displayName, String message) {
        if (config == null || message == null || message.trim().isEmpty()) return;
        NativeYaml yml = config.raw();
        String webhook = chatWebhook(yml, channelId);
        if (!isAllowedWebhook(webhook)) return;

        String cleanName = NativePlayerInfo.stripColorCodes(displayName == null || displayName.isEmpty() ? playerName : displayName);
        String cleanMsg = NativePlayerInfo.stripColorCodes(message);
        String avatar = playerId != null
                ? "https://mc-heads.net/avatar/" + playerId.toString().replace("-", "") + "/64"
                : yml.getString("discord.webhook-avatar", "");

        String payload = buildChatPayload(cleanName, avatar, cleanMsg);
        dispatch(webhook, payload);
    }

    public void postLifecycle(NativeConfig config, String message) {
        if (config == null || message == null || message.trim().isEmpty()) return;
        NativeYaml yml = config.raw();
        String webhook = chatWebhook(yml, "global");
        if (!isAllowedWebhook(webhook)) return;
        String botName = nonBlank(yml.getString("discord.webhook-name", "Allium"), "Allium");
        String avatar = yml.getString("discord.webhook-avatar", "");
        String payload = buildChatPayload(botName, avatar, NativePlayerInfo.stripColorCodes(message));
        dispatch(webhook, payload);
    }

    public void postModeration(NativeConfig config, String action, String actor, String target,
                               String duration, String reason) {
        if (config == null || action == null) return;
        NativeYaml yml = config.raw();
        String webhook = yml.getString("discord.channels.moderation.webhook", "").trim();
        if (!isAllowedWebhook(webhook)) return;
        List<String> allowed = yml.getStringList("discord.channels.moderation.actions");
        if (!allowed.isEmpty() && !containsIgnoreCase(allowed, action)) return;

        List<String> mentions = roleIds(yml.getStringList("discord.channels.moderation.mentions"));
        String title = action.substring(0, 1).toUpperCase(Locale.ROOT) + action.substring(1).toLowerCase(Locale.ROOT);
        StringBuilder desc = new StringBuilder();
        desc.append("**Target:** ").append(target);
        desc.append("\n**Staff:** ").append(actor);
        if (duration != null && !duration.isEmpty()) desc.append("\n**Duration:** ").append(duration);
        if (reason != null && !reason.isEmpty()) desc.append("\n**Reason:** ").append(reason);
        int color = colorForAction(action);
        dispatch(webhook, buildEmbedPayload(yml, mentions, title, desc.toString(), color));
    }

    public void postNote(NativeConfig config, String actor, String target, String note) {
        if (config == null) return;
        NativeYaml yml = config.raw();
        String webhook = yml.getString("discord.channels.notes.webhook", "").trim();
        if (!isAllowedWebhook(webhook)) return;
        List<String> mentions = roleIds(yml.getStringList("discord.channels.notes.mentions"));
        String desc = "**Player:** " + target + "\n**Staff:** " + actor + "\n**Note:** " + note;
        dispatch(webhook, buildEmbedPayload(yml, mentions, "Staff Note", desc, 0x3498DB));
    }

    public void postReport(NativeConfig config, String reporter, String target, String reason) {
        if (config == null) return;
        NativeYaml yml = config.raw();
        String webhook = yml.getString("discord.channels.reports.webhook", "").trim();
        if (!isAllowedWebhook(webhook)) return;
        List<String> mentions = roleIds(yml.getStringList("discord.channels.reports.mentions"));
        String desc = (target != null && !target.isEmpty() ? "**Reported:** " + target + "\n" : "")
                + "**Reporter:** " + reporter + "\n**Details:** " + reason;
        dispatch(webhook, buildEmbedPayload(yml, mentions, target == null ? "Help Request" : "Player Report", desc, 0xE67E22));
    }

    static String chatWebhook(NativeYaml yml, String channelId) {
        String id = channelId == null ? "global" : channelId.toLowerCase(Locale.ROOT);
        if ("staff".equals(id) || "staff-chat".equals(id)) {
            String w = yml.getString("discord.channels.staff.webhook", "").trim();
            if (w.isEmpty()) w = yml.getString("chat.channels.list.staff-chat.discord-webhook", "").trim();
            return w;
        }
        if ("local".equals(id)) {
            if (yml.getBoolean("chat.channels.local-channel.exclude-from-discord", true)) return "";
            return yml.getString("discord.channels.local.webhook", "").trim();
        }
        String w = yml.getString("discord.channels.chat.webhook", "").trim();
        if (w.isEmpty()) w = yml.getString("chat.channels.list.default.discord-webhook", "").trim();
        return w;
    }

    public static boolean isAllowedWebhook(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean local = "localhost".equals(host) || "127.0.0.1".equals(host)
                    || "::1".equals(host) || "[::1]".equals(host);
            return "https".equalsIgnoreCase(scheme) || ("http".equalsIgnoreCase(scheme) && local);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static String buildChatPayload(String username, String avatarUrl, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"username\":\"").append(escapeJson(truncate(username, 80))).append("\"");
        if (avatarUrl != null && !avatarUrl.trim().isEmpty()) {
            sb.append(",\"avatar_url\":\"").append(escapeJson(avatarUrl.trim())).append("\"");
        }
        sb.append(",\"content\":\"").append(escapeJson(truncate(content, 1900))).append("\"");
        sb.append(",\"allowed_mentions\":{\"parse\":[]}}");
        return sb.toString();
    }

    static String buildEmbedPayload(NativeYaml yml, List<String> roleMentions,
                                    String title, String description, int color) {
        String botName = nonBlank(yml.getString("discord.webhook-name", "Allium"), "Allium");
        String avatar = yml.getString("discord.webhook-avatar", "").trim();
        String serverName = yml.getString("discord.server-name", "").trim();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"username\":\"").append(escapeJson(truncate(botName, 80))).append("\"");
        if (!avatar.isEmpty()) {
            sb.append(",\"avatar_url\":\"").append(escapeJson(avatar)).append("\"");
        }
        if (!roleMentions.isEmpty()) {
            StringBuilder ping = new StringBuilder();
            for (int i = 0; i < roleMentions.size(); i++) {
                if (i > 0) ping.append(' ');
                ping.append("<@&").append(roleMentions.get(i)).append('>');
            }
            sb.append(",\"content\":\"").append(escapeJson(ping.toString())).append("\"");
            sb.append(",\"allowed_mentions\":{\"parse\":[],\"roles\":[");
            for (int i = 0; i < roleMentions.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(escapeJson(roleMentions.get(i))).append('"');
            }
            sb.append("]}");
        } else {
            sb.append(",\"allowed_mentions\":{\"parse\":[]}");
        }
        sb.append(",\"embeds\":[{\"title\":\"").append(escapeJson(title))
                .append("\",\"description\":\"").append(escapeJson(truncate(description, 3500)))
                .append("\",\"color\":").append(color);
        if (!serverName.isEmpty()) {
            sb.append(",\"footer\":{\"text\":\"").append(escapeJson(serverName)).append("\"}");
        }
        sb.append("}]}");
        return sb.toString();
    }

    private void dispatch(final String webhookUrl, final String jsonBody) {
        Runnable job = new Runnable() {
            @Override
            public void run() {
                sendHttpPost(webhookUrl, jsonBody);
            }
        };
        if (syncMode || executor == null) {
            job.run();
        } else {
            try {
                executor.execute(job);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void sendHttpPost(String webhookUrl, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(webhookUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "Allium-Native-Relay");
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
            conn.getResponseCode();
        } catch (IOException ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static List<String> roleIds(List<String> raw) {
        List<String> out = new ArrayList<String>();
        for (String item : raw) {
            if (item == null) continue;
            String clean = item.trim().replaceAll("^<@&|>$", "");
            if (clean.matches("[0-9]{17,22}")) out.add(clean);
        }
        return out;
    }

    private static boolean containsIgnoreCase(List<String> list, String target) {
        for (String s : list) {
            if (s != null && s.equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    private static int colorForAction(String action) {
        String a = action.toLowerCase(Locale.ROOT);
        if (a.contains("unmute") || a.contains("unjail") || a.contains("unfreeze") || a.contains("unban")) {
            return 0x2ECC71;
        }
        if (a.contains("warn")) return 0xF1C40F;
        if (a.contains("freeze")) return 0x3498DB;
        return 0xE74C3C;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
