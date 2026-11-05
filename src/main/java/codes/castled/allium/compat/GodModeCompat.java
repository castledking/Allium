package codes.castled.allium.compat;

import org.bukkit.entity.Player;

public interface GodModeCompat {

    boolean isGodMode(Player player);

    void setGodMode(Player player, boolean enabled);
}
