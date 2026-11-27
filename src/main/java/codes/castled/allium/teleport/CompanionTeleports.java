package codes.castled.allium.teleport;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Selection state belongs to the player. Glow and teleport work belongs to the selected entity. */
public final class CompanionTeleports implements Listener, AutoCloseable {
    private static final String PET_PERMISSION = "allium.tppet";
    private static final String ENTITY_PERMISSION = "allium.tpmob";

    private static final class Selection {
        final Player owner;
        final UUID ownerId;
        final Location start;
        final Set<UUID> pets = new LinkedHashSet<>();
        final Set<UUID> entities = new LinkedHashSet<>();
        // Entity callbacks use marker identity to ignore stale glow work after deselection.
        final Map<UUID, Marker> marks = new ConcurrentHashMap<>();
        boolean petMode;
        boolean entityMode;
        boolean awaitingArrival;
        boolean checkingDestination;
        long modeRevision;
        long moveRevision;

        Selection(Player owner) {
            this.owner = owner;
            this.ownerId = owner.getUniqueId();
            this.start = owner.getLocation().clone();
        }
    }

    private static final class Marker {
        final Selection selection;
        final Entity entity;
        final UUID id;
        volatile boolean entitySelected;

        Marker(Selection selection, Entity entity) {
            this.selection = selection;
            this.entity = entity;
            this.id = entity.getUniqueId();
        }
    }

    private record Glow(Entity entity, boolean original, Set<Marker> markers) {}
    public record MobChanges(int eligible, int changed) {}

    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
    private final Map<UUID, Glow> glows = new ConcurrentHashMap<>();
    private final PluginStart plugin;
    private final DestinationSafety safety;
    private TaskHandle timer = TaskHandle.NONE;
    private volatile boolean closed;

    public CompanionTeleports(PluginStart plugin) {
        this(plugin, new DestinationSafety());
    }

