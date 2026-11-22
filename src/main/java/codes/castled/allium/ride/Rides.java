package codes.castled.allium.ride;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.Messages;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who rides whom through the powertool {@code ride} action, and the restraint rules of its
 * {@code restrain} option (the Handcuffs). A carrier's riders form one stack, each riding the one
 * below, bottom first, as pre-recode Allium's handcuffs stacked restrained players.
 *
 * <p>A restrained player cannot get down, run commands (but {@code restraints.allowed-commands}),
 * break, place, use or drop items, pick items up, attack, fish or take damage, and does not push
 * other players. Nothing about them is changed that must be put back later except collision, which
 * does not outlast their session, so a crash cannot leave anyone stuck. Riders are let go when their
 * carrier lets them go, quits, dies or is teleported by something other than Allium, and when they
 * quit, die or are teleported away; a restrained player who logs out runs
 * {@code restraints.commands-on-quit}. Allium's own teleports take the riders along. Server thread.
 */
public final class Rides implements Listener {

    /** What {@link #carry} did. */
    public enum Result { CARRIED, RESTRAINED, SELF, BUSY, FULL, FAILED }

    private record Rider(UUID id, UUID carrier, boolean restrained) {
    }

    private static volatile Rides current;

    private final PluginStart plugin;
    /** Each carrier's riders, bottom first. */
    private final Map<UUID, List<UUID>> stacks = new LinkedHashMap<>();
    private final Map<UUID, Rider> riders = new HashMap<>();
    /** Riders Allium is moving itself, whose dismounts and teleports are expected. */
    private final Set<UUID> moving = new HashSet<>();
    private final Map<UUID, Long> dismountNotices = new HashMap<>();
    private boolean enabled;

    public Rides(PluginStart plugin) {
        this.plugin = plugin;
    }

    /** The running service, for Allium's teleports; null while the tools module is off. */
    @Nullable
    public static Rides current() {
        return current;
    }

    public void enable() {
        if (enabled) {
            return;
        }
        enabled = true;
        var plugins = plugin.getServer().getPluginManager();
        plugins.registerEvents(this, plugin);
        // Spigot 1.20 has org.spigotmc's EntityDismountEvent; later APIs moved it to org.bukkit.
        for (String name : List.of("org.bukkit.event.entity.EntityDismountEvent", "org.spigotmc.event.entity.EntityDismountEvent")) {
            try {
                Class<? extends Event> type = Class.forName(name).asSubclass(Event.class);
                plugins.registerEvent(type, this, EventPriority.HIGHEST, (listener, event) -> {
                    if (event instanceof EntityEvent dismount && event instanceof Cancellable cancellable) {
                        onDismount(dismount.getEntity(), cancellable);
                    }
                }, plugin);
                break;
            } catch (ClassNotFoundException | ClassCastException absent) {
                // try the other name
            }
        }
        current = this;
    }

    /** Lets everyone go and stops listening. */
    public void disable() {
        if (!enabled) {
            return;
        }
        for (UUID carrier : List.copyOf(stacks.keySet())) {
            releaseAll(carrier);
        }
        HandlerList.unregisterAll(this);
        enabled = false;
        if (current == this) {
            current = null;
        }
    }

    // -- State --------------------------------------------------------------------------------

    public boolean isRestrained(UUID id) {
        Rider rider = riders.get(id);
        return rider != null && rider.restrained();
    }

    /** Whether {@code id} rides someone through {@link #carry}. */
    public boolean isCarried(UUID id) {
        return riders.containsKey(id);
    }

    /** Who carries {@code id}, or null. */
    @Nullable
    public UUID carrierOf(UUID id) {
        Rider rider = riders.get(id);
        return rider == null ? null : rider.carrier();
    }

    /** {@code carrier}'s riders, bottom first. */
    public List<UUID> riders(UUID carrier) {
        return List.copyOf(stacks.getOrDefault(carrier, List.of()));
    }

    /** Every restrained player. */
    public List<UUID> restrained() {
        return riders.values().stream().filter(Rider::restrained).map(Rider::id).toList();
    }

    // -- Carrying ---------------------------------------------------------------------------------

