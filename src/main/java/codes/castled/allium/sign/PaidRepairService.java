package codes.castled.allium.sign;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.economy.EconomyDisplay;
import codes.castled.allium.economy.EconomyReservations;
import codes.castled.allium.economy.EconomyRuntime;
import codes.castled.allium.economy.Money;
import codes.castled.allium.economy.PaidActionOutcome;
import codes.castled.allium.economy.PaidActionResult;
import codes.castled.allium.economy.PaidActionService;
import codes.castled.allium.economy.PaidWorkflowSupport;
import codes.castled.allium.mail.PlayerDataReceiptProof;
import codes.castled.allium.message.Messages;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Paid repair signs built on {@link PaidActionService}'s receipt-backed lifecycle:
 * plans damaged inventory items, delegates baseline proof, reservation, atomic receipt save,
 * capture/release settlement, and join recovery to {@link PaidActionService}.
 */
public final class PaidRepairService implements Listener {

    public static final String FAULT_HALT_AFTER_RESERVE = "repair.halt-after-reserve";
    public static final String FAULT_HALT_AFTER_SAVE = "repair.halt-after-save";

    private record Plan(boolean all, Map<Integer, ItemStack> originals, Map<Integer, ItemStack> replacements, List<String> names) {}

    private final PluginStart plugin;
    private final PaidActionService actions;
    private final PaidActionService.ReceiptSpec receiptSpec;

