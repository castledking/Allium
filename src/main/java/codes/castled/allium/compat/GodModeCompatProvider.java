package codes.castled.allium.compat;

import codes.castled.allium.PluginStart;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class GodModeCompatProvider {

    private static final String MODERN_CLASS = "codes.castled.allium.compat.modern.ModernGodModeCompat";
    private static final String LEGACY_CLASS = "codes.castled.allium.compat.legacy.LegacyGodModeCompat";

    private static volatile GodModeCompat current;
    private static PluginStart plugin;

    private GodModeCompatProvider() {
    }

    public static void init(PluginStart pluginInstance) {
        plugin = pluginInstance;
    }

    public static GodModeCompat current() {
        GodModeCompat selected = current;
        if (selected == null) {
            selected = select(hasPersistentDataContainer());
            current = selected;
        }
        return selected;
    }

    static GodModeCompat select(boolean supportsPersistentData) {
        GodModeCompat packaged = instantiate(supportsPersistentData ? MODERN_CLASS : LEGACY_CLASS);
        if (packaged != null) {
            return packaged;
        }

        return supportsPersistentData ? new DirectModernGodModeCompat() : new DirectLegacyGodModeCompat();
    }

    private static boolean hasPersistentDataContainer() {
        try {
            Class.forName("org.bukkit.persistence.PersistentDataContainer");
            Class.forName("org.bukkit.NamespacedKey");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static GodModeCompat instantiate(String className) {
        try {
            Class<?> type = Class.forName(className);
            Object instance;
            if (className.contains("Modern")) {
                instance = type.getDeclaredConstructor(PluginStart.class).newInstance(plugin);
            } else {
                instance = type.getDeclaredConstructor().newInstance();
            }
            return instance instanceof GodModeCompat ? (GodModeCompat) instance : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static final class DirectModernGodModeCompat implements GodModeCompat {

        @Override
        public boolean isGodMode(Player player) {
            try {
                Object key = Class.forName("org.bukkit.NamespacedKey").getDeclaredConstructor(PluginStart.class, String.class).newInstance(plugin, "godmode");
                Object pdc = player.getClass().getMethod("getPersistentDataContainer").invoke(player);
                Object enumType = Class.forName("org.bukkit.persistence.PersistentDataType").getField("INTEGER").get(null);
                Object value = pdc.getClass().getMethod("getOrDefault", Class.forName("org.bukkit.NamespacedKey"), enumType.getClass(), Object.class).invoke(pdc, key, enumType, 0);
                return value.equals(1);
            } catch (ReflectiveOperationException | LinkageError e) {
                return false;
            }
        }

        @Override
        public void setGodMode(Player player, boolean enabled) {
            try {
                Object key = Class.forName("org.bukkit.NamespacedKey").getDeclaredConstructor(PluginStart.class, String.class).newInstance(plugin, "godmode");
                Object pdc = player.getClass().getMethod("getPersistentDataContainer").invoke(player);
                Object enumType = Class.forName("org.bukkit.persistence.PersistentDataType").getField("INTEGER").get(null);
                pdc.getClass().getMethod("set", Class.forName("org.bukkit.NamespacedKey"), enumType.getClass(), Object.class).invoke(pdc, key, enumType, enabled ? 1 : 0);
                player.setInvulnerable(enabled);
            } catch (ReflectiveOperationException | LinkageError e) {
                // Fallback
            }
        }
    }

    private static final class DirectLegacyGodModeCompat implements GodModeCompat {

        private final Map<UUID, Boolean> godModePlayers = new HashMap<>();

        @Override
        public boolean isGodMode(Player player) {
            return godModePlayers.getOrDefault(player.getUniqueId(), false);
        }

        @Override
        public void setGodMode(Player player, boolean enabled) {
            godModePlayers.put(player.getUniqueId(), enabled);
            player.setInvulnerable(enabled);
        }
    }
}
