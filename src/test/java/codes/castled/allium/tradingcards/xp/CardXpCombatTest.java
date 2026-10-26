package codes.castled.allium.tradingcards.xp;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDeathEvent;
import org.junit.jupiter.api.Test;

class CardXpCombatTest {
    private final Player player = proxy(Player.class, "getUniqueId", UUID.randomUUID());
    private final RecordingXp xp = new RecordingXp();
    private final CardXpListener listener = new CardXpListener(null, xp);

    @Test void ordinaryPlayerKillAwardsCardXp() {
        listener.onEntityDeath(death(player, null, null));
        assertEquals(1, xp.awards);
    }

    @Test void damageSourceCreditsPlayerEvenWhenLegacyKillerIsMissing() {
        listener.onEntityDeath(death(null, player, null));
        assertEquals(1, xp.awards);
    }

    @Test void projectileShooterReceivesTheKill() {
        Projectile arrow = proxy(Projectile.class, "getShooter", player);
        listener.onEntityDeath(death(null, null, arrow));
        assertEquals(1, xp.awards);
    }

    @Test void environmentalDeathAndCancelledDeathPayNothing() {
        listener.onEntityDeath(death(null, null, null));
        EntityDeathEvent cancelled = death(player, player, null);
        cancelled.setCancelled(true);
        listener.onEntityDeath(cancelled);
        assertEquals(0, xp.awards);
    }

    private EntityDeathEvent death(Player killer, org.bukkit.entity.Entity causing, org.bukkit.entity.Entity direct) {
        LivingEntity mob = (LivingEntity) Proxy.newProxyInstance(LivingEntity.class.getClassLoader(),
            new Class<?>[]{LivingEntity.class}, (p, m, a) -> switch (m.getName()) {
                case "getKiller" -> killer;
                case "getType" -> EntityType.ZOMBIE;
                case "getHealth", "getMaxHealth" -> 20.0;
                default -> null;
            });
        DamageSource damage = (DamageSource) Proxy.newProxyInstance(DamageSource.class.getClassLoader(),
            new Class<?>[]{DamageSource.class}, (p, m, a) -> switch (m.getName()) {
                case "getCausingEntity" -> causing;
                case "getDirectEntity" -> direct;
                default -> null;
            });
        return new EntityDeathEvent(mob, damage, new java.util.ArrayList<>());
    }

    private static <T> T proxy(Class<T> type, String method, Object value) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (p, m, a) -> m.getName().equals(method) ? value : null));
    }

    private static class RecordingXp extends CardXpService {
        int awards;
        RecordingXp() { super(null, null, null, null, null); }
        @Override public Award award(Player player, String source, double amount, String mob, String claim) {
            assertEquals("kill-mob", source);
            assertEquals("ZOMBIE", mob);
            awards++;
            return Award.progress(source, 4, 0, 0);
        }
    }
}
