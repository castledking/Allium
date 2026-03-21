package net.survivalfun.core.listeners;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.voucher.Voucher;
import net.survivalfun.core.voucher.VouchersConfig;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Right-click item with PDC voucher_id to redeem: run commands (replace %player%), optionally grant one permission, remove one item.
 */
public class VoucherRedeemListener implements Listener {

    private final PluginStart plugin;
    private final NamespacedKey voucherIdKey;

    public VoucherRedeemListener(PluginStart plugin) {
        this.plugin = plugin;
        this.voucherIdKey = new NamespacedKey(plugin, "voucher_id");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        if (!meta.getPersistentDataContainer().has(voucherIdKey, PersistentDataType.STRING)) return;

        event.setCancelled(true);
        String voucherId = meta.getPersistentDataContainer().get(voucherIdKey, PersistentDataType.STRING);
        if (voucherId == null || voucherId.isEmpty()) return;

        VouchersConfig config = plugin.getVouchersConfig();
        if (config == null) return;
        Voucher voucher = config.getVoucher(voucherId);
        if (voucher == null) return;

        Player player = event.getPlayer();
        String name = player.getName();

        for (String cmd : voucher.getCommands()) {
            String parsed = cmd.replace("%player%", name);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
        }

        List<String> messages = new ArrayList<>();
        if (!voucher.getCommands().isEmpty()) messages.add(ChatColor.GREEN + "Commands executed!");

        if (!voucher.getGrants().isEmpty()) {
            Voucher.Grant selected = selectGrant(player, voucher.getGrants());
            if (selected != null) {
                boolean granted = grantPermission(player, selected.getPermission());
                if (granted) messages.add(ChatColor.GREEN + "You received: " + ChatColor.GOLD + selected.getPermission());
                else messages.add(ChatColor.YELLOW + "Permission could not be granted.");
            } else {
                messages.add(ChatColor.YELLOW + "You already have all available permissions from this voucher!");
            }
        }

        removeOneItem(player, event.getHand(), item);
        player.sendMessage(ChatColor.GREEN + "Voucher redeemed: " + ChatColor.translateAlternateColorCodes('&', voucher.getDisplayName()));
        for (String msg : messages) player.sendMessage(msg);
    }

    private Voucher.Grant selectGrant(Player player, List<Voucher.Grant> grants) {
        List<Voucher.Grant> available = new ArrayList<>();
        int totalWeight = 0;
        for (Voucher.Grant g : grants) {
            if (!player.hasPermission(g.getPermission())) {
                available.add(g);
                totalWeight += g.getChance();
            }
        }
        if (available.isEmpty() || totalWeight <= 0) return null;
        int r = ThreadLocalRandom.current().nextInt(totalWeight);
        for (Voucher.Grant g : available) {
            r -= g.getChance();
            if (r < 0) return g;
        }
        return available.get(available.size() - 1);
    }

    private boolean grantPermission(Player player, String permission) {
        try {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "lp user " + player.getName() + " permission set " + permission + " true");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to grant permission " + permission + " to " + player.getName() + ": " + e.getMessage());
            return false;
        }
    }

    private void removeOneItem(Player player, EquipmentSlot hand, ItemStack item) {
        ItemStack inHand = hand == EquipmentSlot.OFF_HAND ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        if (inHand == null || !inHand.isSimilar(item)) return;
        if (inHand.getAmount() > 1) {
            inHand.setAmount(inHand.getAmount() - 1);
        } else {
            if (hand == EquipmentSlot.OFF_HAND) player.getInventory().setItemInOffHand(null);
            else player.getInventory().setItemInMainHand(null);
        }
    }

    public NamespacedKey getVoucherIdKey() { return voucherIdKey; }
}
