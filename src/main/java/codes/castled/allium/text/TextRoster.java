package codes.castled.allium.text;

import codes.castled.allium.PluginStart;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.scheduler.BukkitServerThread;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** A Folia roster collected once per text delivery, with target data read on each owner's region. */
final class TextRoster {
    record Entry(Player player, UUID uuid, String name, String displayName, String group, boolean vanished) {}

    private final PluginStart plugin;
    private CompletableFuture<List<Entry>> snapshot;

    TextRoster(PluginStart plugin) {
        this.plugin = plugin;
    }

    synchronized CompletableFuture<List<Entry>> collect() {
        if (snapshot != null) return snapshot;

        var players = List.copyOf(plugin.getServer().getOnlinePlayers());
        if (players.isEmpty()) {
            snapshot = CompletableFuture.completedFuture(List.of());
            return snapshot;
        }

        var result = new CompletableFuture<List<Entry>>();
        snapshot = result;
        var entries = new ConcurrentLinkedQueue<Entry>();
        var pending = new AtomicInteger(players.size());
        Runnable complete = () -> {
            if (pending.decrementAndGet() == 0) {
                result.complete(entries.stream().sorted(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER)).toList());
            }
        };
        var scheduler = plugin.getScheduler();
        for (Player player : players) {
            scheduler.runOn(BukkitServerThread.entity(plugin, player), () -> {
                try {
                    if (player.isOnline()) {
                        entries.add(new Entry(player, player.getUniqueId(), player.getName(),
                                TextFormatter.stripFormatting(player.getDisplayName()), PlayerMeta.primaryGroup(plugin, player),
                                PlayerNameVisibility.isVanished(player)));
                    }
                } finally {
                    complete.run();
                }
            }, complete, 0);
        }
        return result;
    }

    /** On the viewer's owner, consult the viewer's current visibility and ignore ended connections. */
    List<Entry> visibleTo(CommandSender sender, List<Entry> entries) {
        Player viewer = sender instanceof Player player ? player : null;
        boolean seeVanished = viewer != null && viewer.hasPermission("allium.vanish.see");
        var server = plugin.getServer();
        return entries.stream()
                .filter(entry -> server.getPlayer(entry.uuid()) == entry.player())
                .filter(entry -> viewer == null || viewer == entry.player()
                        || (!entry.vanished() || seeVanished) && viewer.canSee(entry.player()))
                .toList();
    }
}
