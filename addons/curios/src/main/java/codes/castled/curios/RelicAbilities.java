package codes.castled.curios;

import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static codes.castled.curios.Catalog.Ability.*;

final class RelicAbilities implements Listener {
    private static final Set<String> PURIFIABLE = Set.of("poison", "wither", "slowness");
    private static final Set<Material> UNSAFE_GROUND = Set.of(
            Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.CACTUS, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW);

    private final CuriosPlugin plugin;
    private final NamespacedKey aggro;
    private final NamespacedKey vexOwner;
    private final codes.castled.allium.scheduler.TaskScheduler scheduler;
    private final Map<UUID, EnumMap<Catalog.Ability, Long>> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, List<Vex>> vexes = new ConcurrentHashMap<>();
    private final Map<Block, Material> frozenLava = new ConcurrentHashMap<>();
    private final Set<TaskHandle> tasks = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<Boolean> retaliating = ThreadLocal.withInitial(() -> false);
    private boolean paperPushHooked;

    RelicAbilities(CuriosPlugin plugin, codes.castled.allium.scheduler.TaskScheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.aggro = new NamespacedKey(plugin, "retaliation_target");
        this.vexOwner = new NamespacedKey(plugin, "relic_vex_owner");
    }

    /** Optionally hooks Paper's push event when running on Paper/Purpur/Folia. */
    void registerOptionalHooks() {
        try {
            Class<? extends Event> pushClass = Class.forName("io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent")
                    .asSubclass(Event.class);
            Method getEntity = pushClass.getMethod("getEntity");
            Method getPushedBy = pushClass.getMethod("getPushedBy");
            Method setCancelled = pushClass.getMethod("setCancelled", boolean.class);
            plugin.getServer().getPluginManager().registerEvent(
                    pushClass, this, EventPriority.HIGH, (listener, event) -> {
                        if (!pushClass.isInstance(event)) return;
                        try {
                            Object victim = getEntity.invoke(event);
                            Object pusher = getPushedBy.invoke(event);
                            if (victim instanceof Player player
                                    && (pusher instanceof IronGolem || pusher instanceof Ravager)
                                    && plugin.hasAbility(player, GOLEM_KNOCKBACK_IMMUNITY)) {
                                setCancelled.invoke(event, true);
                            }
                        } catch (ReflectiveOperationException ignored) {
                        }
                    }, plugin, true);
            paperPushHooked = true;
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError unavailable) {
            paperPushHooked = false;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void target(EntityTargetEvent event) {
        String owner = event.getEntity().getPersistentDataContainer().get(vexOwner, PersistentDataType.STRING);
        if (owner != null && event.getTarget() != null && owner.equals(event.getTarget().getUniqueId().toString())) {
            event.setCancelled(true);
            return;
        }
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
        if (retaliating.get() || !(event.getEntity() instanceof LivingEntity victim) || event.getFinalDamage() <= 0) return;
        Entity source = event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter
                ? shooter : event.getDamager();
        if (source instanceof Player attacker) {
            Catalog.Ability truce = truce(victim);
            if (truce != null && plugin.hasAbility(attacker, truce)) recordTarget(victim, attacker);
            if (event.getDamager() == attacker && attacker.getInventory().getItemInMainHand().getType().getMaxDurability() == 0) {
                Catalog.Entry igniteEntry = plugin.abilityEntry(attacker, UNARMED_IGNITE);
                if (igniteEntry != null) {
                    int fireTicks = Math.max(1, igniteEntry.effectTicks("fire-ticks", 80));
                    victim.setFireTicks(Math.max(victim.getFireTicks(), fireTicks));
                }
            }
            Catalog.Entry echoEntry = plugin.abilityEntry(attacker, ECHO_STRIKE);
            if (echoEntry != null) {
                long cooldownMs = Math.max(0L, echoEntry.effectTicks("cooldown-ticks", 200) * 50L);
                if (acquire(attacker, ECHO_STRIKE, cooldownMs)) {
                    int ticks = Math.max(1, echoEntry.effectTicks("duration-ticks", 100));
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, ticks, 0));
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, ticks, 0));
                }
            }
            retargetVexes(attacker.getUniqueId(), victim);
        }
        if (victim instanceof Player target && source instanceof LivingEntity attacker && attacker != target) {
            retargetVexes(target.getUniqueId(), attacker);
            if (target.isInWater()) {
                Catalog.Entry waterEntry = plugin.abilityEntry(target, WATER_RETALIATE);
                if (waterEntry != null) {
                    double retaliationDamage = Math.max(0.0, waterEntry.effect("retaliation-damage", 2.0));
                    if (retaliationDamage > 0.0) {
                        retaliating.set(true);
                        try {
                            attacker.damage(retaliationDamage, target);
                        } finally {
                            retaliating.set(false);
                        }
                    }
                }
            }
            Catalog.Entry vexEntry = plugin.abilityEntry(target, SUMMON_VEX);
            if (vexEntry != null) {
                Attribute maxHealthAttr = CardBoostRuntime.resolveAttribute("max_health");
                var health = maxHealthAttr == null ? null : target.getAttribute(maxHealthAttr);
                double threshold = Math.max(0.0, Math.min(1.0, vexEntry.effect("health-threshold", 0.3)));
                long cooldownMs = Math.max(0L, vexEntry.effectTicks("cooldown-ticks", 600) * 50L);
                if (health != null
                        && target.getHealth() - event.getFinalDamage() < health.getValue() * threshold
                        && acquire(target, SUMMON_VEX, cooldownMs)) {
                    summonVexes(target, attacker, vexEntry);
                }
            }
            if (!paperPushHooked && (attacker instanceof IronGolem || attacker instanceof Ravager)
                    && plugin.hasAbility(target, GOLEM_KNOCKBACK_IMMUNITY)) {
                Vector preserved = target.getVelocity().clone();
                runEntity(target, () -> {
                    if (target.isOnline() && !target.isDead()) {
                        target.setVelocity(preserved);
                    }
                }, 0L);
            }
        }
    }

    private void summonVexes(Player owner, LivingEntity target, Catalog.Entry entry) {
        despawnVexes(owner.getUniqueId());
        int count = Math.max(0, Math.min(8, entry.effectTicks("vex-count", 2)));
        int durationTicks = Math.max(1, entry.effectTicks("duration-ticks", 300));
        if (count == 0) return;
        List<Vex> summoned = new ArrayList<>(count);
        Location spawnAt = owner.getLocation().add(0, 1, 0);
        for (int i = 0; i < count; i++) {
            Vex vex = owner.getWorld().spawn(spawnAt, Vex.class, v -> {
                v.getPersistentDataContainer().set(vexOwner, PersistentDataType.STRING, owner.getUniqueId().toString());
                v.setPersistent(false);
                v.setTarget(target);
                applyVexLifetime(v, durationTicks);
            });
            summoned.add(vex);
            long delayMs = durationTicks * 50L;
            TaskHandle[] handle = new TaskHandle[1];
            handle[0] = runEntity(vex, () -> {
                tasks.remove(handle[0]);
                if (vex.isValid()) vex.remove();
            }, delayMs);
            if (handle[0] != null && handle[0] != TaskHandle.NONE) tasks.add(handle[0]);
        }
        vexes.put(owner.getUniqueId(), summoned);
    }

    private static void applyVexLifetime(Vex vex, int durationTicks) {
        try {
            Vex.class.getMethod("setLimitedLifetime", boolean.class).invoke(vex, true);
            Vex.class.getMethod("setLifeTicks", int.class).invoke(vex, durationTicks);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Older Spigot builds still despawn via the scheduled task.
        }
    }

    private void retargetVexes(UUID ownerId, LivingEntity target) {
        List<Vex> list = vexes.get(ownerId);
        if (list == null || list.isEmpty() || target.getUniqueId().equals(ownerId)) return;
        for (Vex vex : list) {
            if (!vex.isValid()) continue;
            runEntity(vex, () -> {
                if (vex.isValid() && target.isValid() && !target.isDead()) {
                    vex.setTarget(target);
                }
            }, 0L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isOnGround() || player.getVehicle() != null) return;
        Catalog.Entry entry = plugin.abilityEntry(player, LAVA_WALKER);
        if (entry == null) return;
        int radius = Math.max(0, Math.min(8, entry.effectTicks("radius", 3)));
        long basaltMs = Math.max(1, entry.effectTicks("basalt-ticks", 60)) * 50L;
        long magmaMs = Math.max(1, entry.effectTicks("magma-ticks", 60)) * 50L;
        Block center = to.getBlock();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z > radius * radius) continue;
                Block block = center.getRelative(x, -1, z);
                if (block.getType() != Material.LAVA
                        || !(block.getBlockData() instanceof Levelled levelled)
                        || levelled.getLevel() != 0
                        || !block.getRelative(0, 1, 0).getType().isAir()) {
                    continue;
                }
                BlockState state = block.getState();
                state.setType(Material.BASALT);
                EntityBlockFormEvent formEvent = new EntityBlockFormEvent(player, block, state);
                Bukkit.getPluginManager().callEvent(formEvent);
                if (formEvent.isCancelled() || formEvent.getNewState().getType() != Material.BASALT) continue;
                if (!formEvent.getNewState().update(true)) continue;
                frozenLava.put(block, Material.BASALT);
                scheduleBlockLater(block, () -> {
                    if (block.getType() != Material.BASALT) {
                        frozenLava.remove(block);
                        return;
                    }
                    block.setType(Material.MAGMA_BLOCK);
                    frozenLava.put(block, Material.MAGMA_BLOCK);
                    scheduleBlockLater(block, () -> {
                        if (block.getType() == Material.MAGMA_BLOCK) {
                            block.setType(Material.LAVA);
                        }
                        frozenLava.remove(block);
                    }, magmaMs);
                }, basaltMs);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!player.isSneaking()) return;
        Catalog.Entry entry = plugin.abilityEntry(player, CHORUS_BLINK);
        if (entry == null) return;
        long cooldownMs = Math.max(0L, entry.effectTicks("cooldown-ticks", 300) * 50L);
        if (!acquire(player, CHORUS_BLINK, cooldownMs)) return;
        event.setCancelled(true);
        int radius = Math.max(1, Math.min(32, entry.effectTicks("radius", 16)));
        int attempts = Math.max(1, Math.min(64, entry.effectTicks("attempts", 16)));
        Location destination = findChorusDestination(player.getLocation(), radius, attempts, ThreadLocalRandom.current());
        if (destination == null) {
            releaseCooldown(player.getUniqueId(), CHORUS_BLINK);
            return;
        }
        var future = plugin.core() != null
                ? plugin.core().teleport(player, destination, PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT)
                : java.util.concurrent.CompletableFuture.completedFuture(
                        player.teleport(destination, PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT));
        future.thenAccept(ok -> {
            if (!Boolean.TRUE.equals(ok)) {
                releaseCooldown(player.getUniqueId(), CHORUS_BLINK);
            } else {
                runEntity(player, () -> {
                    if (player.isOnline()) {
                        player.playSound(player.getLocation(), Sound.ITEM_CHORUS_FRUIT_TELEPORT, 1.0f, 1.0f);
                    }
                }, 0L);
            }
        });
    }

    static Location findChorusDestination(Location from, int radius, int attempts, Random rng) {
        World world = from == null ? null : from.getWorld();
        if (world == null) return null;
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        for (int i = 0; i < attempts; i++) {
            int x = from.getBlockX() + rng.nextInt(-radius, radius + 1);
            int z = from.getBlockZ() + rng.nextInt(-radius, radius + 1);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = Math.max(minY + 1, Math.min(maxY - 3, from.getBlockY() + rng.nextInt(-radius, radius + 1)));
            Block ground = world.getBlockAt(x, y, z);
            while (ground.getY() > minY && !ground.getType().isSolid()) {
                ground = ground.getRelative(0, -1, 0);
            }
            if (!ground.getType().isSolid() || ground.isLiquid() || UNSAFE_GROUND.contains(ground.getType())) {
                continue;
            }
            Block feet = ground.getRelative(0, 1, 0);
            Block head = ground.getRelative(0, 2, 0);
            if (!feet.isPassable() || feet.isLiquid() || !head.isPassable() || head.isLiquid()) {
                continue;
            }
            Location destination = feet.getLocation().add(0.5, 0.0, 0.5);
            destination.setYaw(from.getYaw());
            destination.setPitch(from.getPitch());
            if (world.getWorldBorder() != null && !world.getWorldBorder().isInside(destination)) {
                continue;
            }
            return destination;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void effect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getNewEffect() == null
                || !PURIFIABLE.contains(event.getNewEffect().getType().getKey().getKey())) return;
        Catalog.Entry entry = plugin.abilityEntry(player, PURIFY);
        if (entry == null) return;
        long cooldownMs = Math.max(0L, entry.effectTicks("cooldown-ticks", 600) * 50L);
        if (!acquire(player, PURIFY, cooldownMs)) return;
        int durationTicks = Math.max(1, entry.effectTicks("duration-ticks", 100));
        event.setCancelled(true);
        var type = event.getNewEffect().getType();
        runEntity(player, () -> {
            if (player.isOnline() && !player.isDead()) {
                player.removePotionEffect(type);
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, durationTicks, 0));
            }
        }, 0L);
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        despawnVexes(id);
        cooldowns.remove(id);
    }

    void close() {
        tasks.forEach(TaskHandle::cancel);
        tasks.clear();
        for (UUID ownerId : List.copyOf(vexes.keySet())) {
            despawnVexes(ownerId);
        }
        frozenLava.forEach((block, expected) -> {
            if (plugin.core() != null && plugin.core().isFolia()
                    && !plugin.core().ownsRegion(block.getWorld(), block.getX() >> 4, block.getZ() >> 4)) {
                return;
            }
            if (block.getType() == expected) {
                block.setType(Material.LAVA);
            }
        });
        frozenLava.clear();
        cooldowns.clear();
    }

    private void despawnVexes(UUID ownerId) {
        List<Vex> list = vexes.remove(ownerId);
        if (list == null) return;
        for (Vex vex : list) {
            if (!vex.isValid()) continue;
            if (plugin.core() == null || plugin.core().ownsEntity(vex)) {
                vex.remove();
            } else if (plugin.isEnabled()) {
                plugin.core().runForEntity(vex, () -> {
                    if (vex.isValid()) vex.remove();
                }, () -> {}, 0L);
            }
        }
    }

    private TaskHandle runEntity(Entity entity, Runnable task, long delayMs) {
        if (plugin.core() != null) {
            return plugin.core().runForEntity(entity, task, () -> {}, delayMs);
        }
        return delayMs > 0L ? scheduler.runOnServerLater(task, delayMs) : scheduler.runOnServer(task);
    }

    private void scheduleBlockLater(Block block, Runnable action, long delayMs) {
        TaskHandle[] handle = new TaskHandle[1];
        Runnable wrapped = () -> {
            tasks.remove(handle[0]);
            action.run();
        };
        handle[0] = plugin.core() != null
                ? plugin.core().runForRegion(block.getWorld(), block.getX() >> 4, block.getZ() >> 4, wrapped, delayMs)
                : scheduler.runOnServerLater(wrapped, delayMs);
        if (handle[0] != null && handle[0] != TaskHandle.NONE) tasks.add(handle[0]);
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
        synchronized (state) {
            if (state.getOrDefault(ability, 0L) > now) return false;
            state.put(ability, now + duration);
            return true;
        }
    }

    private void releaseCooldown(UUID playerId, Catalog.Ability ability) {
        var state = cooldowns.get(playerId);
        if (state == null) return;
        synchronized (state) {
            state.remove(ability);
        }
    }
}
