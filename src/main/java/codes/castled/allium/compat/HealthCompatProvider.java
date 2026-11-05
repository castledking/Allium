package codes.castled.allium.compat;

import org.bukkit.entity.Player;

public final class HealthCompatProvider {

    private static final String MODERN_CLASS = "codes.castled.allium.compat.modern.ModernHealthCompat";
    private static final String LEGACY_CLASS = "codes.castled.allium.compat.legacy.LegacyHealthCompat";

    private static volatile HealthCompat current;

    private HealthCompatProvider() {
    }

    public static HealthCompat current() {
        HealthCompat selected = current;
        if (selected == null) {
            selected = select(hasAttribute());
            current = selected;
        }
        return selected;
    }

    static HealthCompat select(boolean supportsAttribute) {
        HealthCompat packaged = instantiate(supportsAttribute ? MODERN_CLASS : LEGACY_CLASS);
        if (packaged != null) {
            return packaged;
        }

        return supportsAttribute ? new DirectModernHealthCompat() : new DirectLegacyHealthCompat();
    }

    private static boolean hasAttribute() {
        try {
            Class.forName("org.bukkit.attribute.Attribute");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static HealthCompat instantiate(String className) {
        try {
            Class<?> type = Class.forName(className);
            Object instance = type.getDeclaredConstructor().newInstance();
            return instance instanceof HealthCompat ? (HealthCompat) instance : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static final class DirectModernHealthCompat implements HealthCompat {

        @Override
        public double getMaxHealth(Player player) {
            try {
                Class<?> attributeClass = Class.forName("org.bukkit.attribute.Attribute");
                Object maxHealth = attributeClass.getField("MAX_HEALTH").get(null);
                Object attr = player.getClass().getMethod("getAttribute", attributeClass).invoke(player, maxHealth);
                return (double) attr.getClass().getMethod("getValue").invoke(attr);
            } catch (ReflectiveOperationException | LinkageError e) {
                return 20.0;
            }
        }
    }

    private static final class DirectLegacyHealthCompat implements HealthCompat {

        @Override
        public double getMaxHealth(Player player) {
            return 20.0;
        }
    }
}
