package codes.castled.allium.protection;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

/**
 * A protection plugin integration. Implementations run on the server thread and must not
 * modify the world, the player or the target.
 */
public interface ProtectionHook {

    String name();

    /**
     * @param cause the event that triggered the action, passed to plugins that accept one
     */
    ProtectionDecision checkEntityInteraction(Player player, Entity target, @Nullable Event cause);
}
