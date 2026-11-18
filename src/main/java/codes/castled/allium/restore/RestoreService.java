package codes.castled.allium.restore;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.item.SlotCodec;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.ModerationModule;
import codes.castled.allium.module.SignsModule;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Inventory snapshots and restores (legacy Allium's {@code /restore}, rebuilt on Allium's item
 * codec and storage).
 *
 * <p>Snapshots are taken on death, login, logout, world change, gamemode change (before the
 * creative manager swaps inventories) and on a timer. A restore <em>replaces</em> the chosen parts
 * of a player's inventory. For an offline player it waits in storage and is applied on their next
 * join; the applied restore's ID is saved in the player's own data together with the restored
 * items, so a crash between applying and clearing the queue never applies it twice.
 */
public final class RestoreService implements Listener {

    public static final String PERMISSION = "allium.restore";
    public static final String PERMISSION_OTHERS = "allium.restore.others";
    public static final String PERMISSION_EDIT = "allium.restore.edit";

    /** The parts of a snapshot a restore can replace. */
    public enum Part {
        INVENTORY("inventory"), ARMOR("armor and offhand"), EXPERIENCE("experience"), ENDER_CHEST("ender chest");

        private final String label;

        Part(String label) {
            this.label = label;
        }

        public static String describe(Set<Part> parts) {
            List<String> names = new ArrayList<>();
            for (Part part : parts) names.add(part.label);
            return String.join(", ", names);
        }
    }

    /** A decoded snapshot: slots 0-40 (storage, armor, offhand) and the 27 ender chest slots. */
    public record Contents(ItemStack[] inventory, ItemStack[] enderChest, int level, float progress) {
    }

    record Settings(Set<String> triggers, long autosaveMillis, long retentionMillis, int maxPerPlayer) {
        static Settings read(@Nullable ConfigurationSection c) {
            Set<String> triggers = new LinkedHashSet<>();
            List<String> configured = c == null ? List.of() : c.getStringList("snapshot-on");
            for (String raw : configured.isEmpty() ? List.of("death", "login", "logout", "world-change", "gamemode-change", "autosave")
                    : configured) {
                triggers.add(raw.toLowerCase(Locale.ROOT));
            }
            long autosave = Math.max(1, c == null ? 10 : c.getInt("autosave-minutes", 10)) * 60_000L;
            long retention = Math.max(0, c == null ? 14 : c.getInt("retention-days", 14)) * 86_400_000L;
            int max = Math.max(1, Math.min(500, c == null ? 50 : c.getInt("max-snapshots-per-player", 50)));
            return new Settings(Set.copyOf(triggers), autosave, retention, max);
        }
    }

    private final PluginStart plugin;
    private final SnapshotRepository repository;
    private final Executor io;
    private final SlotCodec slots = new SlotCodec();
    private final NamespacedKey receiptKey;
    private volatile Settings settings;
    private TaskHandle autosave;
    private volatile boolean closed;

