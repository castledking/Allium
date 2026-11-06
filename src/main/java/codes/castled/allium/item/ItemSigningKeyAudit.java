package codes.castled.allium.item;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Read-only key-ID audit as inventories become available; never creates or re-signs items. */
public final class ItemSigningKeyAudit implements Listener {
    private final Set<String> knownKeys;
    private final Set<String> reportedKeys = new HashSet<>();
    private final Consumer<String> criticalLogger;

    public ItemSigningKeyAudit(ItemHmacSigner signer, Consumer<String> criticalLogger) {
        this.knownKeys = Set.copyOf(signer.keyIds());
        this.criticalLogger = criticalLogger;
    }

    public void install(JavaPlugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var online = new ArrayDeque<>(plugin.getServer().getOnlinePlayers());
        if (!online.isEmpty()) {
            // One inventory per tick on late enable; avoid a large synchronous startup scan.
            new BukkitRunnable() {
                @Override public void run() {
                    Player player = online.poll();
                    if (player == null) { cancel(); return; }
                    if (player.isOnline()) auditPlayer(player);
                    if (online.isEmpty()) cancel();
                }
            }.runTaskTimer(plugin, 1L, 1L);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        auditPlayer(event.getPlayer());
    }

    private void auditPlayer(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) auditStack(stack);
        for (ItemStack stack : player.getEnderChest().getContents()) auditStack(stack);
    }

    void auditStack(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        String signature = meta.getPersistentDataContainer().get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING);
        if (signature == null) return;
        String[] parts = signature.split(":", -1);
        if (parts.length < 2) return; // Invalid signature is separately rejected by ItemService.
        String id = parts[0].startsWith("v") && parts.length >= 3 ? parts[1] : parts[0];
        if (!knownKeys.contains(id) && reportedKeys.size() < 64 && reportedKeys.add(id)) {
            String safeId = id.replaceAll("[^A-Za-z0-9_.-]", "?");
            if (safeId.length() > 80) safeId = safeId.substring(0, 80);
            criticalLogger.accept("CRITICAL HMAC KEY MISMATCH: loaded inventory contains item signed with unknown key ID '"
                    + safeId + "'. Restore its key in .item-hmac-secret before use; active/retired IDs: " + knownKeys
                    + ". Inventory metadata was left unchanged.");
        }
    }
}
