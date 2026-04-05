package net.survivalfun.core.managers.chat;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.DiscordGuildMessageSentEvent;
import github.scarsz.discordsrv.api.events.GameChatMessagePostProcessEvent;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Message;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.INFO;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.WARN;

/**
 * Optional DiscordSRV bridge for mapping Allium chat message IDs to Discord message objects so
 * /delmsg can remove the mirrored Discord message as well.
 */
public final class DiscordSrvMessageBridge implements Listener {

    private static final long PENDING_WINDOW_MS = 15_000L;
    private static final long LINKED_WINDOW_MS = 60L * 60L * 1000L;
    private static final long WEBHOOK_LOOKUP_WINDOW_MS = 30_000L;
    private static final long[] WEBHOOK_LOOKUP_DELAYS_TICKS = {10L, 20L, 40L, 80L};
    private static final int WEBHOOK_LOOKUP_HISTORY_LIMIT = 10;

    private final PluginStart plugin;
    private final ConcurrentLinkedDeque<PendingChatMessage> pendingMessages = new ConcurrentLinkedDeque<>();
    private final ConcurrentMap<Long, LinkedDiscordMessage> linkedMessages = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Boolean> pendingDeleteRequests = new ConcurrentHashMap<>();

    private volatile boolean hooked;

    public DiscordSrvMessageBridge(PluginStart plugin) {
        this.plugin = plugin;
        hookIfAvailable();
    }

    public boolean isHooked() {
        return hooked;
    }

    public void retryHook() {
        if (!hooked) {
            hookIfAvailable();
        }
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        try {
            DiscordSRV.api.unsubscribe(this);
        } catch (Throwable ignored) {
        }
        pendingMessages.clear();
        linkedMessages.clear();
        pendingDeleteRequests.clear();
        hooked = false;
    }

    public void noteOutgoingPlayerChat(long alliumMessageId, Player sender, Component rawMessage, Component formattedMessage) {
        if (!hooked || sender == null) {
            return;
        }

        purgeExpired();

            String rawPlain = normalize(plain(rawMessage));
            String formattedPlain = normalize(plain(formattedMessage));

        if (rawPlain.isEmpty() && formattedPlain.isEmpty()) {
            return;
        }

        pendingMessages.addLast(new PendingChatMessage(
                alliumMessageId,
                sender.getUniqueId(),
                rawPlain,
                formattedPlain,
                System.currentTimeMillis()
        ));
        Text.sendDebugLog(INFO, "[DiscordBridge] queued message " + alliumMessageId + " raw='" + rawPlain + "' formatted='" + formattedPlain + "'");
    }

    /**
     * Queue deletion for the mirrored Discord message if one exists now or arrives moments later.
     */
    public void requestDiscordDelete(long alliumMessageId) {
        if (!hooked) {
            return;
        }

        purgeExpired();
        pendingDeleteRequests.put(alliumMessageId, Boolean.TRUE);

        LinkedDiscordMessage linked = linkedMessages.get(alliumMessageId);
        if (linked != null) {
            Text.sendDebugLog(INFO, "[DiscordBridge] deleting linked Discord message for " + alliumMessageId + " -> " + linked.discordMessageId);
            deleteLinkedMessage(linked);
        } else {
            Text.sendDebugLog(INFO, "[DiscordBridge] queued Discord delete for " + alliumMessageId + " before link was available");
        }
    }

    private void hookIfAvailable() {
        try {
            Plugin discordSrv = Bukkit.getPluginManager().getPlugin("DiscordSRV");
            if (discordSrv == null || !discordSrv.isEnabled()) {
                Text.sendDebugLog(INFO, "[DiscordBridge] DiscordSRV not ready yet");
                return;
            }

            if (!hooked) {
                DiscordSRV.api.subscribe(this);
            }
            hooked = true;
            Text.sendDebugLog(INFO, "[DiscordBridge] enabled for /delmsg mirroring");
        } catch (Throwable t) {
            hooked = false;
            Text.sendDebugLog(WARN, "[DiscordBridge] unavailable: " + t.getMessage());
        }
    }

