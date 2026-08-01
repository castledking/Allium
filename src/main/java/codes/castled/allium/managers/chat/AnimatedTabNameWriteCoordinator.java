package codes.castled.allium.managers.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chooses exactly one owner for each animated tab-name frame.
 *
 * <p>When TAB accepts a frame, sending the same frame through Bukkit would race
 * TAB's anti-override cache. The Bukkit callback is therefore a fallback, not a
 * second broadcast path.</p>
 */
final class AnimatedTabNameWriteCoordinator {

    @FunctionalInterface
    interface TabNameWriter {
        boolean setName(UUID playerId, String name);
    }

    private final TabNameWriter tabWriter;
    private final Set<UUID> tabOwnedPlayers = ConcurrentHashMap.newKeySet();

    AnimatedTabNameWriteCoordinator(TabNameWriter tabWriter) {
        this.tabWriter = tabWriter;
    }

    boolean write(UUID playerId, String name, Runnable bukkitFallback) {
        if (tabWriter.setName(playerId, name)) {
            tabOwnedPlayers.add(playerId);
            return true;
        }

        tabOwnedPlayers.remove(playerId);
        bukkitFallback.run();
        return false;
    }

    void releaseInactive(Collection<UUID> activePlayers) {
        for (UUID playerId : new ArrayList<>(tabOwnedPlayers)) {
            if (activePlayers.contains(playerId)) {
                continue;
            }
            tabWriter.setName(playerId, null);
            tabOwnedPlayers.remove(playerId);
        }
    }

    void releaseAll() {
        releaseInactive(Set.of());
    }

    boolean isTabOwned(UUID playerId) {
        return tabOwnedPlayers.contains(playerId);
    }
}
