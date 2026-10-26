package codes.castled.allium.inventory;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.DB.Database;
import codes.castled.allium.managers.core.Text;
import codes.castled.allium.util.SchedulerAdapter;
import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import java.util.*;
import java.util.concurrent.*;

public class InventoryManager {
    private final PluginStart plugin;
    private final Database database;
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<InventorySnapshot>> playerSnapshots = new ConcurrentHashMap<>();
    private static final int SNAPSHOTS_PER_PAGE = 45; // 5 rows (9 * 5)
    private final ScheduledExecutorService executorService;

    public InventoryManager(PluginStart plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
        this.executorService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "InventorySnapshot-Executor");
            thread.setDaemon(true);
            return thread;
        });
        
        // Schedule periodic cleanup of old snapshots (once per day)
        this.executorService.scheduleAtFixedRate(
            this::purgeExpiredSnapshots,
            1, 24, TimeUnit.HOURS
        );
        
        // Schedule periodic autosave for all online players (every 5 minutes)
        this.executorService.scheduleAtFixedRate(
            this::autosaveOnlinePlayers,
            2, 5, TimeUnit.MINUTES // Start after 2 minutes, then every 5 minutes
        );
        
        createTable();
        // No need to preload all snapshots
    }

    /**
     * How many days a snapshot keeps before it is purged, from
     * {@code restore.snapshot-retention-days} in the config. Read per pass, so
     * a config reload moves the cutoff without one. Zero or less keeps every
     * snapshot for ever: nothing deletes today's restore history.
     */
    public int retentionDays() {
        return Math.max(0, plugin.getConfig().getInt("restore.snapshot-retention-days", 30));
    }

    /** The timestamp snapshots are purged once older than. */
    private long retentionCutoff() {
        return System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays());
    }

    /** The daily purge step; retention off means the pass is a no-op. */
    private void purgeExpiredSnapshots() {
        if (retentionDays() <= 0) {
            return;
        }
        cleanupOldSnapshots(retentionCutoff());
    }

    /**
     * Purges expired snapshots from the database right now. Called by
     * {@code /core purge restoresnapshots}, which wants an answer promptly
     * rather than waiting up to a day for the periodic pass.
     *
     * @return A future completing with the number of snapshots purged, or 0
     *         without a database round-trip when retention is switched off
     */
    public CompletableFuture<Integer> purgeExpiredSnapshotsNow() {
        if (retentionDays() <= 0) {
            return CompletableFuture.completedFuture(0);
        }
        return cleanupOldSnapshots(retentionCutoff());
    }

    /**
     * Periodically saves snapshots for all online players
     */
    private void autosaveOnlinePlayers() {
        Text.sendDebugLog(INFO, "Running periodic autosave for online players...");
        // Run on main thread to get online players
        SchedulerAdapter.run(() -> {
            int playerCount = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                saveSnapshot(player, "AUTOSAVE");
                playerCount++;
            }
            Text.sendDebugLog(INFO, "Autosave completed for " + playerCount + " online players");
        });
    }
    public CompletableFuture<Boolean> saveSnapshot(Player player, String reason) {
        try {
            return saveSnapshot(new InventorySnapshot(player, reason));
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Error creating inventory snapshot for " + player.getName(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Persists a captured snapshot, including an administrator's MANUAL copy. */
    public CompletableFuture<Boolean> saveSnapshot(InventorySnapshot snapshot) {
        return CompletableFuture.supplyAsync(() -> {
            if (!database.saveInventorySnapshot(snapshot)) {
                return false;
            }
            // A cache entry means the complete database history has been loaded.
            // Login/autosave must not create a partial entry hiding older rows.
            playerSnapshots.computeIfPresent(snapshot.getPlayerId(), (id, snapshots) -> {
                snapshots.add(snapshot);
                snapshots.sort(Comparator.comparingLong(InventorySnapshot::getTimestamp).reversed());
                return snapshots;
            });
            return true;
        }, executorService);
    }
    
    private void createTable() {
        // Table creation is now handled by the Database class
    }
    
    /**
     * Gets a page of inventory snapshots for a player
     * @param playerId The UUID of the player
     * @param page The page number (0-based)
     * @return List of snapshots for the requested page
     */
    public CompletableFuture<List<InventorySnapshot>> getPlayerSnapshots(UUID playerId, int page) {
        if (page < 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Page must not be negative"));
        }
        return loadSnapshots(playerId).thenApply(snapshots -> getPaginatedSnapshots(snapshots, page));
    }

    private CompletableFuture<CopyOnWriteArrayList<InventorySnapshot>> loadSnapshots(UUID playerId) {
        CopyOnWriteArrayList<InventorySnapshot> cached = playerSnapshots.get(playerId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        // All database loads, saves and cache mutations share this executor. A
        // queued save cannot be lost between SELECT and publishing the cache.
        return CompletableFuture.supplyAsync(() -> playerSnapshots.computeIfAbsent(playerId,
            id -> new CopyOnWriteArrayList<>(database.getPlayerInventorySnapshots(id))), executorService);
    }

    public CompletableFuture<InventorySnapshot> getLatestSnapshot(UUID playerId) {
        CompletableFuture<InventorySnapshot> future = new CompletableFuture<>();
        getPlayerSnapshots(playerId, 0).whenComplete((snapshots, error) -> {
            if (error != null) {
                future.completeExceptionally(error);
                return;
            }
            future.complete(snapshots.isEmpty() ? null : snapshots.get(0));
        });
        return future;
    }
    
    private List<InventorySnapshot> getPaginatedSnapshots(List<InventorySnapshot> snapshots, int page) {
        // Use one immutable view while a save or retention purge updates the cache.
        snapshots = List.copyOf(snapshots);
        if (snapshots.isEmpty()) {
            return Collections.emptyList();
        }
        
        int fromIndex = page * SNAPSHOTS_PER_PAGE;
        if (fromIndex >= snapshots.size()) {
            return Collections.emptyList();
        }
        
        int toIndex = Math.min(fromIndex + SNAPSHOTS_PER_PAGE, snapshots.size());
        return new ArrayList<>(snapshots.subList(fromIndex, toIndex));
    }
    
    /**
     * Gets the total number of pages of snapshots for a player
     * @param playerId The UUID of the player
     * @return Total number of pages
     */
    public CompletableFuture<Integer> getTotalPages(UUID playerId) {
        return loadSnapshots(playerId).thenApply(snapshots -> calculateTotalPages(snapshots.size()));
    }
    
    private int calculateTotalPages(int totalItems) {
        return (int) Math.ceil((double) totalItems / SNAPSHOTS_PER_PAGE);
    }
    
    /**
     * Deletes an inventory snapshot from the database and cache
     * @param playerId The UUID of the player
     * @param timestamp The timestamp of the snapshot to delete
     * @return true if the snapshot was deleted, false otherwise
     */
    public CompletableFuture<Boolean> deleteSnapshot(UUID playerId, long timestamp) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        executorService.execute(() -> {
            try {
                boolean success = database.deleteInventorySnapshot(timestamp);
                
                if (success) {
                    // Remove from cache
                    CopyOnWriteArrayList<InventorySnapshot> snapshots = playerSnapshots.get(playerId);
                    if (snapshots != null) {
                        snapshots.removeIf(s -> s.getTimestamp() == timestamp);
                    }
                }
                
                future.complete(success);
            } catch (Exception e) {
                Text.sendDebugLog(ERROR, "Error deleting snapshot for player: " + playerId, e);
                future.completeExceptionally(e);
            }
        });
        
        return future;
    }
    
    /**
     * Clean up old snapshots
     * @param olderThan Timestamp in milliseconds
     * @return Number of snapshots deleted
     */
    public CompletableFuture<Integer> cleanupOldSnapshots(long olderThan) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        
        executorService.execute(() -> {
            try {
                int deleted = database.cleanupOldSnapshots(olderThan);
                
                // Update cache by removing deleted snapshots
                for (Map.Entry<UUID, CopyOnWriteArrayList<InventorySnapshot>> entry : playerSnapshots.entrySet()) {
                    entry.getValue().removeIf(snapshot -> snapshot.getTimestamp() < olderThan);
                }
                
                future.complete(deleted);
            } catch (Exception e) {
                Text.sendDebugLog(ERROR, "Error cleaning up old snapshots", e);
                future.completeExceptionally(e);
            }
        });
        
        return future;
    }
    
    /**
     * Shutdown the executor service when the plugin disables
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