    CompanionTeleports(PluginStart plugin, DestinationSafety safety) {
        this.plugin = plugin;
        this.safety = safety;
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, codes.castled.allium.PluginStart.bukkitPlugin(plugin));
        timer = plugin.getScheduler().repeatOnServer(() -> {
            for (Selection selection : List.copyOf(selections.values())) {
                onEntity(selection.owner, () -> expire(selection), () -> deferClear(selection));
            }
            return !closed;
        }, 1000, 1000);
    }

    private void expire(Selection selection) {
        if (!current(selection)) return;
        Player owner = selection.owner;
        if (!connected(owner) || (!selection.awaitingArrival && !selection.checkingDestination
                && !owner.hasPermission("allium.admin") && (owner.getWorld() != selection.start.getWorld()
                || owner.getLocation().distanceSquared(selection.start) > 900))) {
            clear(selection);
        }
    }

    public void toggle(CommandSender actor, Player target, boolean pets) {
        String permission = pets ? PET_PERMISSION : ENTITY_PERMISSION;
        if (!actor.hasPermission(permission) || (actor != target && !actor.hasPermission(permission + ".others"))) {
            send(actor, "Permission denied.");
            return;
        }
        onEntity(target, () -> toggleOwned(actor, target, pets, permission), () -> {});
    }

    private void toggleOwned(CommandSender actor, Player target, boolean pets, String permission) {
        if (closed || !connected(target)) return;
        if (!target.hasPermission(permission)) {
            send(actor, "Permission denied.");
            return;
        }
        UUID id = target.getUniqueId();
        Selection selection = selections.computeIfAbsent(id, ignored -> new Selection(target));
        if (selection.owner != target) return;
        boolean wasEnabled = pets ? selection.petMode : selection.entityMode;
        if (pets) selection.petMode = !wasEnabled;
        else selection.entityMode = !wasEnabled;
        selection.modeRevision++;
        if (selection.checkingDestination) {
            selection.moveRevision++;
            selection.checkingDestination = false;
        }
        if (wasEnabled) {
            Set<UUID> selected = pets ? selection.pets : selection.entities;
            var removed = List.copyOf(selected);
            selected.clear();
            for (UUID entityId : removed) updateMarker(selection, entityId);
        }
        send(target, (pets ? "Pet" : "Entity") + " selection " + (wasEnabled ? "disabled." : "enabled. Right-click to select."));
        if (actor != target) send(actor, "Selection mode updated for " + target.getName() + ".");
        if (!selection.petMode && !selection.entityMode) clear(selection);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void click(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player owner = event.getPlayer();
        Selection selection = selections.get(owner.getUniqueId());
        Entity entity = event.getRightClicked();
        if (selection == null || selection.owner != owner || entity instanceof Player || selection.checkingDestination) return;

        // Defer until the final interaction result is known, then inspect the entity on its owner.
        long modeRevision = selection.modeRevision;
        BukkitServerThread.runFor(plugin, plugin.getScheduler(), owner, () -> {
            if (event.isCancelled() || !canSelect(selection, modeRevision)) return;
            boolean pets = selection.petMode;
            boolean entities = selection.entityMode;
            onEntity(entity, () -> {
                if (!entity.isValid()) return;
                boolean pet = pets && owned(entity, selection.ownerId);
                if (pet || entities) {
                    onEntity(owner, () -> {
                        if (canSelect(selection, modeRevision)) select(selection, entity, pet);
                    }, () -> {});
                }
            }, () -> {});
        }, () -> {}, 50);
    }

    private boolean canSelect(Selection selection, long modeRevision) {
        return current(selection) && selection.modeRevision == modeRevision
                && !selection.checkingDestination && connected(selection.owner);
    }

    private void select(Selection selection, Entity entity, boolean pets) {
        String permission = pets ? PET_PERMISSION : ENTITY_PERMISSION;
        if (!(pets ? selection.petMode : selection.entityMode) || !selection.owner.hasPermission(permission)) return;
        Set<UUID> selected = pets ? selection.pets : selection.entities;
        UUID id = entity.getUniqueId();
        if (selected.remove(id)) {
            updateMarker(selection, id);
            send(selection.owner, "Deselected " + entity.getType().name() + ".");
            return;
        }
        int max = maxSelection(selection.owner, pets);
        if (selected.size() >= max) {
            send(selection.owner, "Selection limit: " + max);
            return;
        }
        selected.add(id);
        Marker marker = selection.marks.get(id);
        if (marker == null) {
            marker = new Marker(selection, entity);
            selection.marks.put(id, marker);
            Marker added = marker;
            onEntity(entity, () -> highlight(added), () -> {});
        }
        marker.entitySelected = selection.entities.contains(id);
        send(selection.owner, "Selected " + entity.getType().name() + ".");
    }

    private void highlight(Marker marker) {
        if (!current(marker.selection) || marker.selection.marks.get(marker.id) != marker || !marker.entity.isValid()) return;
        Glow glow = glows.get(marker.id);
        if (glow == null || glow.entity() != marker.entity) {
            glow = new Glow(marker.entity, marker.entity.isGlowing(), new HashSet<>());
            glows.put(marker.id, glow);
        }
        glow.markers().add(marker);
        marker.entity.setGlowing(true);
    }

    /** Maximum entities the owner may select with /tppet or /tpe. */
    public int maxSelection(Player owner, boolean pets) {
        TeleportSettings config = TeleportSettings.read(plugin.getConfig());
        int configuredMax = pets ? config.tppetMaxEntities() : config.tpeMaxEntities();
        String permission = pets ? PET_PERMISSION : ENTITY_PERMISSION;
        if (owner.isOp() || owner.hasPermission("allium.admin") || owner.hasPermission(permission + ".*")) return configuredMax;
        String prefix = permission + ".";
        int highest = 0;
        var effective = owner.getEffectivePermissions();
        if (effective != null) {
            for (var info : effective) {
                if (!info.getValue()) continue;
                String node = info.getPermission().toLowerCase(Locale.ROOT);
                if (!node.startsWith(prefix)) continue;
                String suffix = node.substring(prefix.length());
                if (suffix.matches("[0-9]{1,4}")) {
                    int value = Integer.parseInt(suffix);
                    if (value > highest && owner.hasPermission(info.getPermission())) highest = value;
                }
            }
        }
        if (highest == 0) {
            for (int i = 1; i <= Math.min(configuredMax, 100); i++) {
                if (owner.hasPermission(prefix + i)) highest = i;
            }
        }
        return highest > 0 ? Math.min(highest, configuredMax) : configuredMax;
    }

    private static boolean owned(Entity entity, UUID owner) {
        if (!(entity instanceof Tameable tameable) || !tameable.isTamed()) return false;
        var tamer = tameable.getOwner();
        return tamer != null && owner.equals(tamer.getUniqueId());
    }

    /** Immediate snapshot for callers on a single-threaded server, or mobs in the current region. */
    public List<LivingEntity> selectedMobs(Player player) {
        if (BukkitServerThread.isFolia() && !BukkitServerThread.entity(plugin, player).isServerThread()) return List.of();
        Selection selection = mobSelection(player);
        if (selection == null) return List.of();
        World world = player.getWorld();
        List<LivingEntity> result = new ArrayList<>();
        for (UUID id : selection.entities) {
            Entity entity = plugin.getServer().getEntity(id);
            if (entity instanceof LivingEntity mob && !(mob instanceof Player)
                    && (!BukkitServerThread.isFolia() || BukkitServerThread.entity(plugin, mob).isServerThread())
                    && validMob(mob, world)) result.add(mob);
        }
        return List.copyOf(result);
    }

    /** Called on the player owner. Applies an operation to the selected mobs without consuming the selection. */
    public CompletableFuture<MobChanges> applySelectedMobs(Player player, Predicate<LivingEntity> action) {
        Selection selection = mobSelection(player);
        if (selection == null) return CompletableFuture.completedFuture(new MobChanges(0, 0));
        World world = player.getWorld();
        List<CompletableFuture<MobChanges>> jobs = new ArrayList<>();
        for (UUID id : selection.entities) {
            Marker marker = selection.marks.get(id);
            if (marker == null || !(plugin.getServer().getEntity(id) instanceof LivingEntity mob) || mob instanceof Player) continue;
            var job = new CompletableFuture<MobChanges>();
            jobs.add(job);
            onEntity(mob, () -> {
                try {
                    boolean eligible = current(selection) && selection.marks.get(id) == marker && marker.entitySelected
                            && validMob(mob, world);
                    job.complete(eligible ? new MobChanges(1, action.test(mob) ? 1 : 0) : new MobChanges(0, 0));
                } catch (RuntimeException failure) {
                    job.complete(new MobChanges(1, 0));
                }
            }, () -> job.complete(new MobChanges(0, 0)));
        }
        return CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            int eligible = 0;
            int changed = 0;
            for (var job : jobs) {
                MobChanges result = job.join();
                eligible += result.eligible();
                changed += result.changed();
            }
            return new MobChanges(eligible, changed);
        });
    }

    private Selection mobSelection(Player player) {
        Selection selection = selections.get(player.getUniqueId());
        return selection != null && selection.owner == player && selection.entityMode && player.hasPermission(ENTITY_PERMISSION)
                ? selection : null;
    }

    private static boolean validMob(LivingEntity mob, World world) {
        return mob.isValid() && !mob.isDead() && mob.getWorld() == world;
    }

    /** Uses the player's selected companions at an explicit /tphere or /there destination. */
    public boolean move(Player player, Location destination) {
        Selection selection = selections.get(player.getUniqueId());
        if (closed || selection == null || selection.owner != player || selection.marks.isEmpty()) return false;
        if (!selection.checkingDestination) {
            selection.awaitingArrival = false;
            move(selection, destination);
        }
        return true;
    }

    private void move(Selection selection, Location destination) {
        var rules = TeleportSettings.read(plugin.getConfig());
        if (!BukkitServerThread.isFolia()) {
            safety.resolve(destination, rules).ifPresentOrElse(spot -> moveConfirmed(selection, spot),
                    () -> send(selection.owner, "No safe destination for the selection."));
            return;
        }
        selection.checkingDestination = true;
        long revision = ++selection.moveRevision;
        try {
            safety.resolveRegional(plugin, destination.clone(), rules).whenComplete((result, error) -> {
                var checked = error == null && result != null ? result.destination().orElse(null) : null;
                onEntity(selection.owner, () -> destinationChecked(selection, revision, checked), () -> deferClear(selection));
            });
        } catch (RuntimeException failed) {
            destinationChecked(selection, revision, null);
        }
    }

    private void destinationChecked(Selection selection, long revision, Location destination) {
        if (!current(selection) || selection.moveRevision != revision) return;
        selection.checkingDestination = false;
        if (!connected(selection.owner)) clear(selection);
        else if (destination == null) send(selection.owner, "No safe destination for the selection.");
        else moveConfirmed(selection, destination);
    }

    private void moveConfirmed(Selection selection, Location destination) {
        if (!selections.remove(selection.ownerId, selection)) return;
        boolean petsAllowed = selection.owner.hasPermission(PET_PERMISSION);
        boolean entitiesAllowed = selection.owner.hasPermission(ENTITY_PERMISSION);
        var ids = new LinkedHashSet<>(selection.pets);
        ids.addAll(selection.entities);
        releaseAll(selection);
        List<CompletableFuture<Boolean>> moves = new ArrayList<>();
        for (UUID id : ids) {
            boolean pet = petsAllowed && selection.pets.contains(id);
            boolean entity = entitiesAllowed && selection.entities.contains(id);
            if (!pet && !entity) continue;
            Entity target = plugin.getServer().getEntity(id);
            if (target != null && !(target instanceof Player)) {
                moves.add(moveEntity(target, selection, destination, entity));
            }
        }
        CompletableFuture.allOf(moves.toArray(CompletableFuture[]::new)).thenRun(() -> {
            long moved = moves.stream().filter(job -> job.getNow(false)).count();
            Runnable report = () -> {
                if (!closed && connected(selection.owner)) {
                    send(selection.owner, "Teleported " + moved + " selected companion(s); selection disabled.");
                }
            };
            if (BukkitServerThread.isFolia()) BukkitServerThread.runFor(plugin, plugin.getScheduler(), selection.owner, report, () -> {}, 0);
            else report.run();
        });
    }

    private CompletableFuture<Boolean> moveEntity(Entity entity, Selection selection, Location destination, boolean anyEntity) {
        var result = new CompletableFuture<Boolean>();
        onEntity(entity, () -> {
            try {
                if (closed || plugin.getServer().getPlayer(selection.ownerId) != selection.owner
                        || !entity.isValid() || (!anyEntity && !owned(entity, selection.ownerId))) {
                    result.complete(false);
                } else if (BukkitServerThread.isFolia()) {
                    BukkitServerThread.teleportRegional(entity, destination.clone(), PlayerTeleportEvent.TeleportCause.PLUGIN)
                            .whenComplete((moved, error) -> result.complete(error == null && Boolean.TRUE.equals(moved)));
                } else {
                    result.complete(entity.teleport(destination));
                }
            } catch (RuntimeException failure) {
                result.complete(false);
            }
        }, () -> result.complete(false));
        return result;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || selection.owner != player || event.getTo() == null) return;
        Location destination = event.getTo().clone();
        selection.awaitingArrival = true;
        selection.checkingDestination = false;
        long revision = ++selection.moveRevision;
        BukkitServerThread.runFor(plugin, plugin.getScheduler(), player, () -> {
            if (!current(selection) || selection.moveRevision != revision) return;
            selection.awaitingArrival = false;
            if (!event.isCancelled() && connected(player) && player.getWorld() == destination.getWorld()
                    && player.getLocation().distanceSquared(destination) < 4 && !selection.marks.isEmpty()) {
                move(selection, destination);
            }
        }, () -> deferClear(selection), 50);
    }

    /** Allium's native Folia teleport can complete without emitting PlayerTeleportEvent. */
    public void arrived(Player player) {
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || selection.owner != player || selection.marks.isEmpty() || closed) return;
        selection.awaitingArrival = false;
        move(selection, player.getLocation());
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        Selection selection = selections.get(event.getPlayer().getUniqueId());
        if (selection != null && selection.owner == event.getPlayer()) clear(selection);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void unload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            UUID id = entity.getUniqueId();
            Glow glow = glows.get(id);
            if (glow != null && glow.entity() == entity && glows.remove(id, glow)) entity.setGlowing(glow.original());
        }
    }

    private void updateMarker(Selection selection, UUID id) {
        Marker marker = selection.marks.get(id);
        if (marker == null) return;
        marker.entitySelected = selection.entities.contains(id);
        if (!marker.entitySelected && !selection.pets.contains(id)) {
            selection.marks.remove(id);
            release(marker);
        }
    }

    private void release(Marker marker) {
        onEntity(marker.entity, () -> releaseGlow(marker, true), () -> releaseGlow(marker, false));
    }

    private void releaseGlow(Marker marker, boolean restore) {
        Glow glow = glows.get(marker.id);
        if (glow != null && glow.entity() == marker.entity && glow.markers().remove(marker) && glow.markers().isEmpty()
                && glows.remove(marker.id, glow) && restore && glow.entity().isValid()) {
            glow.entity().setGlowing(glow.original());
        }
    }

    private void clear(Selection selection) {
        if (selections.remove(selection.ownerId, selection)) releaseAll(selection);
    }

    private void deferClear(Selection selection) {
        plugin.getScheduler().runOnServer(() -> clear(selection));
    }

    private void releaseAll(Selection selection) {
        var markers = List.copyOf(selection.marks.values());
        selection.marks.clear();
        for (Marker marker : markers) release(marker);
    }

    private boolean current(Selection selection) {
        return !closed && selections.get(selection.ownerId) == selection;
    }

    private boolean connected(Player player) {
        return plugin.getServer().getPlayer(player.getUniqueId()) == player && player.isOnline();
    }

    private void onEntity(Entity entity, Runnable task, Runnable retired) {
        if (!BukkitServerThread.isFolia()) {
            task.run();
            return;
        }
        var owner = BukkitServerThread.entity(plugin, entity);
        if (owner.isServerThread()) {
            task.run();
            return;
        }
        try {
            if (plugin.getScheduler().runOn(owner, task, retired, 0) == TaskHandle.NONE) retired.run();
        } catch (RuntimeException failed) {
            retired.run();
            plugin.logger().warning("Could not schedule companion entity work: " + failed);
        }
    }

    private void send(CommandSender sender, String text) {
        BukkitServerThread.onOwner(plugin, plugin.getScheduler(), sender,
                () -> plugin.getMessageManager().send(sender, Messages.TELEPORT_SELECTION, "{message}", text));
    }

    @Override
    public void close() {
        closed = true;
        timer.cancel();
        HandlerList.unregisterAll(this);
        for (Selection selection : List.copyOf(selections.values())) clear(selection);
        // Catch glow releases already queued by selections that were consumed before shutdown.
        glows.forEach((id, glow) -> onEntity(glow.entity(), () -> {
            if (glows.remove(id, glow) && glow.entity().isValid()) glow.entity().setGlowing(glow.original());
        }, () -> glows.remove(id, glow)));
    }
}
