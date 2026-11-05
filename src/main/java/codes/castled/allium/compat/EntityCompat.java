package codes.castled.allium.compat;

import org.bukkit.entity.LivingEntity;

public interface EntityCompat {

    void clearArrows(LivingEntity entity);

    void clearFreeze(LivingEntity entity);

    void clearPotionEffects(LivingEntity entity);
}
