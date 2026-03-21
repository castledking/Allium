package net.survivalfun.core.inventory;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.*;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

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
            () -> cleanupOldSnapshots(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)), // 30 days retention
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
     * Periodically saves snapshots for all online players
     */
    private void autosaveOnlinePlayers() {
        Text.sendDebugLog(INFO, "Running periodic autosave for online players...");
        // Run on main thread to get online players
        Bukkit.getScheduler().runTask(plugin, () -> {
            int playerCount = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                saveSnapshot(player, "AUTOSAVE");
                playerCount++;
            }
            Text.sendDebugLog(INFO, "Autosave completed for " + playerCount + " online players");
        });
    }
    public CompletableFuture<Boolean> saveSnapshot(Player player, String reason) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        // Run snapshot creation on the appropriate scheduler
        SchedulerAdapter.run(() -> {
            try {
                InventorySnapshot snapshot = new InventorySnapshot(player, reason);
                
                // Run database operation async
                executorService.execute(() -> {
                    try {
                        if (database.saveInventorySnapshot(snapshot)) {
                            // Cache the snapshot on success
                            playerSnapshots.computeIfAbsent(
                                player.getUniqueId(), 
                                k -> new CopyOnWriteArrayList<>()
                            ).add(0, snapshot);
                            future.complete(true);
                        } else {
                            Text.sendDebugLog(WARN, "Failed to save inventory snapshot for " + player.getName());
                            future.complete(false);
                        }
                    } catch (Exception e) {
                        Text.sendDebugLog(ERROR, "Error saving inventory snapshot for " + player.getName(), e);
                        future.completeExceptionally(e);
                    }
                });
            } catch (Exception e) {
                Text.sendDebugLog(ERROR, "Error creating inventory snapshot for " + player.getName(), e);
                future.completeExceptionally(e);
            }
        });
        
        return future;
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
        CompletableFuture<List<InventorySnapshot>> future = new CompletableFuture<>();
        
        // Check cache first
        CopyOnWriteArrayList<InventorySnapshot> cached = playerSnapshots.get(playerId);
        if (cached != null && !cached.isEmpty()) {
            future.complete(getPaginatedSnapshots(cached, page));
            return future;
        }
        
        // Load from database async
        executorService.execute(() -> {
            try {
                List<InventorySnapshot> loaded = database.getPlayerInventorySnapshots(playerId);
                CopyOnWriteArrayList<InventorySnapshot> snapshots = new CopyOnWriteArrayList<>(loaded);
                playerSnapshots.put(playerId, snapshots);
                future.complete(getPaginatedSnapshots(snapshots, page));
            } catch (Exception e) {
                Text.sendDebugLog(ERROR, "Error loading snapshots for player: " + playerId, e);
                future.completeExceptionally(e);
            }
        });
        
        return future;
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
        CompletableFuture<Integer> future = new CompletableFuture<>();
        
        // Check cache first
        CopyOnWriteArrayList<InventorySnapshot> cached = playerSnapshots.get(playerId);
        if (cached != null) {
            future.complete(calculateTotalPages(cached.size()));
            return future;
        }
        
        // Load from database if not in cache
        getPlayerSnapshots(playerId, 0).whenComplete((snapshots, error) -> {
            if (error != null) {
                future.completeExceptionally(error);
                return;
            }
            future.complete(calculateTotalPages(
                playerSnapshots.getOrDefault(playerId, new CopyOnWriteArrayList<>()).size()
            ));
        });
        
        return future;
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
