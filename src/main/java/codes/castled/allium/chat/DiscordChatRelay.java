package codes.castled.allium.chat;

import codes.castled.allium.PluginStart;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.DiscordGuildMessagePreProcessEvent;
import github.scarsz.discordsrv.api.events.DiscordReadyEvent;
import github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent;
import github.scarsz.discordsrv.dependencies.jda.api.JDA;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Member;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import github.scarsz.discordsrv.dependencies.jda.api.events.message.guild.GuildMessageDeleteEvent;
import github.scarsz.discordsrv.dependencies.jda.api.hooks.ListenerAdapter;
import github.scarsz.discordsrv.dependencies.json.JSONObject;
import github.scarsz.discordsrv.dependencies.okhttp3.Call;
import github.scarsz.discordsrv.dependencies.okhttp3.Callback;
import github.scarsz.discordsrv.dependencies.okhttp3.MediaType;
import github.scarsz.discordsrv.dependencies.okhttp3.Request;
import github.scarsz.discordsrv.dependencies.okhttp3.RequestBody;
import github.scarsz.discordsrv.dependencies.okhttp3.Response;
import github.scarsz.discordsrv.util.DiscordUtil;
import github.scarsz.discordsrv.util.MessageUtil;
import github.scarsz.discordsrv.util.PlaceholderUtil;
import github.scarsz.discordsrv.util.WebhookUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/** Loaded only with DiscordSRV. Correlation uses returned IDs, never text/history searches. */
public final class DiscordChatRelay implements ChatRelay {
    static final String DEFAULT_DISCORD_OUT = "{player}: {message}";

    private final PluginStart plugin;
    private final ChatService service;

    record WebhookPayload(String username, String avatarUrl, String content, boolean filtered) {}

    interface Runtime {
        JDA jda();
        String user(UUID id);
        String webhook(TextChannel channel);
        void subscribe(Object listener);
        void unsubscribe(Object listener);

        default boolean webhookDeliveryEnabled() { return false; }

        default TextChannel mappedChannel(ChatSettings.Channel channel, String defaultChannelId) { return null; }

        default String displayName(UUID senderId, String fallback) { return fallback; }

        default WebhookPayload formatWebhook(UUID senderId, String playerName, String displayName,
                                             ChatSettings.Channel channel, TextChannel destination, String body) {
            return null;
        }
    }

    private static final class DiscordRuntime implements Runtime {
        @Override
        public JDA jda() {
            DiscordSRV srv = DiscordSRV.getPlugin();
            return srv == null ? null : srv.getJda();
        }

        @Override
        public String user(UUID id) {
            DiscordSRV srv = DiscordSRV.getPlugin();
            if (srv == null || srv.getAccountLinkManager() == null) return null;
            return srv.getAccountLinkManager().getDiscordId(id);
        }

        @Override
        public String webhook(TextChannel channel) {
            return WebhookUtil.getWebhookUrlToUseForChannel(channel);
        }

        @Override
        public void subscribe(Object listener) {
            DiscordSRV.api.subscribe(listener);
        }

        @Override
        public void unsubscribe(Object listener) {
            DiscordSRV.api.unsubscribe(listener);
        }

        @Override
        public boolean webhookDeliveryEnabled() {
            return DiscordSRV.config().getBoolean("Experiment_WebhookChatMessageDelivery");
        }

        @Override
        public TextChannel mappedChannel(ChatSettings.Channel channel, String defaultChannelId) {
            DiscordSRV srv = DiscordSRV.getPlugin();
            if (srv == null) return null;
            TextChannel mapped = srv.getDestinationTextChannelForGameChannelName(channel.id());
            if (mapped == null && channel.id().equals(defaultChannelId)) {
                mapped = srv.getDestinationTextChannelForGameChannelName("global");
                if (mapped == null) mapped = srv.getMainTextChannel();
            }
            return mapped;
        }

        @Override
        public String displayName(UUID senderId, String fallback) {
            Player online = Bukkit.getPlayer(senderId);
            if (online != null) {
                String display = online.getDisplayName();
                if (display != null && !display.isEmpty()) return display;
            }
            return fallback;
        }