    public PaidRepairService(PluginStart plugin) {
        this.plugin = plugin;
        this.actions = new PaidActionService(plugin, plugin.getScheduler().boundedPool("Allium-PaidRepair", 1, 128));
        this.receiptSpec = new PaidActionService.ReceiptSpec(
                PlayerDataReceiptProof.REPAIR_RECEIPTS,
                new NamespacedKey(codes.castled.allium.PluginStart.bukkitPlugin(plugin), "repair_receipts"),
                FAULT_HALT_AFTER_RESERVE,
                FAULT_HALT_AFTER_SAVE
        );
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, codes.castled.allium.PluginStart.bukkitPlugin(plugin));
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            recover(online);
        }
    }

    public void close() {
        actions.close();
        HandlerList.unregisterAll(this);
    }

    public boolean busy(Player player) {
        return actions.busy(player);
    }

    // -- Starting a paid repair --------------------------------------------------------------------

    /** A paid repair sign was used with {@code mode} (hand or all) and a positive {@code price}. */
    public void start(Player player, String mode, Money price) {
        start(player, mode, price, () -> true);
    }

    /** A paid repair sign was used with {@code mode}, positive {@code price}, and a sign-validity recheck. */
    public void start(Player player, String mode, Money price, BooleanSupplier valid) {
        boolean all = "all".equals(mode);
        if (!player.hasPermission("allium.repair") || all && !player.hasPermission("allium.repair.all")) {
            plugin.getMessageManager().send(player, Messages.NO_PERMISSION, "{cmd}", all ? "repair all" : "repair");
            return;
        }
        if (actions.isClosed() || !PaidWorkflowSupport.ready(plugin)) {
            plugin.getMessageManager().send(player, Messages.SIGN_PAID_REPAIR_DISABLED);
            return;
        }
        if (busy(player) || UtilitySupport.isInventoryBusy(plugin, player) || !emptyCursor(player)) {
            plugin.getMessageManager().send(player, Messages.UTILITY_INVENTORY_BUSY, "{player}", player.getName());
            return;
        }
        Plan plan = plan(player, all);
        if (plan.replacements().isEmpty()) {
            plugin.getMessageManager().send(player, all ? Messages.UTILITY_REPAIR_NONE : Messages.REPAIR_HOLD_DAMAGED);
            return;
        }

        UUID workflow = UUID.randomUUID();
        String display = EconomyDisplay.read(plugin.getConfig()).format(price);
        String fingerprint = EconomyReservations.fingerprint(
                "REPAIR", workflow.toString(), player.getUniqueId().toString(),
                Long.toString(price.minor()), mode);

        actions.executeReceiptBacked(new PaidActionService.ReceiptRequest(
                EconomyReservations.Kind.REPAIR,
                workflow,
                player,
                price,
                fingerprint,
                "Paid repair",
                receiptSpec,
                valid,
                stillValid -> {
                    if (!stillValid.getAsBoolean() || !stillPlanned(player, plan) || !emptyCursor(player)) {
                        return PaidActionOutcome.refused("changed");
                    }
                    var inventory = player.getInventory();
                    plan.replacements().forEach(inventory::setItem);
                    return PaidActionOutcome.delivered();
                },
                result -> handleResult(player, plan, price, display, result)
        ));
    }

    private void handleResult(Player player, Plan plan, Money price, String display, PaidActionResult result) {
        switch (result.status()) {
            case UNAVAILABLE -> plugin.getMessageManager().send(player, Messages.SIGN_PAID_REPAIR_DISABLED);
            case BUSY -> {
                if ("worker queue full".equals(result.detail())) {
                    plugin.getMessageManager().send(player, Messages.ECO_BUSY);
                } else {
                    plugin.getMessageManager().send(player, Messages.UTILITY_INVENTORY_BUSY, "{player}", player.getName());
                }
            }
            case INSUFFICIENT_FUNDS ->
                    plugin.getMessageManager().send(player, Messages.SIGN_REPAIR_FUNDS, "{price}", display);
            case RESERVATION_REFUSED -> EconomyRuntime.sendRefusal(plugin, player, result.detail());
            case UNKNOWN_RESERVE -> {
                if (result.unknownOperationId() != null) {
                    plugin.getMessageManager().send(player, Messages.ECO_UNKNOWN,
                            "{operation}", result.unknownOperationId().toString());
                }
            }
            case STORAGE_ERROR -> plugin.getMessageManager().send(player, Messages.ECO_STORAGE);
            case RELEASED -> {
                if (result.deliveryOutcome() instanceof PaidActionOutcome.Refused) {
                    plugin.getMessageManager().send(player, Messages.SIGN_REPAIR_CHANGED, "{price}", display);
                }
            }
            case RELEASE_DEFERRED -> {
                // Hold remains for next-join receipt recovery.
            }
            case CAPTURE_DEFERRED ->
                    plugin.getMessageManager().send(player, Messages.SIGN_REPAIR_PENDING,
                            "{operation}", result.workflowId().toString());
            case CAPTURED -> {
                plugin.getMessageManager().send(player, Messages.SIGN_REPAIR_PAID,
                        "{items}", String.join(", ", plan.names()), "{price}", display);
                UtilitySupport.audit(plugin, player, "paid_repair",
                        "workflow=" + result.workflowId() + " items=" + plan.names().size()
                                + " price=" + price.minor());
            }
        }
    }

    // -- Recovery ----------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        recover(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        actions.clearBusy(event.getPlayer().getUniqueId());
    }

    private void recover(Player player) {
        actions.recoverReceipts(player, EconomyReservations.Kind.REPAIR, receiptSpec, "Paid repair", summary -> {
            int total = summary.captured() + summary.released();
            if (total > 0) {
                plugin.getMessageManager().send(player, Messages.SIGN_REPAIR_RECOVERED,
                        "{captured}", Integer.toString(summary.captured()),
                        "{released}", Integer.toString(summary.released()));
                plugin.logger().info("Settled " + total + " interrupted paid repair(s) for " + player.getName()
                        + ": " + summary.captured() + " captured, " + summary.released() + " released.");
            }
        });
    }

    // -- Helpers -----------------------------------------------------------------------------------

    private Plan plan(Player player, boolean all) {
        var inventory = player.getInventory();
        Map<Integer, ItemStack> originals = new LinkedHashMap<>();
        Map<Integer, ItemStack> replacements = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        int held = inventory.getHeldItemSlot();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (!all && slot != held) continue;
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir() || !(item.getItemMeta() instanceof Damageable damage) || !damage.hasDamage()) {
                continue;
            }
            ItemStack replacement = item.clone();
            damage.setDamage(0);
            if (!replacement.setItemMeta(damage)) continue;
            originals.put(slot, item.clone());
            replacements.put(slot, replacement);
            names.add(UtilitySupport.itemName(item));
        }
        return new Plan(all, originals, replacements, names);
    }

    private static boolean stillPlanned(Player player, Plan plan) {
        var inventory = player.getInventory();
        for (var entry : plan.originals().entrySet()) {
            ItemStack now = inventory.getItem(entry.getKey());
            if (now == null || !now.equals(entry.getValue())) {
                return false;
            }
        }
        return plan.all() || plan.originals().containsKey(inventory.getHeldItemSlot());
    }

    private static boolean emptyCursor(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        return cursor == null || cursor.getType().isAir();
    }
}
