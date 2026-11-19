package codes.castled.allium.command.essentials;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Each player's {@code /lore copy} lines, kept in memory until {@code /lore paste} uses them or the
 * player leaves. Server thread only.
 */
public final class LoreClipboard implements Listener {

    private final Map<UUID, List<String>> copied = new HashMap<>();

    public void put(UUID player, List<String> lore) {
        copied.put(player, List.copyOf(lore));
    }

    public Optional<List<String>> get(UUID player) {
        return Optional.ofNullable(copied.get(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        copied.remove(event.getPlayer().getUniqueId());
    }
}
