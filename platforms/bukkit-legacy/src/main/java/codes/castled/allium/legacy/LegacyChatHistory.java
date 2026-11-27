package codes.castled.allium.legacy;

import codes.castled.allium.chat.ChatClear;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Chat message history and {@code /delmsg} on legacy Bukkit, as on the modern platform: each
 * public chat and /me line is kept with what every recipient saw (up to 4096 messages, 200 per
 * viewer, for 15 minutes). Staff who may delete see a hover with the message id; clicking it puts
 * {@code /delmsg <id>} in their chat box. Deleting clears the chat of everyone who saw the message
 * ({@code chat.deletion_resend.clear_lines}) and sends them their remaining messages again.
 * Messages from other plugins are not kept, so a re-synced chat holds Allium's chat only.
 */
final class LegacyChatHistory {
    static final int GLOBAL_LIMIT = 4096, VIEWER_LIMIT = 200;
    static final long AGE_MILLIS = 15 * 60_000L;

    static final class Message {
        final String id;
        final UUID sender;
        final long time;
        final Map<UUID, String> rendered = new HashMap<>();
        boolean deleted;

        Message(String id, UUID sender, long time) {
            this.id = id;
            this.sender = sender;
            this.time = time;
        }
    }

    private final JavaPlugin plugin;
    private final LegacyConfigManager config;
    private final LegacyMessageManager messages;
    private final LinkedHashMap<String, Message> history = new LinkedHashMap<>();
    private final Map<UUID, ArrayDeque<String>> viewers = new HashMap<>();
    private final Map<UUID, ArrayDeque<Runnable>> replays = new LinkedHashMap<>();
    private long sequence;
    private BukkitTask task;

