package codes.castled.allium.compat;

import org.bukkit.entity.LivingEntity;

public final class EntityCompatProvider {

    private static final String MODERN_CLASS = "codes.castled.allium.compat.modern.ModernEntityCompat";
    private static final String LEGACY_CLASS = "codes.castled.allium.compat.legacy.LegacyEntityCompat";

    private static volatile EntityCompat current;

    private EntityCompatProvider() {
    }

    public static EntityCompat current() {
        EntityCompat selected = current;
        if (selected == null) {
            selected = select(hasSetArrowsInBody());
            current = selected;
        }
        return selected;
    }

    static EntityCompat select(boolean supportsModernFeatures) {
        EntityCompat packaged = instantiate(supportsModernFeatures ? MODERN_CLASS : LEGACY_CLASS);
        if (packaged != null) {
            return packaged;
        }

        return supportsModernFeatures ? new DirectModernEntityCompat() : new DirectLegacyEntityCompat();
    }

    private static boolean hasSetArrowsInBody() {
        try {
            LivingEntity.class.getMethod("setArrowsInBody", int.class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static EntityCompat instantiate(String className) {
        try {
            Class<?> type = Class.forName(className);
            Object instance = type.getDeclaredConstructor().newInstance();
            return instance instanceof EntityCompat ? (EntityCompat) instance : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static final class DirectModernEntityCompat implements EntityCompat {

        @Override
        public void clearArrows(LivingEntity entity) {
            try {
                entity.getClass().getMethod("setArrowsInBody", int.class).invoke(entity, 0);
            } catch (ReflectiveOperationException | LinkageError e) {
                // Fallback
            }
        }

        @Override
        public void clearFreeze(LivingEntity entity) {
            try {
                entity.getClass().getMethod("setFreezeTicks", int.class).invoke(entity, 0);
            } catch (ReflectiveOperationException | LinkageError e) {
                // Fallback
            }
        }

        @Override
        public void clearPotionEffects(LivingEntity entity) {
            try {
                entity.getClass().getMethod("clearActivePotionEffects").invoke(entity);
            } catch (ReflectiveOperationException | LinkageError e) {
                // Fallback to manual removal
                try {
                    java.util.Collection<?> effects = (java.util.Collection<?>) entity.getClass().getMethod("getActivePotionEffects").invoke(entity);
                    for (Object effect : effects) {
                        entity.getClass().getMethod("removePotionEffect", Class.forName("org.bukkit.potion.PotionEffectType")).invoke(entity, effect.getClass().getMethod("getType").invoke(effect));
                    }
                } catch (ReflectiveOperationException | LinkageError ex) {
                    // Double fallback
                }
            }
        }
    }

    private static final class DirectLegacyEntityCompat implements EntityCompat {

        @Override
        public void clearArrows(LivingEntity entity) {
            // No-op for 1.8
        }

        @Override
        public void clearFreeze(LivingEntity entity) {
            // No-op for 1.8
        }

        @Override
        public void clearPotionEffects(LivingEntity entity) {
            // Manual removal for 1.8
            try {
                java.util.Collection<?> effects = (java.util.Collection<?>) entity.getClass().getMethod("getActivePotionEffects").invoke(entity);
                for (Object effect : effects) {
                    entity.getClass().getMethod("removePotionEffect", Class.forName("org.bukkit.potion.PotionEffectType")).invoke(entity, effect.getClass().getMethod("getType").invoke(effect));
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                // Fallback
            }
        }
    }
}