    @Subscribe
    public void handleGameChatPostProcess(GameChatMessagePostProcessEvent event) {
        try {
            if (event.isCancelled()) {
                return;
            }

            Player player = event.getPlayer();
            if (player == null) {
                return;
            }

            String processedMessage = event.getProcessedMessage();
            if (processedMessage.isBlank()) {
                return;
            }

            String normalizedProcessed = normalize(processedMessage);
            if (normalizedProcessed.isEmpty()) {
                return;
            }

            String channelName = event.getChannel();
            if (channelName.isBlank()) {
                channelName = "global";
            }

            purgeExpired();

            PendingChatMessage match = null;
            for (Iterator<PendingChatMessage> iterator = pendingMessages.descendingIterator(); iterator.hasNext();) {
                PendingChatMessage candidate = iterator.next();
                if (!candidate.senderId.equals(player.getUniqueId())) {
                    continue;
                }
                if (candidate.hasDiscordPostProcess()) {
                    continue;
                }
                if (!contentMatches(candidate, normalizedProcessed)) {
                    continue;
                }
                match = candidate;
                break;
            }

            if (match == null) {
                Text.sendDebugLog(INFO, "[DiscordBridge] no post-process match for player=" + player.getName() + " processed='" + normalizedProcessed + "'");
                return;
            }

            match.discordProcessedMessage = normalizedProcessed;
            match.discordProcessedAt = System.currentTimeMillis();
            match.destinationChannelObject = resolveDestinationChannel(channelName);
            match.destinationChannelId = match.destinationChannelObject != null ? match.destinationChannelObject.getId() : "";
            Text.sendDebugLog(INFO, "[DiscordBridge] post-process matched " + match.alliumMessageId + " channel=" + channelName + " channelId=" + match.destinationChannelId + " processed='" + normalizedProcessed + "'");
            scheduleWebhookLookup(match);
        } catch (Throwable t) {
            Text.sendDebugLog(WARN, "DiscordSRV bridge post-process handling failed: " + t.getMessage());
        }
    }

    @Subscribe
    public void handleDiscordGuildMessageSent(DiscordGuildMessageSentEvent event) {
        try {
            Message message = event.getMessage();
            if (message == null) {
                return;
            }

            String normalizedSent = normalize(message.getContentRaw());
            if (normalizedSent.isEmpty()) {
                normalizedSent = normalize(message.getContentDisplay());
            }
            String channelId = message.getChannel().getId();

            purgeExpired();

            PendingChatMessage match = null;
            for (PendingChatMessage candidate : pendingMessages) {
                if (!candidate.hasDiscordPostProcess()) {
                    continue;
                }
                if (!candidate.destinationChannelId.isEmpty() && !candidate.destinationChannelId.equals(channelId)) {
                    continue;
                }
                if (normalizedSent.isEmpty()) {
                    match = candidate;
                    break;
                }
                if (candidate.discordProcessedMessage.equals(normalizedSent)
                        || candidate.discordProcessedMessage.contains(normalizedSent)
                        || normalizedSent.contains(candidate.discordProcessedMessage)) {
                    match = candidate;
                    break;
                }
            }

            if (match == null) {
                Text.sendDebugLog(INFO, "[DiscordBridge] sent-event had no pending match for channelId=" + channelId + " message='" + normalizedSent + "'");
                return;
            }

            String discordMessageId = message.getId();
            linkedMessages.put(match.alliumMessageId, new LinkedDiscordMessage(match.alliumMessageId, discordMessageId, message, System.currentTimeMillis()));
            pendingMessages.remove(match);
            Text.sendDebugLog(INFO, "[DiscordBridge] linked via sent-event " + match.alliumMessageId + " -> " + discordMessageId);

            if (pendingDeleteRequests.containsKey(match.alliumMessageId)) {
                deleteLinkedMessage(linkedMessages.get(match.alliumMessageId));
            }
        } catch (Throwable t) {
            Text.sendDebugLog(WARN, "DiscordSRV bridge sent-message handling failed: " + t.getMessage());
        }
    }