        @Override
        public WebhookPayload formatWebhook(UUID senderId, String playerName, String displayName,
                                            ChatSettings.Channel channel, TextChannel destination, String body) {
            Player online = Bukkit.getPlayer(senderId);
            OfflinePlayer offline = online != null ? online : Bukkit.getOfflinePlayer(senderId);
            String safeName = (playerName != null && !playerName.isEmpty())
                    ? playerName
                    : (offline.getName() != null ? offline.getName() : "");
            String resolvedDisplay = (displayName != null && !displayName.isEmpty()) ? displayName : safeName;

            String avatarUrl;
            try {
                avatarUrl = online != null
                        ? DiscordSRV.getAvatarUrl(online)
                        : DiscordSRV.getAvatarUrl(safeName, senderId);
            } catch (RuntimeException ignored) {
                avatarUrl = null;
            }

            String usernameFormat = DiscordSRV.config().getString("Experiment_WebhookChatMessageUsernameFormat");
            if (usernameFormat == null || usernameFormat.isBlank()) usernameFormat = "%displayname%";
            String username = usernameFormat
                    .replace("%displayname%", resolvedDisplay)
                    .replace("%username%", safeName);
            username = PlaceholderUtil.replacePlaceholdersToDiscord(username, offline);
            username = MessageUtil.strip(username);

            String chatMessage;
            if (usesDefaultWebhookBody(channel.discordOut())) {
                String messageFormat = DiscordSRV.config().getString("Experiment_WebhookChatMessageFormat");
                if (messageFormat == null || messageFormat.isBlank()) messageFormat = "%message%";
                chatMessage = messageFormat
                        .replace("%displayname%", resolvedDisplay)
                        .replace("%username%", safeName)
                        .replace("%message%", body);
                chatMessage = PlaceholderUtil.replacePlaceholdersToDiscord(chatMessage, offline);
            } else {
                chatMessage = formatCustomWebhookOut(channel.discordOut(), safeName, MessageUtil.strip(resolvedDisplay), body);
            }
            if (destination != null && destination.getGuild() != null) {
                chatMessage = DiscordUtil.translateEmotes(chatMessage, destination.getGuild());
            }

            DiscordSRV srv = DiscordSRV.getPlugin();
            Map<Pattern, String> regexes = srv == null ? Map.of() : srv.getGameRegexes();
            String userId = user(senderId);
            Member member = userId == null ? null : DiscordUtil.getMemberById(userId);
            String discordEffectiveName = member == null ? null : member.getEffectiveName();
            String discordUserName = member == null ? null : member.getUser().getName();
            String discordAvatarUrl = member == null ? null : member.getUser().getEffectiveAvatarUrl();

            return finalizeWebhookPayload(
                    username,
                    avatarUrl,
                    chatMessage,
                    MessageUtil.strip(resolvedDisplay),
                    safeName,
                    regexes,
                    discordEffectiveName,
                    discordUserName,
                    discordAvatarUrl,
                    DiscordSRV.config().getBoolean("Experiment_WebhookChatMessageAvatarFromDiscord"),
                    DiscordSRV.config().getBoolean("Experiment_WebhookChatMessageUsernameFromDiscord")
            );
        }
    }

    private final Runtime backend;
    private final Executor worker;
    private final LinkedHashMap<String, Runnable> linked = new LinkedHashMap<>();
    private volatile boolean closed;
    private Object hookedJda;
    private final ListenerAdapter deletes = new ListenerAdapter() {
        @Override
        public void onGuildMessageDelete(GuildMessageDeleteEvent event) {
            service.discordDeleted(event.getMessageId());
        }
    };

    public DiscordChatRelay(PluginStart plugin, ChatService service) {
        this(plugin, service, new DiscordRuntime());
    }

    DiscordChatRelay(PluginStart plugin, ChatService service, Runtime backend) {
        this.plugin = plugin;
        this.service = service;
        this.backend = backend;
        this.worker = plugin.getScheduler().boundedPool("Allium-Chat-Relay", 1, 64);
        backend.subscribe(this);
        hookDeletes();
    }

    @Subscribe
    public void ready(DiscordReadyEvent event) {
        hookDeletes();
    }

    private synchronized void hookDeletes() {
        var jda = backend.jda();
        if (!closed && jda != null && hookedJda != jda) {
            if (hookedJda instanceof JDA previous) previous.removeEventListener(deletes);
            jda.addEventListener(deletes);
            hookedJda = jda;
        }
    }

    @Subscribe
    public void preventDefaultRelay(GameChatMessagePreProcessEvent event) {
        if (service.ownsChat()) event.setCancelled(true);
    }

