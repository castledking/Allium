package codes.castled.allium.sign;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.core.FaultInjection;
import codes.castled.allium.economy.EconomyDisplay;
import codes.castled.allium.economy.EconomyReservations;
import codes.castled.allium.economy.EconomyResult;
import codes.castled.allium.economy.Money;
import codes.castled.allium.economy.PaidWorkflowSupport;
import codes.castled.allium.economy.RepairReceipts;
import codes.castled.allium.mail.PlayerDataFiles;
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
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Paid repair signs, settled so a crash never loses money and never repairs for free.
 *
 * <ol>
 *   <li><b>Reserve:</b> the price is held (not yet spent) as a {@code REPAIR} reservation. The
 *       player's saved file is proven to hold the current receipt first.</li>
 *   <li><b>Apply and save:</b> on the server thread the planned repairs are rechecked and applied,
 *       the workflow ID is added to the player's repair receipt (their own persistent data), and the
 *       player is saved: the repaired items and the receipt reach disk in the same file write.</li>
 *   <li><b>Capture:</b> once the saved file is proven to list the receipt, the hold is captured.</li>
 * </ol>
 *
 * <p>A hold whose receipt the saved file lacks is released: nothing was repaired. That decision is
 * made from the complete, validated player file, on the player's next join (or at startup for an
 * offline player, in {@code EconomyRecovery}), so a crash at any point settles exactly once.
 */
public final class PaidRepairService implements Listener {

    public static final String FAULT_HALT_AFTER_RESERVE = "repair.halt-after-reserve";
    public static final String FAULT_HALT_AFTER_SAVE = "repair.halt-after-save";

    private record Plan(boolean all, Map<Integer, ItemStack> originals, Map<Integer, ItemStack> replacements, List<String> names) {
    }

    private final PluginStart plugin;
    private final NamespacedKey receiptKey;
    private final Executor io;
    private final Path playerData;
    /** Players with a paid repair or a recovery in flight: one at a time. Server thread only. */
    private final Set<UUID> busy = new HashSet<>();
    private volatile boolean closed;

