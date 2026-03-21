package net.survivalfun.core.listeners;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.util.SetExpFix;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
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

/**
 * Right-click XP bottle (with PDC xp_voucher_amount) to add XP and consume one bottle.
 */
public class XpBottleRedeemListener implements Listener {

    private final PluginStart plugin;
    private final NamespacedKey xpAmountKey;

    public XpBottleRedeemListener(PluginStart plugin) {
        this.plugin = plugin;
        this.xpAmountKey = new NamespacedKey(plugin, "xp_voucher_amount");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.EXPERIENCE_BOTTLE) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        Integer amount = meta.getPersistentDataContainer().get(xpAmountKey, PersistentDataType.INTEGER);
        if (amount == null || amount <= 0) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        int current = SetExpFix.getTotalExperience(player);
        SetExpFix.setTotalExperience(player, current + amount);

        EquipmentSlot hand = event.getHand();
        ItemStack itemInHand = hand == EquipmentSlot.OFF_HAND
            ? player.getInventory().getItemInOffHand()
            : player.getInventory().getItemInMainHand();

        if (itemInHand != null && itemInHand.isSimilar(item)) {
            if (itemInHand.getAmount() > 1) {
                itemInHand.setAmount(itemInHand.getAmount() - 1);
            } else if (hand == EquipmentSlot.OFF_HAND) {
                player.getInventory().setItemInOffHand(null);
            } else {
                player.getInventory().setItemInMainHand(null);
            }
        }

        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.15f);
        player.sendMessage("§aRedeemed " + amount + " XP.");
    }
}
