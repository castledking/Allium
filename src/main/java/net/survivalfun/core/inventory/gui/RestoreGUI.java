package net.survivalfun.core.inventory.gui;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.inventory.InventoryManager;
import net.survivalfun.core.inventory.InventorySnapshot;
import net.survivalfun.core.util.ItemBuilder;
import net.survivalfun.core.util.SchedulerAdapter;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.UUID;

public class RestoreGUI extends BaseGUI {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    
    private final UUID targetId;
    private final InventoryManager inventoryManager;
    private int currentPage = 0;

    public RestoreGUI(Player player, UUID targetId, InventoryManager inventoryManager, PluginStart plugin) {
        super(player, "Inventory Restore - Page 1", 6, plugin);
        this.targetId = targetId;
        this.inventoryManager = inventoryManager;
    }

    @Override
    public void initialize() {
        // Clear existing items and handlers on the main thread
        SchedulerAdapter.runAtEntity(player, () -> {
            inventory.clear();
            clickHandlers.clear();
        });

        // Load snapshots asynchronously
        SchedulerAdapter.runAsync(() -> {
            inventoryManager.getPlayerSnapshots(targetId, currentPage).whenComplete((snapshots, error) -> {
                if (error != null) {
                    SchedulerAdapter.runAtEntity(player, () -> {
                        player.sendMessage("§cFailed to load inventory snapshots: " + error.getMessage());
                        error.printStackTrace();
                    });
                    return;
                }

                // Process snapshots on the entity's thread
                SchedulerAdapter.runAtEntity(player, () -> {
                    try {
                        // Display snapshots
                        for (int i = 0; i < snapshots.size(); i++) {
                            InventorySnapshot snapshot = snapshots.get(i);
                            ItemStack item = createSnapshotItem(snapshot);
                            int slot = i;
                            setItem(slot, item, e -> openRestoreOptions(snapshot));
                        }
                        
                        // Update navigation buttons
                        updateNavigationButtons(snapshots.size());
                    } catch (Exception e) {
                        player.sendMessage("§cFailed to process inventory snapshots");
                        e.printStackTrace();
                    }
                });
            });
        });
    }

    private void openRestoreOptions(InventorySnapshot snapshot) {
        SchedulerAdapter.runAtEntity(player, () -> {
            new RestoreOptionsGUI(player, snapshot, this, plugin).open();
        });
    }

    private void updateNavigationButtons(int snapshotCount) {
        inventoryManager.getTotalPages(targetId).whenComplete((totalPages, error) -> {
            if (error != null) {
                String errorMsg = "Failed to load page information: " + error.getMessage();
                Text.sendDebugLog(ERROR, errorMsg);
                error.printStackTrace();
                player.sendMessage("§cFailed to load page information. Please check the server logs.");
                return;
            }
            
            final int finalTotalPages = totalPages == 0 ? 1 : totalPages;
            
            // Run on main thread since we're updating the inventory
            SchedulerAdapter.runAtEntity(player, () -> {
                try {
                    // Clear previous navigation buttons (bottom row)
                    for (int i = 45; i < 54; i++) {
                        setItem(i, null, null);
                    }
                    
                    // Add previous page button if not on first page
                    if (currentPage > 0) {
                        ItemStack prevButton = new ItemBuilder(Material.ARROW)
                                .name("&aPrevious Page")
                                .lore("&7Click to go to page " + currentPage)
                                .build();
                        setItem(45, prevButton, e -> {
                            currentPage--;
                            initialize();
                        });
                    }
                    
                    // Add current page indicator
                    ItemStack pageInfo = new ItemBuilder(Material.BOOK)
                            .name("&ePage " + (currentPage + 1) + " of " + finalTotalPages)
                            .lore("&7Total snapshots: " + snapshotCount)
                            .build();
                    setItem(49, pageInfo, null);
                    
                    // Add next page button if there are more pages
                    if (currentPage < finalTotalPages - 1) {
                        ItemStack nextButton = new ItemBuilder(Material.ARROW)
                                .name("&aNext Page")
                                .lore("&7Click to go to page " + (currentPage + 2))
                                .build();
                        setItem(53, nextButton, e -> {
                            currentPage++;
                            initialize();
                        });
                    }
                } catch (Exception e) {
                    Text.sendDebugLog(ERROR, "Error updating navigation buttons: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        });
    }

    private ItemStack createSnapshotItem(InventorySnapshot snapshot) {
        String reason = snapshot.getReason() != null ? snapshot.getReason() : "Unknown";
        ItemBuilder builder = getIconBuilderForReason(reason);

        return builder
                .name("&e" + reason)
                .lore(
                        "&7Time: &f" + DATE_FORMAT.format(snapshot.getTimestamp()),
                        "&7World: &f" + snapshot.getWorldName(),
                        "&7Experience: &f" + snapshot.getExperience(),
                        "",
                        "&eClick to view restore options"
                )
                .build();
    }

    private ItemBuilder getIconBuilderForReason(String reason) {
        String key = reason.trim().toUpperCase(Locale.ROOT);

        switch (key) {
            case "LOGIN":
                return new ItemBuilder(Material.GREEN_BED);
            case "LOGOUT":
                return new ItemBuilder(Material.IRON_DOOR);
            case "AUTOSAVE":
                return new ItemBuilder(Material.CLOCK);
            case "DEATH":
                return new ItemBuilder(Material.SKELETON_SKULL);
            case "TELEPORT":
                return new ItemBuilder(Material.ENDER_PEARL);
            case "FREEZE":
                return new ItemBuilder(Material.ICE);
            case "WORLD CHANGE":
                return new ItemBuilder(Material.NETHER_PORTAL);
            default:
                return new ItemBuilder(Material.CHEST);
        }
    }

}
