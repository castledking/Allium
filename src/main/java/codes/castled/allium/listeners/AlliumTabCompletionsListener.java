package codes.castled.allium.listeners;

import codes.castled.allium.events.AlliumTabCompletionsEvent;
import codes.castled.allium.managers.core.PartyManager;
import codes.castled.allium.managers.core.VanishManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;

/**
 * Listens for {@link AlliumTabCompletionsEvent} and populates completions
 * with all online non-vanished players when PartyManager is enabled.
 */
public class AlliumTabCompletionsListener implements Listener {

    private final PartyManager partyManager;
    private final VanishManager vanishManager;

    public AlliumTabCompletionsListener(PartyManager partyManager, VanishManager vanishManager) {
        this.partyManager = partyManager;
        this.vanishManager = vanishManager;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onTabCompletions(AlliumTabCompletionsEvent event) {
        if (!partyManager.isPartyLocatorBarEnabled()) {
            return;
        }

        Player viewer = event.getViewer();
        List<String> names = new ArrayList<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.equals(viewer)) continue;
            if (vanishManager.isVanished(player)) continue;
            names.add(player.getName());
        }

        event.setCompletions(names);
    }
}
