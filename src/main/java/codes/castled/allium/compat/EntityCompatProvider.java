package codes.castled.allium.compat;

import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;

public final class EntityCompatProvider {

    private static final EntityCompat INSTANCE = new StandardEntityCompat();

    private EntityCompatProvider() {
    }

    public static EntityCompat current() {
        return INSTANCE;
    }

    private static final class StandardEntityCompat implements EntityCompat {

        @Override
        public void clearArrows(LivingEntity entity) {
            entity.setArrowsInBody(0);
        }

        @Override
        public void clearFreeze(LivingEntity entity) {
            entity.setFreezeTicks(0);
        }

        @Override
        public void clearPotionEffects(LivingEntity entity) {
            List<PotionEffect> active = new ArrayList<>(entity.getActivePotionEffects());
            for (PotionEffect effect : active) {
                entity.removePotionEffect(effect.getType());
            }
        }
    }
}
