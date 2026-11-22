package codes.castled.curios;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static codes.castled.curios.Catalog.Ability.*;

final class RelicAbilities implements Listener {
    private static final Set<String> PURIFIABLE = Set.of("poison", "wither", "slowness");
    private final CuriosPlugin plugin;
    private final NamespacedKey aggro;
    private final codes.castled.allium.scheduler.TaskScheduler scheduler;
    private final Map<UUID, EnumMap<Catalog.Ability, Long>> cooldowns = new HashMap<>();
    private boolean retaliating;

    RelicAbilities(CuriosPlugin plugin, codes.castled.allium.scheduler.TaskScheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        aggro = new NamespacedKey(plugin, "retaliation_target");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void target(EntityTargetEvent event) {
        if (!(event.getTarget() instanceof Player target)) return;
        Catalog.Ability ability = truce(event.getEntity());
        if (ability == null || !plugin.hasAbility(target, ability)) return;

        switch (event.getReason()) {
            case TARGET_ATTACKED_ENTITY, TARGET_ATTACKED_NEARBY_ENTITY, DEFEND_VILLAGE, CUSTOM, UNKNOWN -> {
                recordTarget(event.getEntity(), target);
                return;
            }
            default -> { }
        }
        String retaliation = event.getEntity().getPersistentDataContainer().get(aggro, PersistentDataType.STRING);
        if (!target.getUniqueId().toString().equals(retaliation)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent event) {
        if (retaliating || !(event.getEntity() instanceof LivingEntity victim) || event.getFinalDamage() <= 0) return;
        Entity source = event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter
                ? shooter : event.getDamager();
        if (source instanceof Player attacker) {
            Catalog.Ability truce = truce(victim);
            if (truce != null && plugin.hasAbility(attacker, truce)) recordTarget(victim, attacker);
            if (event.getDamager() == attacker && attacker.getInventory().getItemInMainHand().getType().getMaxDurability() == 0
                    && plugin.hasAbility(attacker, UNARMED_IGNITE)) {
                victim.setFireTicks(Math.max(victim.getFireTicks(), 80));
            }
            if (plugin.hasAbility(attacker, ECHO_STRIKE) && acquire(attacker, ECHO_STRIKE, 10_000)) {
                victim.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 100, 0));
                victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 0));
            }
        }
        if (victim instanceof Player target && source instanceof LivingEntity attacker && attacker != target
                && target.isInWater() && plugin.hasAbility(target, WATER_RETALIATE)) {
            retaliating = true;
            try {
                attacker.damage(2.0, target);
            } finally {
                retaliating = false;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void effect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getNewEffect() == null
                || !PURIFIABLE.contains(event.getNewEffect().getType().getKey().getKey())
                || !plugin.hasAbility(player, PURIFY) || !acquire(player, PURIFY, 30_000)) return;
        event.setCancelled(true);
        var type = event.getNewEffect().getType();
        scheduler.runOnServer(() -> {
            if (player.isOnline() && !player.isDead()) {
                player.removePotionEffect(type);
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 0));
            }
        });
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
    }

    private static Catalog.Ability truce(Entity entity) {
        if (entity instanceof Phantom) return PHANTOM_TRUCE;
        if (entity instanceof PiglinAbstract) return PIGLIN_TRUCE;
        return null;
    }

    private void recordTarget(Entity entity, Player target) {
        entity.getPersistentDataContainer().set(aggro, PersistentDataType.STRING, target.getUniqueId().toString());
    }

    private boolean acquire(Player player, Catalog.Ability ability, long duration) {
        long now = System.currentTimeMillis();
        var state = cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new EnumMap<>(Catalog.Ability.class));
        if (state.getOrDefault(ability, 0L) > now) return false;
        state.put(ability, now + duration);
        return true;
    }
}
