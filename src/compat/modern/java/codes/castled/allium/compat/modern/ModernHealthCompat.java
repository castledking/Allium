package codes.castled.allium.compat.modern;

import codes.castled.allium.compat.HealthCompat;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;

public final class ModernHealthCompat implements HealthCompat {

    @Override
    public double getMaxHealth(Player player) {
        try {
            return player.getAttribute(Attribute.MAX_HEALTH).getValue();
        } catch (Exception e) {
            return 20.0;
        }
    }
}