    @Subscribe
    public void incoming(DiscordGuildMessagePreProcessEvent event) {
        if (closed || event.isCancelled() || !service.ownsChat()) return;
        var channel = service.settings().channels().values().stream()
                .filter(c -> !c.local() && !c.excludeFromDiscord() && c.discordId().equals(event.getChannel().getId()))
                .findFirst().orElse(null);
        if (channel == null) return;
        event.setCancelled(true);
        if (event.getAuthor().isBot() || event.getMessage().isWebhookMessage()) return;
        service.inbound(
                event.getMessage().getId(),
                event.getChannel().getId(),
                event.getMember() == null ? event.getAuthor().getName() : event.getMember().getEffectiveName(),
                event.getMessage().getContentDisplay()
        );
    }

    @Override
    public void send(ChatHistory.Message message, ChatSettings.Channel channel, String player, String body) {
        if (closed || channel.excludeFromDiscord() || message.deleted().get()) return;
        boolean explicitDestination = !channel.discordId().isEmpty();
        var jda = backend.jda();
        if (jda == null) {
            if (explicitDestination) failed(message.id());
            return;
        }
        TextChannel destination = explicitDestination
                ? jda.getTextChannelById(channel.discordId())
                : backend.mappedChannel(channel, service.settings().defaultChannel());
        if (destination == null) {
            if (explicitDestination) failed(message.id());
            return;
        }
        String destinationId = explicitDestination ? channel.discordId() : destination.getId();
        String capturedDisplay = backend.displayName(message.sender(), player);
        if (capturedDisplay == null || capturedDisplay.isEmpty()) capturedDisplay = player;

        boolean useWebhook = channel.webhook() || backend.webhookDeliveryEnabled();
        if (!useWebhook) {
            String content = channel.discordOut()
                    .replace("{player}", player)
                    .replace("{displayname}", capturedDisplay)
                    .replace("{message}", body);
            if (content.length() > 1900) content = content.substring(0, 1900);
            destination.sendMessage(content).allowedMentions(List.of()).queue(
                    sent -> plugin.getScheduler().runOnServer(() -> link(message,
                            () -> sent.delete().queue(ok -> {}, error -> failed(message.id())))),
                    error -> failed(message.id())
            );
            return;
        }

        final String frozenDisplay = capturedDisplay;
        final String frozenDestinationId = destinationId;
        try {
            worker.execute(() -> {
                try {
                    if (closed || message.deleted().get()) return;
                    String url = backend.webhook(destination);
                    if (url == null) {
                        failed(message.id());
                        return;
                    }
                    WebhookPayload payload = backend.formatWebhook(
                            message.sender(), player, frozenDisplay, channel, destination, body);
                    if (payload == null) {
                        payload = defaultWebhookPayload(player, frozenDisplay, channel, body);
                    }
                    if (payload.filtered() || payload.content() == null || payload.content().isBlank()) {
                        return;
                    }
                    var json = new JSONObject();
                    json.put("content", payload.content());
                    json.put("username", payload.username());
                    if (payload.avatarUrl() != null && !payload.avatarUrl().isBlank()) {
                        json.put("avatar_url", payload.avatarUrl());
                    }
                    json.put("allowed_mentions", new JSONObject().put("parse", new ArrayList<>()));
                    var request = new Request.Builder()
                            .url(url + "?wait=true")
                            .post(RequestBody.create(MediaType.parse("application/json"), json.toString()))
                            .build();
                    jda.getHttpClient().newCall(request).enqueue(new Callback() {
                        @Override
                        public void onFailure(Call call, IOException error) {
                            failed(message.id());
                        }

                        @Override
                        public void onResponse(Call call, Response response) throws IOException {
                            try (response) {
                                if (!response.isSuccessful() || response.body() == null) {
                                    failed(message.id());
                                    return;
                                }
                                var saved = new JSONObject(response.body().string());
                                String id = saved.optString("id", "");
                                if (!id.matches("[0-9]{5,24}")
                                        || (frozenDestinationId != null && !frozenDestinationId.isEmpty()
                                        && !frozenDestinationId.equals(saved.optString("channel_id", frozenDestinationId)))) {
                                    failed(message.id());
                                    return;
                                }
                                plugin.getScheduler().runOnServer(() -> link(message,
                                        () -> jda.getHttpClient()
                                                .newCall(new Request.Builder().url(url + "/messages/" + id).delete().build())
                                                .enqueue(new Callback() {
                                                    @Override
                                                    public void onFailure(Call call, IOException error) {
                                                        failed(message.id());
                                                    }

                                                    @Override
                                                    public void onResponse(Call call, Response r) {
                                                        try (r) {
                                                            if (!r.isSuccessful()) failed(message.id());
                                                        }
                                                    }
                                                })));
                            }
                        }
                    });
                } catch (Exception error) {
                    failed(message.id());
                }
            });
        } catch (RejectedExecutionException error) {
            failed(message.id());
        }
    }

