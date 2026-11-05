package codes.castled.allium.compat.legacy;

import codes.castled.allium.compat.GodModeCompat;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class LegacyGodModeCompat implements GodModeCompat {

    private final Map<UUID, Boolean> godModePlayers = new HashMap<>();

    @Override
    public boolean isGodMode(Player player) {
        return godModePlayers.getOrDefault(player.getUniqueId(), false);
    }

    @Override
    public void setGodMode(Player player, boolean enabled) {
        godModePlayers.put(player.getUniqueId(), enabled);
        // setInvulnerable doesn't exist in 1.8.8, just track the state
        // The actual invulnerability would need to be handled via events or other means
    }
}
