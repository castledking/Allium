package codes.castled.allium.legacy;

import codes.castled.allium.ServerVersion;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/**
 * Java 8 / 1.12.2-1.19.4 companion & mob selection teleportation ({@code /tppet}, {@code /tpent} / {@code /tpmob}).
 *
 * <p>Uses a persistent yellow dust particle indicator above selected mobs on 1.12.2 (and as a fallback
 * if entity glowing is unavailable on 1.13-1.19.4), restoring any original glow state on release.
 */
final class LegacyCompanionTeleports implements Listener {
    private static final String PET_PERMISSION = "allium.tppet";
    private static final String ENTITY_PERMISSION = "allium.tpmob";
    private static final long HIGHLIGHT_PERIOD_TICKS = 5L;

    private static final class Selection {
        final Player owner;
        final UUID ownerId;
        final Location start;
        final Set<UUID> pets = new LinkedHashSet<UUID>();
        final Set<UUID> entities = new LinkedHashSet<UUID>();
        final Map<UUID, Entity> tracked = new HashMap<UUID, Entity>();
        boolean petMode;
        boolean entityMode;
        boolean awaitingArrival;

        Selection(Player owner) {
            this.owner = owner;
            this.ownerId = owner.getUniqueId();
            Location loc = owner.getLocation();
            this.start = loc == null ? null : loc.clone();
        }

        boolean empty() {
            return pets.isEmpty() && entities.isEmpty();
        }
    }

    private static final class GlowState {
        final Entity entity;
        final boolean original;
        final Set<UUID> owners = new HashSet<UUID>();

        GlowState(Entity entity, boolean original) {
            this.entity = entity;
            this.original = original;
        }
    }

    private final JavaPlugin plugin;
    private final LegacyConfigManager config;
    private final LegacyMessageManager messages;
    private final boolean pre113;
    private final BiFunction<CommandSender, String, Player> playerFinder;
    private final BiPredicate<CommandSender, Player> canSee;

    private final Map<UUID, Selection> selections = new HashMap<UUID, Selection>();
    private final Map<UUID, GlowState> glows = new HashMap<UUID, GlowState>();
    private BukkitTask highlightTask;
    private boolean closed;

