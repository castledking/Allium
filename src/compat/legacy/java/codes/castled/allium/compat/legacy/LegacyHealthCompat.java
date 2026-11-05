package codes.castled.allium.compat.legacy;

import codes.castled.allium.compat.HealthCompat;
import org.bukkit.entity.Player;

public final class LegacyHealthCompat implements HealthCompat {

    @Override
    public double getMaxHealth(Player player) {
        return 20.0;
    }
}
