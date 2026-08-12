package codes.castled.allium.items.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

class MobPickupSuppressionTest {
    private static final NamespacedKey KEY =
            new NamespacedKey("allium", "mob_disarmer_pickup_blocked_until");

    @Test
    void cancelsPickupsUntilTheTwoMinuteDeadline() {
        AtomicLong now = new AtomicLong(1_000L);
        PersistentMob mob = persistentMob();
        MobPickupSuppression suppression = new MobPickupSuppression(KEY, now::get);
        suppression.block(mob.entity());

        now.set(1_000L + MobPickupSuppression.DURATION_MS - 1L);
        EntityPickupItemEvent blocked = pickup(mob.entity());
        assertTrue(suppression.cancelIfBlocked(blocked));
        assertTrue(blocked.isCancelled());

        now.incrementAndGet();
        EntityPickupItemEvent expired = pickup(mob.entity());
        assertFalse(suppression.cancelIfBlocked(expired));
        assertFalse(expired.isCancelled());
        assertFalse(mob.values().containsKey(KEY));
    }

    @Test
    void persistedDeadlineSurvivesAPluginRestart() {
        AtomicLong now = new AtomicLong(5_000L);
        PersistentMob mob = persistentMob();
        new MobPickupSuppression(KEY, now::get).block(mob.entity());

        MobPickupSuppression afterRestart = new MobPickupSuppression(KEY, now::get);
        EntityPickupItemEvent pickup = pickup(mob.entity());
        assertTrue(afterRestart.cancelIfBlocked(pickup));
        assertTrue(pickup.isCancelled());
    }

    private EntityPickupItemEvent pickup(final LivingEntity entity) {
        return new EntityPickupItemEvent(entity, proxy(Item.class, new HashMap<>()), 0);
    }

    private PersistentMob persistentMob() {
        Map<Object, Object> values = new HashMap<>();
        PersistentDataContainer pdc = proxy(PersistentDataContainer.class, values);
        Map<Object, Object> entityValues = new HashMap<>();
        entityValues.put("pdc", pdc);
        LivingEntity entity = proxy(LivingEntity.class, entityValues);
        return new PersistentMob(entity, values);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(final Class<T> type, final Map<Object, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getName().equals("getPersistentDataContainer")) return values.get("pdc");
                    if (method.getName().equals("set")) {
                        values.put(args[0], args[2]);
                        return null;
                    }
                    if (method.getName().equals("get")) return values.get(args[0]);
                    if (method.getName().equals("remove")) {
                        values.remove(args[0]);
                        return null;
                    }
                    if (method.getName().equals("isCancelled")) return values.getOrDefault("cancelled", false);
                    if (method.getName().equals("setCancelled")) {
                        values.put("cancelled", args[0]);
                        return null;
                    }
                    Class<?> result = method.getReturnType();
                    if (!result.isPrimitive()) return null;
                    if (result == boolean.class) return false;
                    if (result == byte.class) return (byte) 0;
                    if (result == short.class) return (short) 0;
                    if (result == int.class) return 0;
                    if (result == long.class) return 0L;
                    if (result == float.class) return 0.0F;
                    if (result == double.class) return 0.0D;
                    if (result == char.class) return '\0';
                    return null;
                });
    }

    private record PersistentMob(LivingEntity entity, Map<Object, Object> values) {}
}