    static boolean usesDefaultWebhookBody(String discordOut) {
        if (discordOut == null) return true;
        String trimmed = discordOut.trim();
        return trimmed.isEmpty() || trimmed.equals(DEFAULT_DISCORD_OUT);
    }

    static String formatCustomWebhookOut(String template, String playerName, String strippedDisplayName, String body) {
        return template
                .replace("{player}", playerName)
                .replace("{displayname}", strippedDisplayName)
                .replace("{message}", body);
    }

    static WebhookPayload defaultWebhookPayload(String playerName, String displayName,
                                                ChatSettings.Channel channel, String body) {
        String content = usesDefaultWebhookBody(channel.discordOut())
                ? body
                : formatCustomWebhookOut(channel.discordOut(), playerName, displayName, body);
        if (content.length() > 1900) content = content.substring(0, 1900);
        String username = (displayName != null && !displayName.isBlank()) ? displayName : playerName;
        if (username.length() > 80) username = username.substring(0, 80);
        return new WebhookPayload(username, null, content, content.isBlank());
    }

    static WebhookPayload finalizeWebhookPayload(
            String rawUsername,
            String defaultAvatarUrl,
            String rawContent,
            String fallbackDisplayName,
            String fallbackPlayerName,
            Map<Pattern, String> gameRegexes,
            String discordEffectiveName,
            String discordUserName,
            String discordAvatarUrl,
            boolean avatarFromDiscord,
            boolean usernameFromDiscord
    ) {
        String username = rawUsername == null ? "" : rawUsername;
        String content = rawContent == null ? "" : rawContent;
        String avatarUrl = defaultAvatarUrl;

        if (gameRegexes != null) {
            for (Map.Entry<Pattern, String> entry : gameRegexes.entrySet()) {
                username = entry.getKey().matcher(username).replaceAll(entry.getValue());
                content = entry.getKey().matcher(content).replaceAll(entry.getValue());
                if (username.isBlank() || content.isBlank()) {
                    return new WebhookPayload("", avatarUrl, "", true);
                }
            }
        }

        username = username
                .replace("%discordname%", discordEffectiveName != null ? discordEffectiveName : "")
                .replace("%discordusername%", discordUserName != null ? discordUserName : "");

        if (discordEffectiveName != null && usernameFromDiscord && !discordEffectiveName.isBlank()) {
            username = discordEffectiveName;
        }
        if (discordAvatarUrl != null && avatarFromDiscord && !discordAvatarUrl.isBlank()) {
            avatarUrl = discordAvatarUrl;
        }

        if (username.isBlank()) {
            username = (fallbackDisplayName != null && !fallbackDisplayName.isBlank())
                    ? fallbackDisplayName
                    : (fallbackPlayerName == null ? "" : fallbackPlayerName);
        }
        if (username.length() > 80) username = username.substring(0, 80);
        if (content.length() > 1900) content = content.substring(0, 1900);
        if (content.isBlank()) {
            return new WebhookPayload(username, avatarUrl, "", true);
        }
        return new WebhookPayload(username, avatarUrl, content, false);
    }

    private void link(ChatHistory.Message message, Runnable remove) {
        boolean deleteNow = false;
        synchronized (linked) {
            if (message.deleted().get()) {
                deleteNow = true;
            } else if (!closed) {
                linked.put(message.id(), remove);
                while (linked.size() > 4096) linked.remove(linked.keySet().iterator().next());
            }
        }
        if (deleteNow) remove.run();
    }

    @Override
    public void delete(String id) {
        Runnable action;
        synchronized (linked) {
            action = linked.remove(id);
        }
        if (action != null) action.run();
    }

    private void failed(String id) {
        plugin.logger().warning("Discord chat relay operation unavailable for " + id + "; no automatic resend.");
    }

    @Override
    public void close() {
        closed = true;
        backend.unsubscribe(this);
        var jda = backend.jda();
        if (jda != null) jda.removeEventListener(deletes);
        synchronized (linked) {
            linked.clear();
        }
    }
}