    /**
     * Puts {@code target} on top of {@code carrier}'s stack. It refuses the carrier itself, anyone
     * already carried, carrying someone, or carrying the carrier, and a full stack.
     */
    public Result carry(Player carrier, Entity target, boolean restrain, int maxRiders) {
        UUID carrierId = carrier.getUniqueId();
        UUID targetId = target.getUniqueId();
        if (carrierId.equals(targetId)) {
            return Result.SELF;
        }
        if (riders.containsKey(targetId) || stacks.containsKey(targetId) || riders.containsKey(carrierId)
                || ridesOn(carrier, target)) {
            return Result.BUSY;
        }
        List<UUID> stack = stacks.computeIfAbsent(carrierId, id -> new ArrayList<>());
        if (stack.size() >= Math.max(1, maxRiders)) {
            if (stack.isEmpty()) {
                stacks.remove(carrierId);
            }
            return Result.FULL;
        }
        Entity top = stack.isEmpty() ? carrier : entity(stack.get(stack.size() - 1));
        if (top == null) {
            top = carrier;
        }
        moving.add(targetId);
        try {
            target.leaveVehicle();
            if (!target.getPassengers().isEmpty()) {
                target.eject();
            }
            if (!top.addPassenger(target)) {
                if (stack.isEmpty()) {
                    stacks.remove(carrierId);
                }
                return Result.FAILED;
            }
        } finally {
            moving.remove(targetId);
        }
        stack.add(targetId);
        boolean restrained = restrain && target instanceof Player;
        riders.put(targetId, new Rider(targetId, carrierId, restrained));
        if (restrained) {
            ((Player) target).setCollidable(false);
        }
        return restrained ? Result.RESTRAINED : Result.CARRIED;
    }

    /** Whether {@code carrier} rides {@code target}, directly or further up. */
    private static boolean ridesOn(Entity carrier, Entity target) {
        Entity vehicle = carrier.getVehicle();
        for (int depth = 0; vehicle != null && depth < 64; depth++) {
            if (vehicle.getUniqueId().equals(target.getUniqueId())) {
                return true;
            }
            vehicle = vehicle.getVehicle();
        }
        return false;
    }

    /** Lets go of {@code carrier}'s riders: every one, or the top one. The released, top first. */
    public List<Entity> release(Player carrier, boolean all) {
        List<UUID> stack = stacks.get(carrier.getUniqueId());
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        List<Entity> released = new ArrayList<>();
        List<UUID> order = new ArrayList<>(all ? stack : List.of(stack.get(stack.size() - 1)));
        java.util.Collections.reverse(order);
        for (UUID id : order) {
            Entity entity = let(id);
            if (entity != null) {
                released.add(entity);
            }
        }
        return released;
    }

    private List<Entity> releaseAll(UUID carrier) {
        List<Entity> released = new ArrayList<>();
        List<UUID> stack = new ArrayList<>(stacks.getOrDefault(carrier, List.of()));
        java.util.Collections.reverse(stack);
        for (UUID id : stack) {
            Entity entity = let(id);
            if (entity != null) {
                released.add(entity);
            }
        }
        return released;
    }

    /** Lets one rider go wherever they are in a stack; the riders above close the gap. Null when not carried. */
    @Nullable
    public Entity let(UUID id) {
        Rider rider = riders.remove(id);
        if (rider == null) {
            return null;
        }
        dismountNotices.remove(id);
        List<UUID> stack = stacks.get(rider.carrier());
        int index = stack == null ? -1 : stack.indexOf(id);
        Entity entity = entity(id);
        moving.add(id);
        try {
            if (entity != null) {
                entity.leaveVehicle();
                if (rider.restrained() && entity instanceof Player player) {
                    player.setCollidable(true);
                }
            }
        } finally {
            moving.remove(id);
        }
        if (stack != null) {
            stack.remove(id);
            if (stack.isEmpty()) {
                stacks.remove(rider.carrier());
            } else if (index >= 0 && index < stack.size()) {
                restack(rider.carrier());
            }
        }
        return entity;
    }

    /** Seats every rider on the one below again, bottom first, dropping riders that are gone. */
    private void restack(UUID carrierId) {
        List<UUID> stack = stacks.get(carrierId);
        Entity below = entity(carrierId);
        if (stack == null || below == null) {
            return;
        }
        for (UUID id : List.copyOf(stack)) {
            Entity rider = entity(id);
            if (rider == null || !rider.isValid()) {
                forget(id);
                continue;
            }
            if (rider.getVehicle() == null || !rider.getVehicle().getUniqueId().equals(below.getUniqueId())) {
                moving.add(id);
                try {
                    rider.leaveVehicle();
                    if (!below.addPassenger(rider)) {
                        forget(id);
                        continue;
                    }
                } finally {
                    moving.remove(id);
                }
            }
            below = rider;
        }
    }

    /** Drops a rider from the books without moving them (they are already off). */
    private void forget(UUID id) {
        Rider rider = riders.remove(id);
        dismountNotices.remove(id);
        if (rider == null) {
            return;
        }
        Entity entity = entity(id);
        if (rider.restrained() && entity instanceof Player player) {
            player.setCollidable(true);
        }
        List<UUID> stack = stacks.get(rider.carrier());
        if (stack != null) {
            stack.remove(id);
            if (stack.isEmpty()) {
                stacks.remove(rider.carrier());
            }
        }
    }

    // -- Teleports --------------------------------------------------------------------------------

