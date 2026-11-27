package codes.castled.allium.legacy;

import codes.castled.allium.chat.ChatClear;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

final class LegacyChatClear {
    private final JavaPlugin plugin;
    private final LegacyConfigManager config;
    private final LegacyMessageManager messages;
    private final BiPredicate<CommandSender, Player> canSee;
    private final Map<UUID, PendingClear> pending = new LinkedHashMap<>();
    private BukkitTask task;

    LegacyChatClear(JavaPlugin plugin, LegacyConfigManager config, LegacyMessageManager messages,
                    BiPredicate<CommandSender, Player> canSee) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.canSee = canSee;
    }

    boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.clearchat")) return noPermission(sender);
        if (args.length > 1) return false;
        int entries = ChatClear.entries(config.raw().getInt("chat.deletion_resend.clear_lines", 500));
        if (entries == 0) {
            messages.send(sender, "chat.clear.disabled", "{prefix} &cChat clearing is disabled.");
            return true;
        }

        boolean global = args.length == 0;
        Collection<? extends Player> audience;
        if (global) {
            audience = plugin.getServer().getOnlinePlayers();
        } else {
            boolean self = sender instanceof Player && sender.getName().equalsIgnoreCase(args[0]);
            if (!self && !sender.hasPermission("allium.clearchat.others")) return noPermission(sender);
            Player target = self ? (Player) sender : plugin.getServer().getPlayerExact(args[0]);
            if (target == null || !canSee.test(sender, target)) {
                messages.send(sender, "chat.player-not-found", "{prefix} &cPlayer not found.");
                return true;
            }
            audience = Collections.singletonList(target);
        }
        boolean acknowledgeAfterClear = false;
        for (Player viewer : audience) {
            if (global && viewer.hasPermission("allium.clearchat.exempt")) continue;
            boolean self = viewer.equals(sender);
            PendingClear previous = pending.get(viewer.getUniqueId());
            boolean acknowledge = self || (previous != null && previous.acknowledge);
            pending.put(viewer.getUniqueId(), new PendingClear(viewer, entries, acknowledge));
            acknowledgeAfterClear |= self;
        }
        if (task == null && !pending.isEmpty()) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::flush, 1L, 1L);
        }
        plugin.getLogger().info("Chat clear by " + sender.getName() + " for "
                + (global ? "all online players" : args[0]));
        if (!acknowledgeAfterClear) success(sender);
        return true;
    }

    List<String> complete(CommandSender sender, String prefix) {
        if (!sender.hasPermission("allium.clearchat")) return Collections.emptyList();
        boolean others = sender.hasPermission("allium.clearchat.others");
        return plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> (others || player.equals(sender)) && canSee.test(sender, player))
                .map(Player::getName)
                .filter(name -> name.regionMatches(true, 0, prefix, 0, prefix.length()))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    private void flush() {
        int budget = Math.max(1, Math.min(256, config.raw().getInt("chat.replay-packets-per-tick", 64)));
        Iterator<PendingClear> clears = pending.values().iterator();
        while (clears.hasNext() && budget > 0) {
            PendingClear clear = clears.next();
            if (!clear.player.isOnline()) {
                clears.remove();
                continue;
            }
            while (clear.sent < clear.entries && budget > 0) {
                clear.player.sendMessage(ChatClear.blankLine(clear.sent++));
                budget--;
            }
            if (clear.sent == clear.entries) {
                clears.remove();
                if (clear.acknowledge) success(clear.player);
            }
        }
        if (pending.isEmpty()) stop();
    }

    void forget(UUID player) {
        pending.remove(player);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        pending.clear();
    }

    private void success(CommandSender sender) {
        messages.send(sender, "chat.clear.success", "{prefix} &aChat cleared.");
    }

    private boolean noPermission(CommandSender sender) {
        messages.send(sender, "no-permission", "{prefix} &cYou don't have permission to use &f/{cmd}&c.",
                "{cmd}", "clearchat");
        return true;
    }

    private static final class PendingClear {
        private final Player player;
        private final int entries;
        private final boolean acknowledge;
        private int sent;

        private PendingClear(Player player, int entries, boolean acknowledge) {
            this.player = player;
            this.entries = entries;
            this.acknowledge = acknowledge;
        }
    }
}
