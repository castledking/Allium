package codes.castled.allium.legacy;

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
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who rides whom through the powertool {@code ride} action, and the restraints of its
 * {@code restrain} option (the Handcuffs), as on the modern platform. A carrier's riders form one
 * stack, each riding the one below.
 *
 * <p>A restrained player cannot get down, run commands (but {@code restraints.allowed-commands}),
 * break, place, use or drop items, pick items up, attack, fish or take damage, and does not push
 * other players. Riders are let go when their carrier lets them go, quits or dies, and when they
 * quit, die or are teleported away; a restrained player who logs out runs
 * {@code restraints.commands-on-quit}. Before 1.13 getting down cannot be cancelled, so a
 * restrained player who gets down is seated again a tick later. A carrier cannot be teleported
 * while carrying anyone, as Bukkit refuses to teleport an entity with passengers.
 */
final class LegacyRides implements Listener {
    enum Result { CARRIED, RESTRAINED, SELF, BUSY, FULL, FAILED }

    static final class Rider {
        final UUID id, carrier;
        final boolean restrained;

        Rider(UUID id, UUID carrier, boolean restrained) {
            this.id = id;
            this.carrier = carrier;
            this.restrained = restrained;
        }
    }

    private final JavaPlugin plugin;
    private final LegacyConfigManager config;
    private final LegacyMessageManager messages;
    private final Map<UUID, Rider> riders = new HashMap<>();
    private final Map<UUID, List<UUID>> stacks = new HashMap<>();
    private final Set<UUID> moving = new HashSet<>();
    private final Map<UUID, Long> dismountNotices = new HashMap<>();

