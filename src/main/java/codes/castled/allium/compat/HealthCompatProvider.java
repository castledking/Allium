package codes.castled.allium.compat;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

public final class HealthCompatProvider {

    private static final HealthCompat INSTANCE = new StandardHealthCompat();

    private HealthCompatProvider() {
    }

    public static HealthCompat current() {
        return INSTANCE;
    }

    private static final class StandardHealthCompat implements HealthCompat {

        private static final Attribute MAX_HEALTH_ATTRIBUTE = resolveMaxHealthAttribute();

        private static Attribute resolveMaxHealthAttribute() {
            try {
                Attribute byKey = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
                if (byKey != null) {
                    return byKey;
                }
            } catch (Throwable ignored) {
            }
            try {
                Attribute byLegacyKey = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.max_health"));
                if (byLegacyKey != null) {
                    return byLegacyKey;
                }
            } catch (Throwable ignored) {
            }
            try {
                return Attribute.valueOf("GENERIC_MAX_HEALTH");
            } catch (IllegalArgumentException ignored) {
                return Attribute.valueOf("MAX_HEALTH");
            }
        }

        @Override
        public double getMaxHealth(Player player) {
            if (MAX_HEALTH_ATTRIBUTE != null) {
                AttributeInstance instance = player.getAttribute(MAX_HEALTH_ATTRIBUTE);
                if (instance != null) {
                    return instance.getValue();
                }
            }
            return 20.0;
        }
    }
}