    private void scheduleWebhookLookup(PendingChatMessage pendingMessage) {
        if (pendingMessage == null
                || pendingMessage.destinationChannelObject == null
                || linkedMessages.containsKey(pendingMessage.alliumMessageId)
                || pendingMessage.webhookLookupScheduled) {
            return;
        }

        pendingMessage.webhookLookupScheduled = true;
        pendingMessage.webhookLookupAttempts = 0;
        scheduleWebhookLookupAttempt(pendingMessage);
    }

    private void scheduleWebhookLookupAttempt(PendingChatMessage pendingMessage) {
        if (pendingMessage == null || linkedMessages.containsKey(pendingMessage.alliumMessageId)) {
            return;
        }

        int attempt = pendingMessage.webhookLookupAttempts;
        if (attempt >= WEBHOOK_LOOKUP_DELAYS_TICKS.length) {
            Text.sendDebugLog(INFO, "[DiscordBridge] exhausted webhook lookup attempts for " + pendingMessage.alliumMessageId + " processed='" + pendingMessage.discordProcessedMessage + "'");
            return;
        }

        long delay = WEBHOOK_LOOKUP_DELAYS_TICKS[attempt];
        pendingMessage.webhookLookupAttempts++;
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> lookupWebhookMessage(pendingMessage), delay);
    }

    private void lookupWebhookMessage(PendingChatMessage pendingMessage) {
        try {
            if (pendingMessage == null
                    || pendingMessage.destinationChannelObject == null
                    || pendingMessage.discordProcessedMessage == null
                    || linkedMessages.containsKey(pendingMessage.alliumMessageId)) {
                return;
            }

            Iterable<Message> iterable = pendingMessage.destinationChannelObject.getHistory().retrievePast(WEBHOOK_LOOKUP_HISTORY_LIMIT).complete();

            Message matchedMessage = null;
            long matchedTimestamp = Long.MIN_VALUE;
            int seen = 0;
            for (Message candidateMessage : iterable) {
                seen++;
                if (!candidateMessage.isWebhookMessage()) {
                    continue;
                }

                String normalizedCandidate = normalize(candidateMessage.getContentRaw());
                if (normalizedCandidate.isEmpty()) {
                    normalizedCandidate = normalize(candidateMessage.getContentDisplay());
                }
                if (normalizedCandidate.isEmpty()) {
                    continue;
                }
                if (!(pendingMessage.discordProcessedMessage.equals(normalizedCandidate)
                        || pendingMessage.discordProcessedMessage.contains(normalizedCandidate)
                        || normalizedCandidate.contains(pendingMessage.discordProcessedMessage))) {
                    continue;
                }

                long candidateTimestamp = extractMessageTimestamp(candidateMessage);
                if (candidateTimestamp > 0L && Math.abs(candidateTimestamp - pendingMessage.createdAt) > WEBHOOK_LOOKUP_WINDOW_MS) {
                    continue;
                }

                if (candidateTimestamp >= matchedTimestamp) {
                    matchedMessage = candidateMessage;
                    matchedTimestamp = candidateTimestamp;
                }
            }

            if (matchedMessage == null) {
                Text.sendDebugLog(INFO, "[DiscordBridge] webhook lookup attempt " + pendingMessage.webhookLookupAttempts + " found no match for " + pendingMessage.alliumMessageId + " channelId=" + pendingMessage.destinationChannelId + " processed='" + pendingMessage.discordProcessedMessage + "' seen=" + seen);
                scheduleWebhookLookupAttempt(pendingMessage);
                return;
            }

            String discordMessageId = matchedMessage.getId();
            if (discordMessageId.isEmpty()) {
                Text.sendDebugLog(INFO, "[DiscordBridge] webhook lookup matched message without id for " + pendingMessage.alliumMessageId);
                return;
            }

            linkedMessages.put(pendingMessage.alliumMessageId, new LinkedDiscordMessage(
                    pendingMessage.alliumMessageId,
                    discordMessageId,
                    matchedMessage,
                    System.currentTimeMillis()
            ));
            pendingMessages.remove(pendingMessage);
            Text.sendDebugLog(INFO, "[DiscordBridge] linked via webhook lookup " + pendingMessage.alliumMessageId + " -> " + discordMessageId);

            if (pendingDeleteRequests.containsKey(pendingMessage.alliumMessageId)) {
                deleteLinkedMessage(linkedMessages.get(pendingMessage.alliumMessageId));
            }
        } catch (Throwable t) {
            Text.sendDebugLog(WARN, "DiscordSRV webhook lookup failed: " + t.getMessage());
        }
    }

    private TextChannel resolveDestinationChannel(String channelName) {
        try {
            Plugin discordSrv = Bukkit.getPluginManager().getPlugin("DiscordSRV");
            if (!(discordSrv instanceof DiscordSRV discordSRV) || !discordSrv.isEnabled()) {
                return null;
            }
            return discordSRV.getDestinationTextChannelForGameChannelName(channelName);
        } catch (Throwable t) {
            return null;
        }
    }

    private void deleteLinkedMessage(LinkedDiscordMessage linkedMessage) {
        if (linkedMessage == null || linkedMessage.deleted) {
            return;
        }

        try {
            linkedMessage.messageObject.delete().queue();

            linkedMessage.deleted = true;
            pendingDeleteRequests.remove(linkedMessage.alliumMessageId);
            linkedMessages.remove(linkedMessage.alliumMessageId);
            Text.sendDebugLog(INFO, "Deleted mirrored Discord message for /delmsg " + linkedMessage.alliumMessageId + " (" + linkedMessage.discordMessageId + ")");
        } catch (Throwable t) {
            Text.sendDebugLog(WARN, "Failed to delete mirrored Discord message for /delmsg " + linkedMessage.alliumMessageId + ": " + t.getMessage());
        }
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();

        for (PendingChatMessage pending : pendingMessages) {
            if (now - pending.createdAt > PENDING_WINDOW_MS) {
                pendingMessages.remove(pending);
                pendingDeleteRequests.remove(pending.alliumMessageId);
            }
        }

        for (Map.Entry<Long, LinkedDiscordMessage> entry : linkedMessages.entrySet()) {
            if (now - entry.getValue().linkedAt > LINKED_WINDOW_MS) {
                linkedMessages.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private boolean contentMatches(PendingChatMessage candidate, String normalizedProcessed) {
        if (!candidate.rawPlain.isEmpty() && (normalizedProcessed.contains(candidate.rawPlain) || candidate.rawPlain.contains(normalizedProcessed))) {
            return true;
        }
        return !candidate.formattedPlain.isEmpty()
                && (normalizedProcessed.contains(candidate.formattedPlain) || candidate.formattedPlain.contains(normalizedProcessed));
    }

    private static String plain(Component component) {
        if (component == null) {
            return "";
        }
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        String normalized = input
                .replaceAll("§[0-9A-FK-ORa-fk-or]", "")
                .replaceAll("[*_`~>|]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
        return normalized;
    }

    private static long extractMessageTimestamp(Message message) {
        return message.getTimeCreated().toInstant().toEpochMilli();
    }

    private static final class PendingChatMessage {
        private final long alliumMessageId;
        private final UUID senderId;
        private final String rawPlain;
        private final String formattedPlain;
        private final long createdAt;

        private volatile String discordProcessedMessage;
        private volatile long discordProcessedAt;
        private volatile String destinationChannelId = "";
        private volatile TextChannel destinationChannelObject;
        private volatile boolean webhookLookupScheduled;
        private volatile int webhookLookupAttempts;

        private PendingChatMessage(long alliumMessageId, UUID senderId, String rawPlain, String formattedPlain, long createdAt) {
            this.alliumMessageId = alliumMessageId;
            this.senderId = senderId;
            this.rawPlain = rawPlain;
            this.formattedPlain = formattedPlain;
            this.createdAt = createdAt;
        }

        private boolean hasDiscordPostProcess() {
            return discordProcessedAt > 0L;
        }
    }

    private static final class LinkedDiscordMessage {
        private final long alliumMessageId;
        private final String discordMessageId;
        private final Message messageObject;
        private final long linkedAt;

        private volatile boolean deleted;

        private LinkedDiscordMessage(long alliumMessageId, String discordMessageId, Message messageObject, long linkedAt) {
            this.alliumMessageId = alliumMessageId;
            this.discordMessageId = discordMessageId;
            this.messageObject = messageObject;
            this.linkedAt = linkedAt;
        }
    }
}
