package codes.castled.allium.compat;

import codes.castled.allium.PluginStart;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

public final class GodModeCompatProvider {

    private static volatile GodModeCompat current;

    private GodModeCompatProvider() {
    }

    public static void init(PluginStart pluginInstance) {
        current = new StandardGodModeCompat(pluginInstance);
    }

    public static GodModeCompat current() {
        GodModeCompat selected = current;
        if (selected == null) {
            throw new IllegalStateException("GodModeCompatProvider has not been initialized");
        }
        return selected;
    }

    private static final class StandardGodModeCompat implements GodModeCompat {

        private final NamespacedKey godModeKey;

        StandardGodModeCompat(PluginStart plugin) {
            this.godModeKey = new NamespacedKey(plugin, "godmode");
        }

        @Override
        public boolean isGodMode(Player player) {
            Integer val = player.getPersistentDataContainer().getOrDefault(godModeKey, PersistentDataType.INTEGER, 0);
            return val != null && val == 1;
        }

        @Override
        public void setGodMode(Player player, boolean enabled) {
            player.getPersistentDataContainer().set(godModeKey, PersistentDataType.INTEGER, enabled ? 1 : 0);
            player.setInvulnerable(enabled);
        }
    }
}
