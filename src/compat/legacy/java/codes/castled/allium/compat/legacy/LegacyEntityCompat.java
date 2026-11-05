package codes.castled.allium.compat.legacy;

import codes.castled.allium.compat.EntityCompat;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;

import java.util.Collection;

public final class LegacyEntityCompat implements EntityCompat {

    @Override
    public void clearArrows(LivingEntity entity) {
        // setArrowsInBody doesn't exist in 1.8, no-op
    }

    @Override
    public void clearFreeze(LivingEntity entity) {
        // setFreezeTicks doesn't exist in 1.8, no-op
    }

    @Override
    public void clearPotionEffects(LivingEntity entity) {
        // In 1.8, manually remove all potion effects
        Collection<PotionEffect> effects = entity.getActivePotionEffects();
        for (PotionEffect effect : effects) {
            entity.removePotionEffect(effect.getType());
        }
    }
}
