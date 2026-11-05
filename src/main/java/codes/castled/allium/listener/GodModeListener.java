package codes.castled.allium.listener;

import codes.castled.allium.compat.GodModeCompatProvider;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

public final class GodModeListener implements Listener {

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (GodModeCompatProvider.current().isGodMode(player)) {
                event.setCancelled(true);
            }
        }
    }
}