    LegacyRides(JavaPlugin plugin, LegacyConfigManager config, LegacyMessageManager messages) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
    }

    void enable() {
        for (String name : new String[]{"org.bukkit.event.entity.EntityDismountEvent", "org.spigotmc.event.entity.EntityDismountEvent"}) {
            try {
                Class<? extends Event> type = Class.forName(name).asSubclass(Event.class);
                plugin.getServer().getPluginManager().registerEvent(type, this, EventPriority.HIGHEST, (listener, event) -> {
                    if (event instanceof EntityEvent) onDismount(((EntityEvent) event).getEntity(), event instanceof Cancellable ? (Cancellable) event : null);
                }, plugin);
                break;
            } catch (ClassNotFoundException | ClassCastException absent) {
                // try the other name
            }
        }
    }

    /** Lets everyone go. */
    void disable() {
        for (UUID carrier : new ArrayList<>(stacks.keySet())) releaseAll(carrier);
        HandlerList.unregisterAll(this);
    }

    boolean isRestrained(UUID id) {
        Rider rider = riders.get(id);
        return rider != null && rider.restrained;
    }

    boolean isCarried(UUID id) {
        return riders.containsKey(id);
    }

    UUID carrierOf(UUID id) {
        Rider rider = riders.get(id);
        return rider == null ? null : rider.carrier;
    }

    List<UUID> riders(UUID carrier) {
        List<UUID> stack = stacks.get(carrier);
        return stack == null ? Collections.<UUID>emptyList() : new ArrayList<>(stack);
    }

    List<UUID> restrained() {
        List<UUID> out = new ArrayList<>();
        for (Rider rider : riders.values()) if (rider.restrained) out.add(rider.id);
        return out;
    }

    /** Puts {@code target} on top of {@code carrier}'s stack. */
    Result carry(Player carrier, Entity target, boolean restrain, int maxRiders) {
        UUID carrierId = carrier.getUniqueId();
        UUID targetId = target.getUniqueId();
        if (carrierId.equals(targetId)) return Result.SELF;
        if (riders.containsKey(targetId) || stacks.containsKey(targetId) || riders.containsKey(carrierId) || ridesOn(carrier, target)) {
            return Result.BUSY;
        }
        List<UUID> stack = stacks.get(carrierId);
        if (stack == null) stack = new ArrayList<>();
        if (stack.size() >= Math.max(1, maxRiders)) return Result.FULL;
        Entity top = stack.isEmpty() ? carrier : entity(stack.get(stack.size() - 1));
        if (top == null) top = carrier;
        moving.add(targetId);
        try {
            target.leaveVehicle();
            if (!target.getPassengers().isEmpty()) target.eject();
            if (!top.addPassenger(target)) return Result.FAILED;
        } finally {
            moving.remove(targetId);
        }
        stack.add(targetId);
        stacks.put(carrierId, stack);
        boolean restrained = restrain && target instanceof Player;
        riders.put(targetId, new Rider(targetId, carrierId, restrained));
        if (restrained) ((Player) target).setCollidable(false);
        return restrained ? Result.RESTRAINED : Result.CARRIED;
    }

    private static boolean ridesOn(Entity carrier, Entity target) {
        Entity vehicle = carrier.getVehicle();
        for (int depth = 0; vehicle != null && depth < 64; depth++) {
            if (vehicle.getUniqueId().equals(target.getUniqueId())) return true;
            vehicle = vehicle.getVehicle();
        }
        return false;
    }

    /** Lets go of every rider, or the top one; the released, top first. */
    List<Entity> release(Player carrier, boolean all) {
        List<UUID> snapshot = riders(carrier.getUniqueId());
        if (snapshot.isEmpty()) return Collections.emptyList();
        List<UUID> order = all ? snapshot : new ArrayList<>(Collections.singletonList(snapshot.get(snapshot.size() - 1)));
        Collections.reverse(order);
        List<Entity> released = new ArrayList<>();
        for (UUID id : order) {
            Entity entity = let(id);
            if (entity != null) released.add(entity);
        }
        return released;
    }

    private List<Entity> releaseAll(UUID carrier) {
        List<UUID> stack = riders(carrier);
        Collections.reverse(stack);
        List<Entity> released = new ArrayList<>();
        for (UUID id : stack) {
            Entity entity = let(id);
            if (entity != null) released.add(entity);
        }
        return released;
    }

    /** Lets one rider go wherever they are in a stack; those above close the gap. */
    Entity let(UUID id) {
        Rider rider = riders.remove(id);
        if (rider == null) return null;
        dismountNotices.remove(id);
        boolean restack = false;
        List<UUID> stack = stacks.get(rider.carrier);
        if (stack != null) {
            int index = stack.indexOf(id);
            stack.remove(id);
            if (stack.isEmpty()) stacks.remove(rider.carrier);
            else restack = index >= 0 && index < stack.size();
        }
        Entity entity = entity(id);
        if (entity != null) {
            moving.add(id);
            try {
                entity.leaveVehicle();
                if (rider.restrained && entity instanceof Player) ((Player) entity).setCollidable(true);
            } finally {
                moving.remove(id);
            }
        }
        if (restack) restack(rider.carrier);
        return entity;
    }

    /** Seats every rider on the one below again, bottom first. */
    private void restack(UUID carrierId) {
        Entity below = entity(carrierId);
        if (below == null) return;
        for (UUID id : riders(carrierId)) {
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

    private void forget(UUID id) {
        Rider rider = riders.remove(id);
        dismountNotices.remove(id);
        if (rider == null) return;
        Entity entity = entity(id);
        if (rider.restrained && entity instanceof Player) ((Player) entity).setCollidable(true);
        List<UUID> stack = stacks.get(rider.carrier);
        if (stack != null) {
            stack.remove(id);
            if (stack.isEmpty()) stacks.remove(rider.carrier);
        }
    }

    // ---- events ----------------------------------------------------------------------------------

    void onDismount(Entity entity, Cancellable event) {
        UUID id = entity.getUniqueId();
        Rider rider = riders.get(id);
        if (rider == null || moving.contains(id)) return;
        if (!rider.restrained) {
            forget(id);
            plugin.getServer().getScheduler().runTask(plugin, () -> restack(rider.carrier));
            return;
        }
        if (event != null) event.setCancelled(true);
        else plugin.getServer().getScheduler().runTask(plugin, () -> restack(rider.carrier));
        long now = System.currentTimeMillis();
        Long last = dismountNotices.get(id);
        if ((last == null || now - last >= 10_000L) && entity instanceof Player) {
            dismountNotices.put(id, now);
            messages.send(entity, "tool.ride.no-dismount", "{prefix} &cYou cannot dismount while restrained!");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        for (Entity released : releaseAll(id)) {
            if (released instanceof Player && ((Player) released).isOnline()) {
                messages.send(released, "tool.ride.released-target", "{prefix} &aYou were released by &f{player}&a.", "{player}", player.getName());
            }
        }
        Rider rider = riders.get(id);
        if (rider == null) return;
        let(id);
        if (!rider.restrained) return;
        Player carrier = plugin.getServer().getPlayer(rider.carrier);
        if (carrier != null) {
            messages.send(carrier, "tool.ride.logged-out", "{prefix} &f{target} &ehas logged out while restrained.", "{target}", player.getName());
        }
        plugin.getLogger().info(player.getName() + " logged out while restrained" + (carrier == null ? "." : " by " + carrier.getName() + "."));
        for (String command : config.raw().getStringList("restraints.commands-on-quit")) {
            if (command == null || command.trim().isEmpty()) continue;
            String line = command.trim().replaceFirst("^/", "").replace("{player}", player.getName());
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), line);
                } catch (RuntimeException failed) {
                    plugin.getLogger().warning("restraints.commands-on-quit: '" + line + "' failed: " + failed.getMessage());
                }
            });
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
        Rider rider = riders.get(id);
        if (rider == null || moving.contains(id)) return;
        // Teleported away by staff or another plugin: no longer carried.
        moving.add(id);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            moving.remove(id);
            forget(id);
            restack(rider.carrier);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isRestrained(player.getUniqueId())) return;
        String message = event.getMessage();
        String label = (message.startsWith("/") ? message.substring(1) : message).trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        String bare = label.substring(label.indexOf(':') + 1);
        for (String allowed : config.raw().getStringList("restraints.allowed-commands")) {
            String entry = allowed == null ? "" : allowed.trim().replaceFirst("^/", "").toLowerCase(Locale.ROOT);
            if (!entry.isEmpty() && (entry.equals(label) || entry.equals(bare))) return;
        }
        event.setCancelled(true);
        messages.send(player, "tool.ride.no-commands", "{prefix} &cYou cannot use commands while restrained!");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isRestrained(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isRestrained(event.getPlayer().getUniqueId())) event.setCancelled(true);
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
        if (isRestrained(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isRestrained(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent event) {
        if (isRestrained(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (isRestrained(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            if (event.getHook() != null) event.getHook().remove();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (isRestrained(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (event instanceof EntityDamageByEntityEvent) {
            Entity damager = ((EntityDamageByEntityEvent) event).getDamager();
            if (damager instanceof Projectile && ((Projectile) damager).getShooter() instanceof LivingEntity) {
                damager = (LivingEntity) ((Projectile) damager).getShooter();
            }
            if (isRestrained(damager.getUniqueId())) event.setCancelled(true);
        }
    }

    // ---- /unrestrain -----------------------------------------------------------------------------

    /** {@code /unrestrain [player]}: frees every restrained player, or one player, restrained or carried. */
    boolean unrestrain(CommandSender sender, String[] args) {
        if (args.length > 1) return false;
        List<UUID> targets;
        if (args.length == 1) {
            Player target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null || sender instanceof Player && !((Player) sender).canSee(target)) {
                messages.send(sender, "error.player-not-found", "{prefix} &cPlayer &f{player} &cwas not found.", "{player}", args[0]);
                return true;
            }
            if (!isCarried(target.getUniqueId())) {
                messages.send(sender, "tool.unrestrain.not-restrained", "{prefix} &f{player} &7is not restrained or carried.",
                        "{player}", target.getName());
                return true;
            }
            targets = Collections.singletonList(target.getUniqueId());
        } else {
            targets = restrained();
            if (targets.isEmpty()) {
                messages.send(sender, "tool.unrestrain.none", "{prefix} &7No one is restrained.");
                return true;
            }
        }
        int count = 0;
        for (UUID id : targets) {
            UUID carrierId = carrierOf(id);
            Entity released = let(id);
            if (released == null) continue;
            count++;
            if (released instanceof Player && ((Player) released).isOnline()) {
                messages.send(released, "tool.ride.released-target", "{prefix} &aYou were released by &f{player}&a.", "{player}", sender.getName());
            }
            Player carrier = carrierId == null ? null : plugin.getServer().getPlayer(carrierId);
            if (carrier != null && carrier != sender) {
                messages.send(carrier, "tool.ride.released", "{prefix} &aYou released &f{target}&a.", "{target}", released.getName());
            }
        }
        plugin.getLogger().info(sender.getName() + " used /unrestrain and freed " + count + " player(s).");
        messages.send(sender, "tool.unrestrain.released", "{prefix} &aFreed &f{count} &aplayer(s).", "{count}", Integer.toString(count));
        return true;
    }

    List<String> complete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1 || !sender.hasPermission("allium.unrestrain")) return out;
        String prefix = args[0].toLowerCase(Locale.ROOT);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!isCarried(player.getUniqueId())) continue;
            if (sender instanceof Player && !((Player) sender).canSee(player)) continue;
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(player.getName());
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private Entity entity(UUID id) {
        Player player = plugin.getServer().getPlayer(id);
        return player != null ? player : plugin.getServer().getEntity(id);
    }
}