    public RestoreService(PluginStart plugin, SnapshotRepository repository, Executor io) {
        this.plugin = plugin;
        this.repository = repository;
        this.io = io;
        this.receiptKey = new NamespacedKey(plugin, "restore_receipts");
        this.settings = Settings.read(plugin.getConfig().getConfigurationSection("restore"));
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        scheduleAutosave();
        purge();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            applyPending(online);
        }
    }

    public void disable() {
        closed = true;
        HandlerList.unregisterAll(this);
        if (autosave != null) autosave.cancel();
    }

    public void reload() {
        settings = Settings.read(plugin.getConfig().getConfigurationSection("restore"));
        if (autosave != null) autosave.cancel();
        scheduleAutosave();
    }

    public SnapshotRepository repository() {
        return repository;
    }

    private void scheduleAutosave() {
        long period = settings.autosaveMillis();
        autosave = plugin.getScheduler().repeatOnServer(() -> {
            if (closed) return false;
            if (settings.triggers().contains("autosave")) {
                for (Player player : plugin.getServer().getOnlinePlayers()) snapshot(player, "AUTOSAVE", "Allium");
            }
            return true;
        }, period, period);
    }

    private void purge() {
        long retention = settings.retentionMillis();
        if (retention <= 0) return;
        submit(() -> {
            try {
                int purged = repository.purge(System.currentTimeMillis() - retention);
                if (purged > 0) plugin.logger().info("Purged " + purged + " inventory snapshot(s) older than the retention limit.");
            } catch (SQLException e) {
                plugin.logger().warning("Could not purge old inventory snapshots: " + e.getMessage());
            }
        });
    }

    // -- Taking snapshots --------------------------------------------------------------------------

    /** Captures {@code player} now (server thread) and stores the snapshot off-thread. */
    public void snapshot(Player player, String reason, String createdBy) {
        SnapshotRepository.Snapshot snapshot;
        try {
            snapshot = capture(player, reason, createdBy);
        } catch (RuntimeException failure) {
            plugin.logger().warning("Could not capture " + player.getName() + "'s inventory snapshot: " + failure.getMessage());
            return;
        }
        save(snapshot);
    }

    public CompletableFuture<Void> save(SnapshotRepository.Snapshot snapshot) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        int keep = settings.maxPerPlayer();
        if (!submit(() -> {
            try {
                repository.save(snapshot, keep);
                done.complete(null);
            } catch (SQLException e) {
                plugin.logger().warning("Could not save an inventory snapshot: " + e.getMessage());
                done.completeExceptionally(e);
            }
        })) {
            done.completeExceptionally(new IllegalStateException("the snapshot queue is full"));
        }
        return done;
    }

    public SnapshotRepository.Snapshot capture(Player player, String reason, String createdBy) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = new ItemStack[41];
        for (int slot = 0; slot < 41; slot++) contents[slot] = cloneOrNull(inventory.getItem(slot));
        Location at = player.getLocation();
        return new SnapshotRepository.Snapshot(UUID.randomUUID(), player.getUniqueId(), System.currentTimeMillis(), reason,
                at.getWorld() == null ? "unknown" : at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(),
                player.getLevel(), player.getExp(), slots.encode(contents), slots.encode(player.getEnderChest().getContents()), createdBy);
    }

    /** A snapshot built from edited contents (a MANUAL save of a restore panel). */
    public SnapshotRepository.Snapshot manual(SnapshotRepository.Snapshot source, Contents edited, String createdBy) {
        return new SnapshotRepository.Snapshot(UUID.randomUUID(), source.player(), System.currentTimeMillis(), "MANUAL", source.world(),
                source.x(), source.y(), source.z(), source.yaw(), source.pitch(), edited.level(), edited.progress(),
                slots.encode(edited.inventory()), slots.encode(edited.enderChest()), createdBy);
    }

    /** Decodes {@code snapshot}; server thread. Throws for a newer data version or damaged data. */
    public Contents decode(SnapshotRepository.Snapshot snapshot) throws IOException {
        return new Contents(slots.decode(snapshot.inventory(), 41), slots.decode(snapshot.enderChest(), 27), snapshot.level(),
                snapshot.progress());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (settings.triggers().contains("death")) snapshot(event.getEntity(), "DEATH", "Allium");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (settings.triggers().contains("login")) snapshot(event.getPlayer(), "LOGIN", "Allium");
        applyPending(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (settings.triggers().contains("logout")) snapshot(event.getPlayer(), "LOGOUT", "Allium");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (settings.triggers().contains("world-change")) {
            snapshot(event.getPlayer(), "WORLD CHANGE: " + event.getPlayer().getWorld().getName(), "Allium");
        }
    }

    /** LOWEST: before the creative manager swaps inventories, so the outgoing inventory is kept. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGamemodeChange(PlayerGameModeChangeEvent event) {
        if (settings.triggers().contains("gamemode-change") && event.getPlayer().isOnline()) {
            snapshot(event.getPlayer(), "GAMEMODE: " + event.getPlayer().getGameMode().name().toLowerCase(Locale.ROOT) + " to "
                    + event.getNewGameMode().name().toLowerCase(Locale.ROOT), "Allium");
        }
    }

    // -- Restoring ---------------------------------------------------------------------------------

    /** Why {@code target}'s inventory cannot be replaced right now, or null. */
    public boolean busy(Player target) {
        if (UtilitySupport.isInventoryBusy(plugin, target)) return true;
        if (plugin.getModuleManager().getModule("signs") instanceof SignsModule signs && signs.isEnabled()
                && signs.paidRepairs() != null && signs.paidRepairs().busy(target)) return true;
        return plugin.getModuleManager().getModule("moderation") instanceof ModerationModule moderation && moderation.isEnabled()
                && moderation.inspection() != null && moderation.inspection().editing(target.getUniqueId());
    }

    /**
     * Replaces {@code parts} of online {@code target}'s inventory with {@code contents} and saves
     * them. Server thread. False (and nothing changed) when the target is busy.
     */
    public boolean apply(Player target, Contents contents, Set<Part> parts) {
        if (busy(target)) return false;
        // Items in a crafting grid or on the cursor return to the inventory before it is replaced.
        target.closeInventory();
        PlayerInventory inventory = target.getInventory();
        if (parts.contains(Part.INVENTORY)) {
            for (int slot = 0; slot < 36; slot++) inventory.setItem(slot, cloneOrNull(contents.inventory()[slot]));
        }
        if (parts.contains(Part.ARMOR)) {
            for (int slot = 36; slot <= 40; slot++) inventory.setItem(slot, cloneOrNull(contents.inventory()[slot]));
        }
        if (parts.contains(Part.EXPERIENCE)) {
            target.setLevel(Math.max(0, contents.level()));
            target.setExp(Math.max(0f, Math.min(0.9999f, contents.progress())));
        }
        if (parts.contains(Part.ENDER_CHEST)) {
            for (int slot = 0; slot < 27; slot++) target.getEnderChest().setItem(slot, cloneOrNull(contents.enderChest()[slot]));
        }
        target.updateInventory();
        try {
            target.saveData();
        } catch (RuntimeException failure) {
            plugin.logger().warning("Restored " + target.getName() + " but could not save them yet: " + failure.getMessage());
        }
        return true;
    }

    /** Queues a restore of {@code parts} for offline {@code target}'s next join. */
    public CompletableFuture<Void> queue(UUID target, SnapshotRepository.Snapshot snapshot, Set<Part> parts, String queuedBy) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        SnapshotRepository.Pending pending = new SnapshotRepository.Pending(UUID.randomUUID(), target, snapshot,
                parts.contains(Part.INVENTORY), parts.contains(Part.ARMOR), parts.contains(Part.EXPERIENCE),
                parts.contains(Part.ENDER_CHEST), queuedBy, System.currentTimeMillis());
        if (!submit(() -> {
            try {
                repository.queue(pending);
                done.complete(null);
            } catch (SQLException e) {
                done.completeExceptionally(e);
            }
        })) {
            done.completeExceptionally(new IllegalStateException("the restore queue is full"));
        }
        return done;
    }

    /**
     * Applies restores queued while {@code player} was away. Each applied ID is recorded in the
     * player's own data and saved with the restored items before the queue row is removed; a row
     * whose ID the player already carries is only removed.
     */
    private void applyPending(Player player) {
        UUID id = player.getUniqueId();
        submit(() -> {
            List<SnapshotRepository.Pending> pending;
            try {
                pending = repository.pending(id);
            } catch (SQLException e) {
                plugin.logger().warning("Could not read " + player.getName() + "'s queued restores: " + e.getMessage());
                return;
            }
            if (pending.isEmpty()) {
                plugin.getScheduler().runOnServer(() -> clearReceipts(player, Set.of()));
                return;
            }
            plugin.getScheduler().runOnServer(() -> {
                if (closed || plugin.getServer().getPlayer(id) != player) return;
                Set<String> applied = receipts(player);
                List<UUID> finished = new ArrayList<>();
                for (SnapshotRepository.Pending row : pending) {
                    if (applied.contains(row.id().toString())) {
                        finished.add(row.id());
                        continue;
                    }
                    Set<Part> parts = EnumSet.noneOf(Part.class);
                    if (row.inventory()) parts.add(Part.INVENTORY);
                    if (row.armor()) parts.add(Part.ARMOR);
                    if (row.experience()) parts.add(Part.EXPERIENCE);
                    if (row.enderChest()) parts.add(Part.ENDER_CHEST);
                    Contents contents;
                    try {
                        contents = decode(row.snapshot());
                    } catch (IOException unreadable) {
                        plugin.logger().warning("Queued restore " + row.id() + " for " + player.getName()
                                + " cannot be read here and stays queued: " + unreadable.getMessage());
                        continue;
                    }
                    if (busy(player)) {
                        plugin.logger().warning("Queued restore " + row.id() + " for " + player.getName() + " waits: inventory busy.");
                        continue;
                    }
                    applied.add(row.id().toString());
                    setReceipts(player, applied);
                    apply(player, contents, parts);
                    finished.add(row.id());
                    plugin.getMessageManager().send(player, Messages.RESTORE_PENDING_APPLIED, "{parts}", Part.describe(parts));
                    plugin.logger().info("[RestoreAudit] applied queued restore " + row.id() + " (" + Part.describe(parts) + ") to "
                            + player.getName() + ", queued by " + row.queuedBy());
                }
                if (finished.isEmpty()) return;
                submit(() -> {
                    for (UUID done : finished) {
                        try {
                            repository.finish(done);
                        } catch (SQLException e) {
                            plugin.logger().warning("Queued restore " + done + " is applied; removing it from the queue failed and "
                                    + "is retried on the next join: " + e.getMessage());
                            return;
                        }
                    }
                    plugin.getScheduler().runOnServer(() -> clearReceipts(player, Set.copyOf(finished.stream().map(UUID::toString).toList())));
                });
            });
        });
    }

    private Set<String> receipts(Player player) {
        String raw = player.getPersistentDataContainer().get(receiptKey, PersistentDataType.STRING);
        Set<String> out = new LinkedHashSet<>();
        if (raw != null && !raw.isEmpty()) {
            for (String part : raw.split(",")) out.add(part);
        }
        return out;
    }

    private void setReceipts(Player player, Set<String> ids) {
        if (ids.isEmpty()) player.getPersistentDataContainer().remove(receiptKey);
        else player.getPersistentDataContainer().set(receiptKey, PersistentDataType.STRING, String.join(",", ids));
    }

    /** Drops receipts of finished rows (all of them when the queue is empty). */
    private void clearReceipts(Player player, Set<String> finished) {
        if (closed || !player.isOnline()) return;
        Set<String> current = receipts(player);
        if (current.isEmpty()) return;
        if (finished.isEmpty()) current.clear();
        else current.removeAll(finished);
        setReceipts(player, current);
    }

    // -- Helpers -----------------------------------------------------------------------------------

    public static String formatTime(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(millis));
    }

    public void execute(Runnable task, CompletableFuture<?> onRejected) {
        if (!submit(task)) onRejected.completeExceptionally(new IllegalStateException("the restore queue is full"));
    }

    private boolean submit(Runnable task) {
        try {
            io.execute(task);
            return true;
        } catch (RejectedExecutionException full) {
            plugin.logger().warning("Restore storage queue is full; a snapshot task was skipped.");
            return false;
        }
    }

    @Nullable
    private static ItemStack cloneOrNull(@Nullable ItemStack stack) {
        return stack == null || stack.getType().isAir() ? null : stack.clone();
    }

    static long days(int days) {
        return TimeUnit.DAYS.toMillis(days);
    }
}
