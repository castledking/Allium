package codes.castled.allium.compat.modern;

import codes.castled.allium.compat.EntityCompat;
import org.bukkit.entity.LivingEntity;

public final class ModernEntityCompat implements EntityCompat {

    @Override
    public void clearArrows(LivingEntity entity) {
        try {
            entity.setArrowsInBody(0);
        } catch (NoSuchMethodError e) {
            // Method not available
        }
    }

    @Override
    public void clearFreeze(LivingEntity entity) {
        try {
            entity.setFreezeTicks(0);
        } catch (NoSuchMethodError e) {
            // Method not available
        }
    }

    @Override
    public void clearPotionEffects(LivingEntity entity) {
        try {
            entity.clearActivePotionEffects();
        } catch (NoSuchMethodError e) {
            // Fallback to manual removal
            for (org.bukkit.potion.PotionEffect effect : entity.getActivePotionEffects()) {
                entity.removePotionEffect(effect.getType());
            }
        }
    }
}
