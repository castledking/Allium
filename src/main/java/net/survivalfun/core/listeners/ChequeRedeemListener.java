package net.survivalfun.core.listeners;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.economy.EconomyManager;
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

import java.math.BigDecimal;

/**
 * Right-click paper with PDC cheque_amount to deposit and consume one.
 */
public class ChequeRedeemListener implements Listener {

    private final PluginStart plugin;
    private final NamespacedKey amountKey;
    private final NamespacedKey signerKey;

    public ChequeRedeemListener(PluginStart plugin) {
        this.plugin = plugin;
        this.amountKey = new NamespacedKey(plugin, "cheque_amount");
        this.signerKey = new NamespacedKey(plugin, "cheque_signer");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.PAPER) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        BigDecimal amount = getChequeAmount(meta);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        EconomyManager economy = plugin.getEconomyManager();
        if (!economy.deposit(player.getUniqueId(), amount)) {
            plugin.getLogger().warning("Failed to redeem cheque for " + player.getName() + ".");
            return;
        }

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

        String signer = meta.getPersistentDataContainer().get(signerKey, PersistentDataType.STRING);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.9f);
        player.sendMessage("§aRedeemed a cheque for " + economy.formatBalance(amount) + (signer != null ? " §7(signed by " + signer + ")" : "") + "§a.");
    }

    private BigDecimal getChequeAmount(ItemMeta meta) {
        Double amountDouble = meta.getPersistentDataContainer().get(amountKey, PersistentDataType.DOUBLE);
        if (amountDouble != null && amountDouble > 0) {
            return BigDecimal.valueOf(amountDouble);
        }

        String amountString = meta.getPersistentDataContainer().get(amountKey, PersistentDataType.STRING);
        if (amountString == null || amountString.isEmpty()) {
            return BigDecimal.ZERO;
        }

        try {
            return new BigDecimal(amountString);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