    LegacyCompanionTeleports(
            JavaPlugin plugin,
            LegacyConfigManager config,
            LegacyMessageManager messages,
            ServerVersion version,
            BiFunction<CommandSender, String, Player> playerFinder,
            BiPredicate<CommandSender, Player> canSee
    ) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.pre113 = version != null && !version.isAtLeast(1, 13, 0);
        this.playerFinder = playerFinder;
        this.canSee = canSee;
    }

    boolean execute(CommandSender sender, boolean pets, String[] args) {
        String permission = pets ? PET_PERMISSION : ENTITY_PERMISSION;
        if (!sender.hasPermission(permission)) {
            messages.send(sender, "no-permission", "{prefix} &cYou don't have permission to use &f/{cmd}&c.",
                    "{cmd}", pets ? "tppet" : "tpent");
            return true;
        }
        if (args.length > 1) return false;
        Player target;
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                messages.send(sender, "player-only", "{prefix} &cThis command can only be run by a player.");
                return true;
            }
            target = (Player) sender;
        } else {
            boolean self = sender instanceof Player && sender.getName().equalsIgnoreCase(args[0]);
            if (!self && !sender.hasPermission(permission + ".others")) {
                messages.send(sender, "no-permission", "{prefix} &cYou don't have permission to use &f/{cmd}&c.",
                        "{cmd}", (pets ? "tppet" : "tpent") + " for others");
                return true;
            }
            target = self ? (Player) sender : playerFinder.apply(sender, args[0]);
            if (target == null || !canSee.test(sender, target)) {
                messages.send(sender, "player-not-found", "{prefix} &cPlayer &f{player} &cwas not found.",
                        "{player}", args[0]);
                return true;
            }
        }
        if (!target.hasPermission(permission)) {
            messages.send(sender, "no-permission", "{prefix} &cYou don't have permission to use &f/{cmd}&c.",
                    "{cmd}", pets ? "tppet" : "tpent");
            return true;
        }
        toggle(sender, target, pets);
        return true;
    }

    List<String> complete(CommandSender sender, boolean pets, String prefix) {
        String permission = pets ? PET_PERMISSION : ENTITY_PERMISSION;
        if (!sender.hasPermission(permission)) return Collections.emptyList();
        boolean others = sender.hasPermission(permission + ".others");
        List<String> out = new ArrayList<String>();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online == null || !canSee.test(sender, online)) continue;
            if (!others && !online.equals(sender)) continue;
            String name = online.getName();
            if (name != null && name.regionMatches(true, 0, prefix, 0, prefix.length())) {
                out.add(name);
            }
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private void toggle(CommandSender actor, Player target, boolean pets) {
        UUID id = target.getUniqueId();
        Selection selection = selections.get(id);
        if (selection == null || selection.owner != target) {
            if (selection != null) clear(selection);
            selection = new Selection(target);
            selections.put(id, selection);
        }
        boolean wasEnabled = pets ? selection.petMode : selection.entityMode;
        if (pets) selection.petMode = !wasEnabled;
        else selection.entityMode = !wasEnabled;

        if (wasEnabled) {
            Set<UUID> bucket = pets ? selection.pets : selection.entities;
            List<UUID> removed = new ArrayList<UUID>(bucket);
            bucket.clear();
            for (UUID entityId : removed) {
                releaseIfUnused(selection, entityId);
            }
        }
        sendSelection(target, (pets ? "Pet" : "Entity") + " selection "
                + (wasEnabled ? "disabled." : "enabled. Right-click to select."));
        if (!target.equals(actor)) {
            sendSelection(actor, "Selection mode updated for " + target.getName() + ".");
        }
        if (!selection.petMode && !selection.entityMode) {
            clear(selection);
        } else {
            ensureTicker();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player owner = event.getPlayer();
        Selection selection = selections.get(owner.getUniqueId());
        Entity entity = event.getRightClicked();
        if (selection == null || selection.owner != owner || entity == null || entity instanceof Player || !entity.isValid()) {
            return;
        }
        boolean selectPet = selection.petMode && isOwnedPet(entity, selection.ownerId);
        boolean selectEntity = selection.entityMode;
        if (!selectPet && !selectEntity) return;

        boolean asPet = selectPet;
        String permission = asPet ? PET_PERMISSION : ENTITY_PERMISSION;
        if (!owner.hasPermission(permission)) return;

        event.setCancelled(true);
        Set<UUID> bucket = asPet ? selection.pets : selection.entities;
        UUID entityId = entity.getUniqueId();
        if (bucket.remove(entityId)) {
            releaseIfUnused(selection, entityId);
            sendSelection(owner, "Deselected " + entity.getType().name() + ".");
            return;
        }
        int limit = maxSelection(owner, asPet);
        if (bucket.size() >= limit) {
            sendSelection(owner, "Selection limit: " + limit);
            return;
        }
        bucket.add(entityId);
        selection.tracked.put(entityId, entity);
        applyHighlight(owner, entity);
        ensureTicker();
        sendSelection(owner, "Selected " + entity.getType().name() + ".");
    }

    /** Moves any selected companions to an explicit {@code /tphere} or {@code /there} destination. */
    boolean move(Player player, Location destination) {
        if (closed || player == null || destination == null) return false;
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || selection.owner != player || selection.empty()) return false;
        selection.awaitingArrival = false;
        moveSelection(selection, destination);
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        final Player player = event.getPlayer();
        final Selection selection = selections.get(player.getUniqueId());
        if (selection == null || selection.owner != player || selection.empty() || event.getTo() == null) return;
        final Location destination = event.getTo().clone();
        selection.awaitingArrival = true;
        plugin.getServer().getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (closed || selections.get(selection.ownerId) != selection) return;
                selection.awaitingArrival = false;
                if (!player.isOnline() || selection.empty()) {
                    clear(selection);
                    return;
                }
                Location current = player.getLocation();
                if (current != null && current.getWorld() != null && current.getWorld().equals(destination.getWorld())
                        && current.distanceSquared(destination) < 16.0) {
                    moveSelection(selection, current);
                }
            }
        }, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Selection selection = selections.get(event.getPlayer().getUniqueId());
        if (selection != null && selection.owner == event.getPlayer()) {
            clear(selection);
        }
    }

    private void moveSelection(Selection selection, Location destination) {
        if (selections.remove(selection.ownerId) != selection) return;
        boolean petsAllowed = selection.owner.hasPermission(PET_PERMISSION);
        boolean entitiesAllowed = selection.owner.hasPermission(ENTITY_PERMISSION);
        Set<UUID> petSnapshot = new HashSet<UUID>(selection.pets);
        Set<UUID> entitySnapshot = new HashSet<UUID>(selection.entities);
        Set<UUID> ordered = new LinkedHashSet<UUID>(petSnapshot);
        ordered.addAll(entitySnapshot);
        Map<UUID, Entity> snapshot = new HashMap<UUID, Entity>(selection.tracked);
        releaseAll(selection);

        int moved = 0;
        for (UUID entityId : ordered) {
            boolean pet = petsAllowed && petSnapshot.contains(entityId);
            boolean anyEntity = entitiesAllowed && entitySnapshot.contains(entityId);
            if (!pet && !anyEntity) continue;
            Entity target = resolveEntity(snapshot.get(entityId), entityId);
            if (target == null || target instanceof Player || !target.isValid()) continue;
            if (!anyEntity && !isOwnedPet(target, selection.ownerId)) continue;
            try {
                if (target.teleport(destination.clone())) {
                    moved++;
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (selection.owner.isOnline()) {
            sendSelection(selection.owner, "Teleported " + moved + " selected companion(s); selection disabled.");
        }
        stopTickerIfIdle();
    }

    private void tickHighlightsAndExpiry() {
        if (closed || selections.isEmpty()) {
            stopTickerIfIdle();
            return;
        }
        List<Selection> active = new ArrayList<Selection>(selections.values());
        for (Selection selection : active) {
            Player owner = selection.owner;
            if (!owner.isOnline()) {
                clear(selection);
                continue;
            }
            if (!selection.awaitingArrival && !owner.hasPermission("allium.admin") && selection.start != null) {
                Location loc = owner.getLocation();
                if (loc == null || loc.getWorld() == null || !loc.getWorld().equals(selection.start.getWorld())
                        || loc.distanceSquared(selection.start) > 900.0) {
                    clear(selection);
                    continue;
                }
            }
            Iterator<Map.Entry<UUID, Entity>> it = selection.tracked.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Entity> entry = it.next();
                Entity entity = resolveEntity(entry.getValue(), entry.getKey());
                if (entity == null || !entity.isValid()) {
                    UUID entityId = entry.getKey();
                    it.remove();
                    selection.pets.remove(entityId);
                    selection.entities.remove(entityId);
                    releaseGlow(selection.ownerId, entityId);
                    continue;
                }
                entry.setValue(entity);
                if (useParticleHighlight(entity)) {
                    LegacyCompat.spawnYellowSelectionDust(owner, entity, pre113);
                }
            }
        }
        stopTickerIfIdle();
    }

    private void applyHighlight(Player owner, Entity entity) {
        if (useParticleHighlight(entity)) {
            LegacyCompat.spawnYellowSelectionDust(owner, entity, pre113);
            return;
        }
        UUID entityId = entity.getUniqueId();
        GlowState state = glows.get(entityId);
        if (state == null || state.entity != entity) {
            boolean original = false;
            try {
                original = entity.isGlowing();
            } catch (Throwable ignored) {
            }
            state = new GlowState(entity, original);
            glows.put(entityId, state);
        }
        state.owners.add(owner.getUniqueId());
        try {
            entity.setGlowing(true);
        } catch (Throwable ignored) {
            // Fall back immediately to yellow dust particles if glowing fails on this server build.
            glows.remove(entityId);
            LegacyCompat.spawnYellowSelectionDust(owner, entity, pre113);
        }
    }

    private boolean useParticleHighlight(Entity entity) {
        if (pre113) return true;
        return !glows.containsKey(entity.getUniqueId()) && !isGlowingSafe(entity);
    }

    private static boolean isGlowingSafe(Entity entity) {
        try {
            return entity.isGlowing();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void releaseIfUnused(Selection selection, UUID entityId) {
        if (!selection.pets.contains(entityId) && !selection.entities.contains(entityId)) {
            selection.tracked.remove(entityId);
            releaseGlow(selection.ownerId, entityId);
        }
    }

    private void releaseGlow(UUID ownerId, UUID entityId) {
        GlowState state = glows.get(entityId);
        if (state == null) return;
        state.owners.remove(ownerId);
        if (state.owners.isEmpty()) {
            glows.remove(entityId);
            try {
                if (state.entity.isValid()) {
                    state.entity.setGlowing(state.original);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void clear(Selection selection) {
        if (selections.remove(selection.ownerId) == selection) {
            releaseAll(selection);
            stopTickerIfIdle();
        }
    }

    private void releaseAll(Selection selection) {
        List<UUID> ids = new ArrayList<UUID>(selection.tracked.keySet());
        selection.pets.clear();
        selection.entities.clear();
        selection.tracked.clear();
        for (UUID entityId : ids) {
            releaseGlow(selection.ownerId, entityId);
        }
    }

    private void ensureTicker() {
        if (closed || highlightTask != null || selections.isEmpty()) return;
        highlightTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                new Runnable() {
                    @Override
                    public void run() {
                        tickHighlightsAndExpiry();
                    }
                },
                HIGHLIGHT_PERIOD_TICKS,
                HIGHLIGHT_PERIOD_TICKS
        );
    }

    private void stopTickerIfIdle() {
        if (selections.isEmpty() && highlightTask != null) {
            highlightTask.cancel();
            highlightTask = null;
        }
    }

    int maxSelection(Player owner, boolean pets) {
        int configuredMax = pets
                ? Math.max(1, config.raw().getInt("teleport.tppet.max-entities", 10))
                : Math.max(1, config.raw().getInt("teleport.tpe.max-entities", 20));
        String permission = pets ? PET_PERMISSION : ENTITY_PERMISSION;
        if (owner.isOp() || owner.hasPermission("allium.admin") || owner.hasPermission(permission + ".*")) {
            return configuredMax;
        }
        String prefix = permission + ".";
        int highest = 0;
        Set<PermissionAttachmentInfo> effective = owner.getEffectivePermissions();
        if (effective != null) {
            for (PermissionAttachmentInfo info : effective) {
                if (!info.getValue()) continue;
                String node = info.getPermission().toLowerCase(Locale.ROOT);
                if (!node.startsWith(prefix)) continue;
                String suffix = node.substring(prefix.length());
                if (suffix.matches("[0-9]{1,4}")) {
                    int value = Integer.parseInt(suffix);
                    if (value > highest && owner.hasPermission(info.getPermission())) {
                        highest = value;
                    }
                }
            }
        }
        if (highest == 0) {
            int probe = Math.min(configuredMax, 100);
            for (int i = 1; i <= probe; i++) {
                if (owner.hasPermission(prefix + i)) highest = i;
            }
        }
        return highest > 0 ? Math.min(highest, configuredMax) : configuredMax;
    }

    private Entity resolveEntity(Entity cached, UUID id) {
        if (cached != null && cached.isValid()) return cached;
        try {
            Entity live = plugin.getServer().getEntity(id);
            if (live != null && live.isValid()) return live;
        } catch (Throwable ignored) {
        }
        return cached;
    }

    private static boolean isOwnedPet(Entity entity, UUID ownerId) {
        if (!(entity instanceof Tameable)) return false;
        Tameable tameable = (Tameable) entity;
        if (!tameable.isTamed()) return false;
        AnimalTamer tamer = tameable.getOwner();
        return tamer != null && ownerId.equals(tamer.getUniqueId());
    }

    private void sendSelection(CommandSender recipient, String text) {
        messages.send(recipient, "teleport.selection", "{prefix} &e{message}", "{message}", text);
    }

    void stop() {
        closed = true;
        HandlerList.unregisterAll(this);
        if (highlightTask != null) {
            highlightTask.cancel();
            highlightTask = null;
        }
        for (Selection selection : new ArrayList<Selection>(selections.values())) {
            releaseAll(selection);
        }
        selections.clear();
        for (GlowState state : new ArrayList<GlowState>(glows.values())) {
            try {
                if (state.entity.isValid()) {
                    state.entity.setGlowing(state.original);
                }
            } catch (Throwable ignored) {
            }
        }
        glows.clear();
    }
}