    /**
     * Teleports {@code carrier} with their riders, for Allium's teleports: Bukkit refuses to
     * teleport an entity that has passengers. The riders follow and are seated again a tick later.
     */
    public boolean teleport(Player carrier, Location to, PlayerTeleportEvent.TeleportCause cause) {
        List<UUID> stack = stacks.get(carrier.getUniqueId());
        if (stack == null || stack.isEmpty()) {
            return carrier.teleport(to, cause);
        }
        List<UUID> ids = List.copyOf(stack);
        moving.addAll(ids);
        boolean success;
        try {
            for (int i = ids.size() - 1; i >= 0; i--) {
                Entity rider = entity(ids.get(i));
                if (rider != null) {
                    rider.leaveVehicle();
                }
            }
            success = carrier.teleport(to, cause);
            if (success) {
                for (UUID id : ids) {
                    Entity rider = entity(id);
                    if (rider != null) {
                        rider.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
                    }
                }
            }
        } catch (RuntimeException failed) {
            moving.removeAll(ids);
            restack(carrier.getUniqueId());
            throw failed;
        }
        if (!success) {
            moving.removeAll(ids);
            restack(carrier.getUniqueId());
            return false;
        }
        // Seating a passenger in the tick it changed worlds can fail; wait for the move to settle.
        plugin.getScheduler().runOnServerLater(() -> {
            moving.removeAll(ids);
            if (carrier.isOnline()) {
                restack(carrier.getUniqueId());
            } else {
                releaseAll(carrier.getUniqueId());
            }
        }, 50L);
        return true;
    }

    // -- Events -----------------------------------------------------------------------------------

    void onDismount(Entity entity, Cancellable event) {
        UUID id = entity.getUniqueId();
        Rider rider = riders.get(id);
        if (rider == null || moving.contains(id)) {
            return;
        }
        if (!rider.restrained()) {
            // Getting down on their own; whoever rode on them moves down a seat.
            forget(id);
            plugin.getScheduler().runOnServer(() -> restack(rider.carrier()));
            return;
        }
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        Long last = dismountNotices.get(id);
        if ((last == null || now - last >= 10_000L) && entity instanceof Player player) {
            dismountNotices.put(id, now);
            send(player, Messages.RIDE_NO_DISMOUNT);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        for (Entity released : releaseAll(id)) {
            if (released instanceof Player rider && rider.isOnline()) {
                send(rider, Messages.RIDE_RELEASED_TARGET, "{player}", player.getName());
            }
        }
        Rider rider = riders.get(id);
        if (rider == null) {
            return;
        }
        let(id);
        if (!rider.restrained()) {
            return;
        }
        Player carrier = plugin.getServer().getPlayer(rider.carrier());
        if (carrier != null) {
            send(carrier, Messages.RIDE_LOGGED_OUT, "{target}", player.getName());
        }
        plugin.logger().info(player.getName() + " logged out while restrained"
                + (carrier == null ? "." : " by " + carrier.getName() + "."));
        for (String command : plugin.getConfig().getStringList("restraints.commands-on-quit")) {
            if (command == null || command.isBlank()) {
                continue;
            }
            String line = command.trim().replaceFirst("^/", "").replace("{player}", player.getName());
            try {
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), line);
            } catch (RuntimeException failed) {
                plugin.logger().warning("restraints.commands-on-quit: '" + line + "' failed: " + failed.getMessage());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        releaseAll(id);
        let(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (moving.contains(id) || !riders.containsKey(id)) {
            return;
        }
        // Teleported away by staff or another plugin: no longer carried. The teleport itself gets them down.
        UUID carrier = riders.get(id).carrier();
        moving.add(id);
        plugin.getScheduler().runOnServer(() -> {
            moving.remove(id);
            forget(id);
            restack(carrier);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isRestrained(player.getUniqueId())) {
            return;
        }
        String message = event.getMessage();
        String label = (message.startsWith("/") ? message.substring(1) : message).trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        String bare = label.substring(label.indexOf(':') + 1);
        for (String allowed : plugin.getConfig().getStringList("restraints.allowed-commands")) {
            String entry = allowed == null ? "" : allowed.trim().replaceFirst("^/", "").toLowerCase(Locale.ROOT);
            if (!entry.isEmpty() && (entry.equals(label) || entry.equals(bare))) {
                return;
            }
        }
        event.setCancelled(true);
        send(player, Messages.RIDE_NO_COMMANDS);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isRestrained(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isRestrained(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (isRestrained(event.getPlayer().getUniqueId())) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (isRestrained(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isRestrained(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (isRestrained(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (isRestrained(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            if (event.getHook() != null) {
                event.getHook().remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (isRestrained(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity shooter) {
                damager = shooter;
            }
            if (isRestrained(damager.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    // -- Helpers ----------------------------------------------------------------------------------

    @Nullable
    private Entity entity(UUID id) {
        Player player = plugin.getServer().getPlayer(id);
        return player != null ? player : plugin.getServer().getEntity(id);
    }

    private void send(CommandSender recipient, Messages message, String... replacements) {
        plugin.getMessageManager().send(recipient, message, replacements);
    }
}