    public PaidRepairService(PluginStart plugin) {
        this.plugin = plugin;
        this.receiptKey = new NamespacedKey(plugin, "repair_receipts");
        this.io = plugin.getScheduler().boundedPool("Allium-PaidRepair", 1, 128);
        this.playerData = PlayerDataFiles.directory(plugin.getServer());
        if (!receiptKey.toString().equals(PlayerDataReceiptProof.REPAIR_RECEIPTS)) {
            throw new IllegalStateException("repair receipt key " + receiptKey + " does not match the proof key");
        }
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            recover(online);
        }
    }

    public void close() {
        closed = true;
        HandlerList.unregisterAll(this);
    }

    public boolean busy(Player player) {
        return busy.contains(player.getUniqueId());
    }

    // -- Starting a paid repair --------------------------------------------------------------------

    /** A paid repair sign was used with {@code mode} (hand or all) and a positive {@code price}. */
    public void start(Player player, String mode, Money price) {
        boolean all = "all".equals(mode);
        if (!player.hasPermission("allium.repair") || all && !player.hasPermission("allium.repair.all")) {
            plugin.getMessageManager().send(player, Messages.NO_PERMISSION, "{cmd}", all ? "repair all" : "repair");
            return;
        }
        if (closed || !PaidWorkflowSupport.ready(plugin)) {
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
        UUID account = player.getUniqueId();
        UUID workflow = UUID.randomUUID();
        String display = EconomyDisplay.read(plugin.getConfig()).format(price);
        // The saved file must match the live receipt before a hold exists: a crash after the hold
        // then decides from a file that provably holds this state.
        try {
            player.saveData();
        } catch (RuntimeException error) {
            plugin.getMessageManager().send(player, Messages.SIGN_PAID_REPAIR_DISABLED);
            return;
        }
        String baseline = receipt(player);
        Path file = file(account);
        busy.add(account);
        if (!submit(player, () -> {
            try {
                PaidWorkflowSupport.prove(file, PlayerDataReceiptProof.REPAIR_RECEIPTS, baseline);
                EconomyResult.Mutation reserved = PaidWorkflowSupport.reservations(plugin).reserve(
                        EconomyReservations.Kind.REPAIR, workflow, account, price,
                        EconomyReservations.fingerprint("REPAIR", workflow.toString(), account.toString(),
                                Long.toString(price.minor()), mode), c -> true);
                if (reserved instanceof EconomyResult.Rejected refused) {
                    onServer(() -> {
                        busy.remove(account);
                        if (refused.reason().equals("INSUFFICIENT_FUNDS") || refused.reason().equals("MISSING_ACCOUNT")) {
                            plugin.getMessageManager().send(player, Messages.SIGN_REPAIR_FUNDS, "{price}", display);
                        } else {
                            plugin.getMessageManager().send(player, Messages.ECO_OPERATION_REFUSED, "{reason}", refused.reason());
                        }
                    });
                    return;
                }
                PaidWorkflowSupport.committed(reserved);
            } catch (PaidWorkflowSupport.UnknownCommit unknown) {
                // If the hold committed, the next join releases it: no receipt was ever saved.
                onServer(() -> {
                    busy.remove(account);
                    plugin.getMessageManager().send(player, Messages.ECO_UNKNOWN, "{operation}", unknown.operationId().toString());
                });
                return;
            } catch (SQLException error) {
                plugin.logger().warning("Paid repair for " + player.getName() + " was not started: " + error.getMessage());
                onServer(() -> {
                    busy.remove(account);
                    plugin.getMessageManager().send(player, Messages.ECO_STORAGE);
                });
                return;
            }
            if (FaultInjection.trip(FAULT_HALT_AFTER_RESERVE)) {
                plugin.logger().warning("Fault " + FAULT_HALT_AFTER_RESERVE + ": halting with repair " + workflow + " held (simulated crash).");
                Runtime.getRuntime().halt(77);
            }
            onServer(() -> apply(player, workflow, plan, price, display, baseline));
        })) {
            busy.remove(account);
            plugin.getMessageManager().send(player, Messages.ECO_BUSY);
        }
    }

    /** Server thread: the hold exists. Repair, record the receipt and save them together. */
    private void apply(Player player, UUID workflow, Plan plan, Money price, String display, @Nullable String baseline) {
        UUID account = player.getUniqueId();
        if (closed || plugin.getServer().getPlayer(account) != player || !player.isOnline()) {
            // The player left: the saved file has no receipt, so their next join releases the hold.
            busy.remove(account);
            return;
        }
        if (!stillPlanned(player, plan) || !emptyCursor(player)) {
            releaseNow(player, workflow, baseline, () -> plugin.getMessageManager().send(player, Messages.SIGN_REPAIR_CHANGED,
                    "{price}", display));
            return;
        }
        var inventory = player.getInventory();
        plan.replacements().forEach(inventory::setItem);
        Set<UUID> receipts = RepairReceipts.parse(receipt(player));
        receipts.add(workflow);
        setReceipt(player, receipts);
        try {
            player.saveData();
        } catch (RuntimeException error) {
            // Live state holds the repair and the receipt; the next save and join settle it.
            plugin.logger().warning("Could not save " + player.getName() + " after paid repair " + workflow + ": " + error.getMessage());
            busy.remove(account);
            plugin.getMessageManager().send(player, Messages.SIGN_REPAIR_PENDING, "{operation}", workflow.toString());
            return;
        }
        String expected = receipt(player);
        if (FaultInjection.trip(FAULT_HALT_AFTER_SAVE)) {
            plugin.logger().warning("Fault " + FAULT_HALT_AFTER_SAVE + ": halting with repair " + workflow + " saved but not captured.");
            Runtime.getRuntime().halt(77);
        }
        Path file = file(account);
        if (!submit(player, () -> {
            boolean captured;
            try {
                PaidWorkflowSupport.prove(file, PlayerDataReceiptProof.REPAIR_RECEIPTS, expected);
                captured = PaidWorkflowSupport.committed(PaidWorkflowSupport.reservations(plugin)
                        .capture(EconomyReservations.Kind.REPAIR, workflow, c -> true));
            } catch (SQLException error) {
                plugin.logger().warning("Paid repair " + workflow + " for " + player.getName()
                        + " is saved; its capture waits for the next join: " + error.getMessage());
                captured = false;
            }
            boolean done = captured;
            onServer(() -> {
                busy.remove(account);
                if (!done) {
                    plugin.getMessageManager().send(player, Messages.SIGN_REPAIR_PENDING, "{operation}", workflow.toString());
                    return;
                }
                if (plugin.getServer().getPlayer(account) == player) {
                    Set<UUID> left = RepairReceipts.parse(receipt(player));
                    left.remove(workflow);
                    setReceipt(player, left);
                }
                plugin.getMessageManager().send(player, Messages.SIGN_REPAIR_PAID, "{items}", String.join(", ", plan.names()),
                        "{price}", display);
                UtilitySupport.audit(plugin, player, "paid_repair", "workflow=" + workflow + " items=" + plan.names().size()
                        + " price=" + price.minor());
            });
        })) {
            busy.remove(account);
            plugin.getMessageManager().send(player, Messages.SIGN_REPAIR_PENDING, "{operation}", workflow.toString());
        }
    }

    /** Releases {@code workflow}'s hold once the saved file proves its receipt absent. */
    private void releaseNow(Player player, UUID workflow, @Nullable String baseline, Runnable then) {
        UUID account = player.getUniqueId();
        Path file = file(account);
        if (!submit(player, () -> {
            try {
                PaidWorkflowSupport.prove(file, PlayerDataReceiptProof.REPAIR_RECEIPTS, baseline);
                PaidWorkflowSupport.committed(PaidWorkflowSupport.reservations(plugin)
                        .release(EconomyReservations.Kind.REPAIR, workflow, c -> true));
                onServer(() -> {
                    busy.remove(account);
                    then.run();
                });
            } catch (SQLException error) {
                plugin.logger().warning("Paid repair hold " + workflow + " stays held until " + player.getName()
                        + "'s next join: " + error.getMessage());
                onServer(() -> busy.remove(account));
            }
        })) {
            busy.remove(account);
        }
    }

    // -- Recovery ----------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        recover(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        busy.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Settles {@code player}'s HELD repair holds from their saved file: captured when it lists the
     * receipt, released when the complete file proves it absent. Receipts of settled holds are
     * then dropped from the live data.
     */
    private void recover(Player player) {
        if (!PaidWorkflowSupport.ready(plugin)) {
            return;
        }
        UUID account = player.getUniqueId();
        busy.add(account);
        Path file = file(account);
        if (!submit(player, () -> {
            int captured = 0;
            int released = 0;
            Set<UUID> settled = new HashSet<>();
            try {
                EconomyReservations holds = PaidWorkflowSupport.reservations(plugin);
                List<EconomyReservations.Reservation> rows = holds.list(account).stream()
                        .filter(r -> r.kind() == EconomyReservations.Kind.REPAIR).toList();
                Set<UUID> saved = rows.stream().anyMatch(r -> r.state().equals("HELD"))
                        ? RepairReceipts.parse(PlayerDataReceiptProof.read(file, PlayerDataReceiptProof.REPAIR_RECEIPTS))
                        : Set.of();
                for (EconomyReservations.Reservation row : rows) {
                    if (!row.state().equals("HELD")) {
                        settled.add(row.id());
                        continue;
                    }
                    if (holds.operatorResolved(row.kind(), row.id())) {
                        continue;
                    }
                    boolean charge = saved.contains(row.id());
                    EconomyResult.Mutation result = charge
                            ? holds.capture(row.kind(), row.id(), c -> true)
                            : holds.release(row.kind(), row.id(), c -> true);
                    if (PaidWorkflowSupport.committed(result)) {
                        settled.add(row.id());
                        if (charge) captured++;
                        else released++;
                    }
                }
            } catch (SQLException | IOException | RuntimeException error) {
                plugin.logger().warning("Paid repair recovery for " + player.getName() + " is retried on their next join: "
                        + error.getMessage());
            }
            int charged = captured;
            int refunded = released;
            onServer(() -> {
                busy.remove(account);
                if (plugin.getServer().getPlayer(account) != player) {
                    return;
                }
                Set<UUID> live = RepairReceipts.parse(receipt(player));
                if (live.removeAll(settled)) {
                    setReceipt(player, live);
                }
                if (charged + refunded > 0) {
                    plugin.getMessageManager().send(player, Messages.SIGN_REPAIR_RECOVERED,
                            "{captured}", Integer.toString(charged), "{released}", Integer.toString(refunded));
                    plugin.logger().info("Settled " + (charged + refunded) + " interrupted paid repair(s) for " + player.getName()
                            + ": " + charged + " captured, " + refunded + " released.");
                }
            });
        })) {
            busy.remove(account);
        }
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
            names.add(codes.castled.allium.command.essentials.UtilitySupport.itemName(item));
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

    @Nullable
    private String receipt(Player player) {
        return player.getPersistentDataContainer().get(receiptKey, PersistentDataType.STRING);
    }

    private void setReceipt(Player player, Set<UUID> ids) {
        String value = RepairReceipts.format(new LinkedHashSet<>(ids));
        if (value == null) {
            player.getPersistentDataContainer().remove(receiptKey);
        } else {
            player.getPersistentDataContainer().set(receiptKey, PersistentDataType.STRING, value);
        }
    }

    private Path file(UUID account) {
        return playerData.resolve(account + ".dat");
    }

    private boolean submit(Player player, Runnable task) {
        try {
            io.execute(task);
            return true;
        } catch (RejectedExecutionException full) {
            plugin.logger().warning("Paid repair queue is full; " + player.getName() + "'s request was not started.");
            return false;
        }
    }

    private void onServer(Runnable task) {
        plugin.getScheduler().runOnServer(() -> {
            if (!closed) task.run();
        });
    }
}
