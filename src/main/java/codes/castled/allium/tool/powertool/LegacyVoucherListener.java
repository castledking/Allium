package codes.castled.allium.tool.powertool;

import codes.castled.allium.importer.VoucherImport;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ResolutionResult;
import codes.castled.allium.message.Messages;
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
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Turns a voucher made by pre-recode Allium ({@code allium:voucher_id} on the item) into its imported
 * powertool ({@code voucher_<id>}, from {@code /allium import vouchers}) when its holder right-clicks
 * it, so the click then redeems it. The whole stack converts, keeping its amount.
 *
 * <p>Vouchers can run commands and grant permissions, and an old voucher carries no signature, so
 * (like other pre-recode privileged tools) it converts only while {@code /allium adopt-window} is
 * open; each conversion is logged. Outside the window the voucher does nothing and its holder is told
 * so. Without an imported tool of that name it is left alone.
 */
public final class LegacyVoucherListener implements Listener {

    private static final long NOTICE_MILLIS = 10_000L;

    private final PowerToolBehavior powerTools;
    private final ItemService items;
    private final NamespacedKey voucherKey;
    private final PermissionGrantAction.Notifier notifier;
    private final Consumer<String> info;
    private final Consumer<String> warning;
    private final Map<UUID, Long> noticed = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public LegacyVoucherListener(Plugin plugin, PowerToolBehavior powerTools, ItemService items,
                                 PermissionGrantAction.Notifier notifier, Consumer<String> info, Consumer<String> warning) {
        this.powerTools = powerTools;
        this.items = items;
        this.voucherKey = new NamespacedKey(codes.castled.allium.PluginStart.bukkitPlugin(plugin), "voucher_id");
        this.notifier = notifier;
        this.info = info;
        this.warning = warning;
    }

    /** Before the powertool listener (normal priority), so the same click redeems the converted voucher. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot slot = event.getHand();
        if (slot != EquipmentSlot.HAND && slot != EquipmentSlot.OFF_HAND) return;
        Player player = event.getPlayer();
        ItemStack stack = slot == EquipmentSlot.HAND ? player.getInventory().getItemInMainHand() : player.getInventory().getItemInOffHand();
        String legacyId = legacyId(stack);
        if (legacyId == null) return;
        String toolName = VoucherImport.toolName(legacyId);
        if (toolName == null || powerTools.registryTool(toolName).isEmpty()) {
            if (warned.add("missing:" + legacyId)) {
                warning.accept("A pre-recode voucher '" + legacyId + "' held by " + player.getName() + " has no powertool "
                        + (toolName == null ? "(its id cannot be a tool name)" : "'" + toolName + "'")
                        + "; run /allium import vouchers to make one.");
            }
            return;
        }
        // An old voucher redeemed on any right click; it must never do its vanilla thing instead.
        event.setCancelled(true);
        if (!items.isPrivilegedAdoptionAllowed()) {
            long now = System.currentTimeMillis();
            Long last = noticed.get(player.getUniqueId());
            if (last == null || now - last > NOTICE_MILLIS) {
                noticed.put(player.getUniqueId(), now);
                notifier.send(player, Messages.POWERTOOL_LEGACY_VOUCHER);
            }
            if (warned.add("window:" + player.getUniqueId())) {
                warning.accept(player.getName() + " holds a pre-recode voucher '" + legacyId + "'; it converts to " + toolName
                        + " while /allium adopt-window is open.");
            }
            return;
        }
        ResolutionResult created = powerTools.createToolItem(toolName, stack.getAmount());
        ItemStack converted = created.itemStack().orElse(null);
        if (converted == null) {
            warning.accept("Could not convert " + player.getName() + "'s pre-recode voucher '" + legacyId + "' to " + toolName + ": "
                    + created.diagnosticMessage());
            return;
        }
        converted.setAmount(stack.getAmount());
        if (slot == EquipmentSlot.HAND) player.getInventory().setItemInMainHand(converted);
        else player.getInventory().setItemInOffHand(converted);
        info.accept("ADOPTED LEGACY VOUCHER: '" + legacyId + "' x" + stack.getAmount() + " held by " + player.getName() + " is now "
                + toolName + " (active adoption window).");
        // The powertool listener handles cancelled clicks too, so this same click redeems it.
    }

    private String legacyId(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(ItemService.KEY_BEHAVIOR_ID, PersistentDataType.STRING)) return null;
        String id = pdc.get(voucherKey, PersistentDataType.STRING);
        return id == null || id.isBlank() ? null : id;
    }
}