    LegacyChatHistory(JavaPlugin plugin, LegacyConfigManager config, LegacyMessageManager messages) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
    }

    static boolean canDelete(CommandSender sender) {
        return sender.hasPermission("allium.delmsg") || sender.hasPermission("allium.deletemsg") || sender.hasPermission("allium.staff");
    }

    String nextId() {
        return Long.toString(++sequence, 36);
    }

    /** Sends {@code line} as message {@code id} to each recipient and keeps it. */
    void deliver(String id, UUID sender, String line, Collection<? extends Player> recipients, long now) {
        Message message = new Message(id, sender, now);
        history.put(id, message);
        for (Player recipient : recipients) {
            message.rendered.put(recipient.getUniqueId(), line);
            ArrayDeque<String> ids = viewers.get(recipient.getUniqueId());
            if (ids == null) viewers.put(recipient.getUniqueId(), ids = new ArrayDeque<>());
            ids.addLast(id);
            while (ids.size() > VIEWER_LIMIT) ids.removeFirst();
            send(recipient, id, line);
        }
        trim(now);
    }

    private void send(Player viewer, String id, String line) {
        if (!canDelete(viewer)) {
            viewer.sendMessage(line);
            return;
        }
        String hover = LegacyCompat.color("&cClick to delete this message\n&8[&f" + id + "&8]");
        LegacyCompat.sendInteractive(viewer, line, "/delmsg " + id, false, hover);
    }

    private void trim(long now) {
        Iterator<Message> all = history.values().iterator();
        while (all.hasNext()) {
            Message message = all.next();
            if (history.size() > GLOBAL_LIMIT || now - message.time > AGE_MILLIS) all.remove();
            else break;
        }
        for (ArrayDeque<String> ids : viewers.values()) ids.removeIf(id -> !history.containsKey(id));
    }

    void forget(UUID viewer) {
        viewers.remove(viewer);
        replays.remove(viewer);
    }

    void clear() {
        history.clear();
        viewers.clear();
        replays.clear();
        if (task != null) task.cancel();
        task = null;
    }

    /** {@code /delmsg <id>} or {@code /delmsg <player> <amount>}. */
    boolean command(CommandSender sender, String[] args) {
        if (!canDelete(sender)) {
            messages.send(sender, "error.no-permission", "{prefix} &cYou don't have permission to do that.");
            return true;
        }
        if (args.length == 1) {
            Message message = history.get(args[0]);
            if (message == null || message.deleted) {
                messages.send(sender, "chat.delete.not-found", "{prefix} &cMessage not found.");
                return true;
            }
            delete(sender, java.util.Collections.singletonList(message));
            messages.send(sender, "chat.delete.success", "{prefix} &aMessage deleted.");
            return true;
        }
        if (args.length != 2) return false;
        int amount;
        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException notNumber) {
            amount = -1;
        }
        if (amount < 1 || amount > 200) {
            messages.send(sender, "chat.delete.amount", "{prefix} &cAmount must be 1..200.");
            return true;
        }
        org.bukkit.OfflinePlayer target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null) target = plugin.getServer().getOfflinePlayer(args[0]);
        if (target == null || target.getUniqueId() == null) {
            messages.send(sender, "chat.player-not-found", "{prefix} &cPlayer not found.");
            return true;
        }
        UUID id = target.getUniqueId();
        List<Message> recent = history.values().stream().filter(m -> m.sender.equals(id) && !m.deleted)
                .sorted(Comparator.comparingLong((Message m) -> m.time).reversed()).limit(amount).collect(Collectors.toList());
        delete(sender, recent);
        messages.send(sender, "chat.delete.multiple", "{prefix} &aMessages deleted.");
        return true;
    }

    private void delete(CommandSender actor, List<Message> doomed) {
        Set<UUID> affected = new LinkedHashSet<>();
        for (Message message : doomed) {
            if (message.deleted) continue;
            message.deleted = true;
            for (UUID viewer : message.rendered.keySet()) {
                ArrayDeque<String> ids = viewers.get(viewer);
                if (ids != null && ids.contains(message.id)) affected.add(viewer);
            }
        }
        if (!doomed.isEmpty()) {
            plugin.getLogger().info("Chat message(s) " + doomed.stream().map(m -> m.id).collect(Collectors.joining(", "))
                    + " deleted by " + actor.getName());
        }
        for (UUID viewerId : affected) {
            Player viewer = plugin.getServer().getPlayer(viewerId);
            if (viewer != null && viewer.isOnline()) replay(viewer);
        }
    }

    /** Clears the viewer's chat, then resends what they may still see. Sent a few lines per tick. */
    private void replay(Player viewer) {
        ArrayDeque<Runnable> steps = new ArrayDeque<>();
        int lines = ChatClear.entries(config.raw().getInt("chat.deletion_resend.clear_lines", 500));
        for (int i = 0; i < lines; i++) {
            final int line = i;
            steps.add(() -> viewer.sendMessage(ChatClear.blankLine(line)));
        }
        if (config.raw().getBoolean("chat.deletion_resend.header_enabled", false)) {
            String header = LegacyCompat.color(config.raw().getString("chat.deletion_resend.header", ""));
            if (!header.isEmpty()) steps.add(() -> viewer.sendMessage(header));
        }
        List<Message> visible = new ArrayList<>();
        ArrayDeque<String> ids = viewers.get(viewer.getUniqueId());
        if (ids != null) for (String id : ids) {
            Message message = history.get(id);
            if (message != null && !message.deleted) visible.add(message);
        }
        for (Message message : visible) {
            String line = message.rendered.get(viewer.getUniqueId());
            steps.add(() -> send(viewer, message.id, line));
        }
        replays.put(viewer.getUniqueId(), steps);
        if (task == null) task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::flush, 1L, 1L);
    }

    private void flush() {
        int budget = Math.max(1, Math.min(256, config.raw().getInt("chat.replay-packets-per-tick", 64)));
        Iterator<Map.Entry<UUID, ArrayDeque<Runnable>>> jobs = replays.entrySet().iterator();
        while (jobs.hasNext() && budget > 0) {
            Map.Entry<UUID, ArrayDeque<Runnable>> job = jobs.next();
            Player viewer = plugin.getServer().getPlayer(job.getKey());
            if (viewer == null || !viewer.isOnline()) {
                jobs.remove();
                continue;
            }
            while (!job.getValue().isEmpty() && budget > 0) {
                job.getValue().pollFirst().run();
                budget--;
            }
            if (job.getValue().isEmpty()) jobs.remove();
        }
        if (replays.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    /** What {@code viewer} has in history, for tests. */
    List<String> visibleIds(UUID viewer) {
        List<String> out = new ArrayList<>();
        ArrayDeque<String> ids = viewers.get(viewer);
        if (ids != null) for (String id : ids) if (history.containsKey(id) && !history.get(id).deleted) out.add(id);
        return out;
    }
}
