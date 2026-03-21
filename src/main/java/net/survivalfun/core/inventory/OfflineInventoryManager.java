package net.survivalfun.core.inventory;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.ERROR;

public class OfflineInventoryManager implements Listener {
    private final PluginStart plugin;
    private final Database database;
    private final InventoryManager inventoryManager;

    public OfflineInventoryManager(PluginStart plugin, InventoryManager inventoryManager) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
        this.inventoryManager = inventoryManager;
    }

    public CompletableFuture<OfflineInventoryData> getEffectiveInventory(UUID playerId) {
        Player onlinePlayer = Bukkit.getPlayer(playerId);
        if (onlinePlayer != null) {
            return CompletableFuture.completedFuture(OfflineInventoryData.fromPlayer(onlinePlayer));
        }

        CompletableFuture<OfflineInventoryData> future = new CompletableFuture<>();
        SchedulerAdapter.runAsync(() -> {
            try {
                OfflineInventoryData stored = database.loadOfflineInventoryState(playerId);
                if (stored != null) {
                    future.complete(stored);
                    return;
                }

                inventoryManager.getLatestSnapshot(playerId).whenComplete((snapshot, error) -> {
                    if (error != null) {
                        future.completeExceptionally(error);
                        return;
                    }
                    if (snapshot != null) {
                        future.complete(OfflineInventoryData.fromSnapshot(snapshot, true, true, true, true));
                    } else {
                        future.complete(new OfflineInventoryData(new org.bukkit.inventory.ItemStack[36], new org.bukkit.inventory.ItemStack[4], null, new org.bukkit.inventory.ItemStack[27], 0));
                    }
                });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Void> savePlayerState(Player player) {
        OfflineInventoryData data = OfflineInventoryData.fromPlayer(player);
        return runAsync(() -> database.saveOfflineInventoryState(player.getUniqueId(), player.getName(), data));
    }

    public CompletableFuture<Void> queueOfflineInventoryEdit(UUID playerId,
                                                             String playerName,
                                                             OfflineInventoryData fullState,
                                                             OfflineInventoryData inventoryOverride) {
        return runAsync(() -> {
            database.saveOfflineInventoryState(playerId, playerName, fullState);
            database.savePendingOfflineInventoryOverride(playerId, playerName, inventoryOverride);
        });
    }

    public CompletableFuture<Void> queueOfflineSnapshotRestore(UUID playerId,
                                                               String playerName,
                                                               InventorySnapshot snapshot,
                                                               boolean restoreInventory,
                                                               boolean restoreArmor,
                                                               boolean restoreExperience,
                                                               boolean restoreEnderChest) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        getEffectiveInventory(playerId).whenComplete((currentState, error) -> {
            if (error != null) {
                future.completeExceptionally(error);
                return;
            }

            OfflineInventoryData override = OfflineInventoryData.fromSnapshot(
                snapshot,
                restoreInventory,
                restoreArmor,
                restoreExperience,
                restoreEnderChest
            );
            OfflineInventoryData merged = currentState.merge(override);

            runAsync(() -> {
                database.saveOfflineInventoryState(playerId, playerName, merged);
                database.savePendingOfflineInventoryOverride(playerId, playerName, override);
            }).whenComplete((ignored, saveError) -> {
                if (saveError != null) {
                    future.completeExceptionally(saveError);
                } else {
                    future.complete(null);
                }
            });
        });
        return future;
    }

    public void applyToOnlinePlayer(Player target, OfflineInventoryData override) {
        if (override.inventory() != null) {
            target.getInventory().setStorageContents(OfflineInventoryData.resize(override.inventory(), 36));
        }
        if (override.armor() != null) {
            target.getInventory().setArmorContents(OfflineInventoryData.resize(override.armor(), 4));
            target.getInventory().setItemInOffHand(override.offhand() == null ? null : override.offhand().clone());
        } else if (override.offhand() != null) {
            target.getInventory().setItemInOffHand(override.offhand().clone());
        }
        if (override.experience() != null) {
            applyExperience(target, override.experience());
        }
        if (override.enderChest() != null) {
            target.getEnderChest().setContents(OfflineInventoryData.resize(override.enderChest(), target.getEnderChest().getSize()));
        }
        target.updateInventory();
    }

    public void saveOnlinePlayerStates() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            database.saveOfflineInventoryState(player.getUniqueId(), player.getName(), OfflineInventoryData.fromPlayer(player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        savePlayerState(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        SchedulerAdapter.runAtEntityLater(player, () -> applyPendingOverride(player), 1L);
    }

    private void applyPendingOverride(Player player) {
        runAsyncSupply(() -> database.loadPendingOfflineInventoryOverride(player.getUniqueId()))
            .whenComplete((override, error) -> {
                if (error != null) {
                    Text.sendDebugLog(ERROR, "Failed to load pending offline inventory override for " + player.getName() + ": " + error.getMessage());
                    return;
                }
                if (override == null || !player.isOnline()) {
                    savePlayerState(player);
                    return;
                }

                SchedulerAdapter.runAtEntity(player, () -> {
                    try {
                        applyToOnlinePlayer(player, override);
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.0f);
                    } catch (Exception e) {
                        Text.sendDebugLog(ERROR, "Failed to apply offline inventory override for " + player.getName(), e);
                        return;
                    }

                    runAsync(() -> {
                        database.deletePendingOfflineInventoryOverride(player.getUniqueId());
                        database.saveOfflineInventoryState(player.getUniqueId(), player.getName(), OfflineInventoryData.fromPlayer(player));
                    });
                });
            });
    }

    private void applyExperience(Player target, int totalExperience) {
        target.setTotalExperience(totalExperience);

        int level = 0;
        int expForNextLevel = 0;
        while (expForNextLevel <= totalExperience) {
            expForNextLevel += getExpForLevel(level);
            level++;
        }

        int finalLevel = Math.max(0, level - 1);
        int expForPreviousLevel = 0;
        for (int i = 0; i < finalLevel; i++) {
            expForPreviousLevel += getExpForLevel(i);
        }

        int expForCurrentLevel = getExpForLevel(finalLevel);
        int expInCurrentLevel = totalExperience - expForPreviousLevel;
        float progress = expForCurrentLevel <= 0 ? 0.0f : Math.min(1.0f, Math.max(0.0f, (float) expInCurrentLevel / expForCurrentLevel));

        target.setLevel(finalLevel);
        target.setExp(progress);
    }

    private int getExpForLevel(int level) {
        if (level <= 15) {
            return 2 * level + 7;
        }
        if (level <= 30) {
            return 5 * level - 38;
        }
        return 9 * level - 158;
    }

    private CompletableFuture<Void> runAsync(ThrowingRunnable runnable) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        SchedulerAdapter.runAsync(() -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private <T> CompletableFuture<T> runAsyncSupply(ThrowingSupplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        SchedulerAdapter.runAsync(() -> {
            try {
                future.complete(supplier.get());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
