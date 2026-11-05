package codes.castled.allium.compat.modern;

import codes.castled.allium.PluginStart;
import codes.castled.allium.compat.GodModeCompat;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

public final class ModernGodModeCompat implements GodModeCompat {

    private final PluginStart plugin;
    private final NamespacedKey godModeKey;

    public ModernGodModeCompat(PluginStart plugin) {
        this.plugin = plugin;
        this.godModeKey = new NamespacedKey(plugin, "godmode");
    }

    @Override
    public boolean isGodMode(Player player) {
        return player.getPersistentDataContainer().getOrDefault(godModeKey, PersistentDataType.INTEGER, 0).equals(1);
    }

    @Override
    public void setGodMode(Player player, boolean enabled) {
        player.getPersistentDataContainer().set(godModeKey, PersistentDataType.INTEGER, enabled ? 1 : 0);
        player.setInvulnerable(enabled);
    }
}
