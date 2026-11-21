package codes.castled.allium.worth;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.core.FaultInjection;
import codes.castled.allium.economy.EconomyRequest;
import codes.castled.allium.economy.EconomyResult;
import codes.castled.allium.economy.EconomyRuntime;
import codes.castled.allium.economy.Money;
import codes.castled.allium.economy.PaidWorkflowSupport;
import codes.castled.allium.economy.SaleReceipts;
import codes.castled.allium.mail.MailItemCodec;
import codes.castled.allium.mail.PlayerDataFiles;
import codes.castled.allium.mail.PlayerDataReceiptProof;
import codes.castled.allium.message.Messages;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * {@code /sell}, settled so a crash never pays twice, never pays for items kept, and never loses
 * items without paying for them.
 *
 * <ol>
 *   <li><b>Check:</b> the seller's account exists and the sale fits under the balance cap.</li>
 *   <li><b>Remove and save:</b> on the server thread the planned items are rechecked and removed, the
 *       sale is added to the seller's sale receipt with a copy of the items, and the player is saved:
 *       the removal and the receipt reach disk in the same file write.</li>
 *   <li><b>Pay:</b> once the saved file is proven to list the sale, it is paid by a deposit whose
 *       operation ID comes from the sale ID. Paying it again replays the first payment.</li>
 *   <li><b>Settle:</b> paid, the receipt entry is dropped. Refused (the balance cap, say), the items are
 *       given back. With the outcome unknown, the receipt stays and the next join settles it.</li>
 * </ol>
 *
 * <p>A sale the saved file does not list never happened there: its items are still in the file, and it
 * is never paid. A crash at any point therefore settles exactly once, on the seller's next join.
 */
public final class SellService implements Listener {

    public static final String FAULT_HALT_AFTER_SAVE = "sell.halt-after-save";
    public static final String FAULT_HALT_AFTER_PAY = "sell.halt-after-pay";
    private static final int MAX_ITEM_BYTES = 256 * 1024;

    private record Pending(String command, long total, int count, long expires) {
    }

    /** How a sale's deposit ended. */
    private sealed interface Settlement permits Paid, Refused, Unsettled {
    }

    private record Paid(EconomyRequest request, @Nullable EconomyResult.Committed done) implements Settlement {
    }

    private record Refused(String reason) implements Settlement {
    }

    private record Unsettled(String why) implements Settlement {
    }

    private final PluginStart plugin;
    private final WorthService worth;
    private final NamespacedKey receiptKey;
    private final Executor io;
    private final Path playerData;
    private final MailItemCodec codec = new MailItemCodec();
    /** Players with a sale or a recovery in flight: one at a time. Server thread only. */
    private final Set<UUID> busy = new HashSet<>();
    private final Map<UUID, Pending> confirmations = new HashMap<>();
    private volatile boolean closed;

    public SellService(PluginStart plugin, WorthService worth) {
        this.plugin = plugin;
        this.worth = worth;
        this.receiptKey = new NamespacedKey(plugin, "sale_receipts");
        this.io = plugin.getScheduler().boundedPool("Allium-Sell", 1, 128);
        this.playerData = PlayerDataFiles.directory(plugin.getServer());
        if (!receiptKey.toString().equals(PlayerDataReceiptProof.SALE_RECEIPTS)) {
            throw new IllegalStateException("sale receipt key " + receiptKey + " does not match the proof key");
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
        confirmations.clear();
    }

    public boolean busy(Player player) {
        return busy.contains(player.getUniqueId());
    }

    /** Whether {@code player} may sell in their game mode. */
    public boolean gameModeAllows(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE || worth.settings().allowCreative()
                || player.hasPermission(WorthService.PERMISSION_SELL_CREATIVE);
    }

    // -- Planning ----------------------------------------------------------------------------------

    /** The plan for {@code player}, or a message already sent and null. */
    @Nullable
    public SalePlan plan(Player player, SalePlan.Scope scope, @Nullable ItemStack target, SaleQuantity quantity) {
        var inventory = player.getInventory();
        SalePlan.Outcome outcome = SalePlan.plan(inventory.getStorageContents(), inventory.getHeldItemSlot(), scope, target,
                quantity, worth.settings(), worth.multiplier(player), worth::refusal);
        if (outcome instanceof SalePlan.Planned planned) {
            return planned.plan();
        }
        if (outcome instanceof SalePlan.Refused refused) {
            sendRefusal(player, refused.item(), refused.refusal());
        } else if (outcome instanceof SalePlan.Short few && few.available() == 0) {
            send(player, Messages.SELL_NONE, "{item}", WorthService.name(few.item()));
        } else if (outcome instanceof SalePlan.Short few) {
            send(player, Messages.SELL_NOT_ENOUGH, "{item}", WorthService.name(few.item()),
                    "{available}", Integer.toString(few.available()));
        } else if (outcome instanceof SalePlan.Stacks stacks) {
            send(player, Messages.SELL_MUST_STACK, "{item}", WorthService.name(stacks.item()),
                    "{stack}", Integer.toString(stacks.stack()));
        } else {
            send(player, Messages.SELL_NOTHING);
        }
        return null;
    }

    public void sendRefusal(Player player, ItemStack item, SalePlan.Refusal refusal) {
        String name = WorthService.name(item);
        switch (refusal) {
            case NOT_SELLABLE -> send(player, Messages.WORTH_NOT_SELLABLE, "{item}", name);
            case WORTHLESS -> send(player, Messages.WORTH_WORTHLESS, "{item}", name);
            case NAMED -> send(player, Messages.SELL_NAMED);
            case CUSTOM -> send(player, Messages.SELL_CUSTOM, "{item}", name);
            case CREATIVE -> send(player, Messages.SELL_CREATIVE_ITEM);
            case CONTAINER -> send(player, Messages.SELL_CONTAINER, "{item}", name);
        }
    }

    /** "64 Cobblestone, 12 Diamond, 3 Oak Log (+2)". */
    public static String describe(SalePlan plan) {
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (SalePlan.Line line : plan.lines()) {
            if (shown == 4) {
                out.append(" (+").append(plan.lines().size() - shown).append(')');
                break;
            }
            if (shown > 0) out.append(", ");
            out.append(line.amount()).append(' ').append(WorthService.name(line.sample()));
            shown++;
        }
        return out.toString();
    }

    // -- Selling -----------------------------------------------------------------------------------

    /**
     * Sells for {@code player}. A bulk sale ({@code confirm}) is first shown, and sells when
     * {@code command} is repeated unchanged within {@code confirm-bulk-seconds}.
     */
    public void sell(Player player, SalePlan.Scope scope, @Nullable ItemStack target, SaleQuantity quantity, String command,
                     boolean confirm) {
        if (closed || !PaidWorkflowSupport.ready(plugin)) {
            send(player, Messages.SELL_UNAVAILABLE);
            return;
        }
        if (!gameModeAllows(player)) {
            send(player, Messages.SELL_GAMEMODE);
            return;
        }
        if (busy(player) || UtilitySupport.isInventoryBusy(plugin, player) || !emptyCursor(player)) {
            send(player, Messages.UTILITY_INVENTORY_BUSY, "{player}", player.getName());
            return;
        }
        SalePlan plan = plan(player, scope, target, quantity);
        if (plan == null) {
            return;
        }
        UUID account = player.getUniqueId();
        int seconds = worth.settings().confirmBulkSeconds();
        if (confirm && seconds > 0) {
            Pending pending = confirmations.remove(account);
            long now = System.currentTimeMillis();
            if (pending == null || pending.expires() < now || !pending.command().equals(command)
                    || pending.total() != plan.total().minor() || pending.count() != plan.count()) {
                confirmations.put(account, new Pending(command, plan.total().minor(), plan.count(), now + seconds * 1000L));
                send(player, Messages.SELL_CONFIRM, "{items}", describe(plan), "{total}", worth.format(plan.total()),
                        "{command}", command, "{seconds}", Integer.toString(seconds));
                return;
            }
        }
        start(player, plan);
    }

    private void start(Player player, SalePlan plan) {
        UUID account = player.getUniqueId();
        busy.add(account);
        if (!submit(player, () -> {
            String refusal;
            try {
                EconomyRuntime runtime = PaidWorkflowSupport.runtime(plugin);
                EconomyResult.Balance balance = runtime.synchronousBalance(account);
                if (balance instanceof EconomyResult.Found found) {
                    long after = found.book().minor() + plan.total().minor();
                    refusal = after > runtime.engine().settings().maximum().minor() ? "CAP" : null;
                } else {
                    refusal = balance instanceof EconomyResult.Missing ? "MISSING_ACCOUNT" : "UNAVAILABLE";
                }
            } catch (SQLException unavailable) {
                refusal = "UNAVAILABLE";
            }
            String refused = refusal;
            onServer(() -> {
                if (refused == null) {
                    remove(player, plan);
                    return;
                }
                busy.remove(account);
                if (refused.equals("UNAVAILABLE")) send(player, Messages.ECO_STORAGE);
                else EconomyRuntime.sendRefusal(plugin, player, refused);
            });
        })) {
            busy.remove(account);
            send(player, Messages.ECO_BUSY);
        }
    }

    /** Server thread: the account can take the money. Remove the items, record the sale, save both. */
    private void remove(Player player, SalePlan plan) {
        UUID account = player.getUniqueId();
        if (closed || plugin.getServer().getPlayer(account) != player || !player.isOnline()) {
            busy.remove(account);
            return;
        }
        if (!unchanged(player, plan) || !emptyCursor(player) || !gameModeAllows(player)) {
            busy.remove(account);
            send(player, Messages.SELL_CHANGED);
            return;
        }
        byte[] items;
        try {
            items = pack(codec.encode(plan.removed()));
        } catch (IOException | IllegalArgumentException tooLarge) {
            busy.remove(account);
            send(player, Messages.SELL_TOO_MANY);
            return;
        }
        Map<UUID, Money> receipts;
        try {
            receipts = SaleReceipts.parse(receipt(player));
        } catch (IllegalArgumentException malformed) {
            busy.remove(account);
            plugin.logger().warning(player.getName() + "'s sale receipt is unreadable; selling is refused until staff clear "
                    + PlayerDataReceiptProof.SALE_RECEIPTS + ": " + malformed.getMessage());
            send(player, Messages.ECO_STORAGE);
            return;
        }
        UUID sale = UUID.randomUUID();
        var inventory = player.getInventory();
        plan.after().forEach(inventory::setItem);
        receipts.put(sale, plan.total());
        setReceipt(player, receipts);
        player.getPersistentDataContainer().set(itemsKey(sale), PersistentDataType.BYTE_ARRAY, items);
        player.saveData();
        String expected = receipt(player);
        if (FaultInjection.trip(FAULT_HALT_AFTER_SAVE)) {
            plugin.logger().warning("Fault " + FAULT_HALT_AFTER_SAVE + ": halting with sale " + sale + " saved but not paid.");
            Runtime.getRuntime().halt(77);
        }
        Path file = file(account);
        if (!submit(player, () -> {
            Settlement settled;
            try {
                PaidWorkflowSupport.prove(file, PlayerDataReceiptProof.SALE_RECEIPTS, expected);
                settled = settle(PaidWorkflowSupport.runtime(plugin), account, sale, plan.total());
            } catch (SQLException unproven) {
                // The saved file may still hold the items: never pay before it lists the sale.
                plugin.logger().warning("Sale " + sale + " by " + player.getName() + " waits for a confirmed save: "
                        + unproven.getMessage());
                settled = new Unsettled(String.valueOf(unproven.getMessage()));
            }
            if (settled instanceof Paid && FaultInjection.trip(FAULT_HALT_AFTER_PAY)) {
                plugin.logger().warning("Fault " + FAULT_HALT_AFTER_PAY + ": halting with sale " + sale + " paid, receipt kept.");
                Runtime.getRuntime().halt(77);
            }
            Settlement result = settled;
            onServer(() -> finish(player, sale, plan, result));
        })) {
            busy.remove(account);
            send(player, Messages.SELL_PENDING, "{operation}", sale.toString());
        }
    }

    private void finish(Player player, UUID sale, SalePlan plan, Settlement result) {
        UUID account = player.getUniqueId();
        busy.remove(account);
        boolean here = plugin.getServer().getPlayer(account) == player && player.isOnline();
        if (result instanceof Paid paid) {
            if (here) forget(player, sale);
            if (paid.done() != null) logTrade(paid, player);
            if (plan.lines().size() == 1) {
                SalePlan.Line line = plan.lines().get(0);
                send(player, Messages.SELL_SOLD, "{amount}", Integer.toString(line.amount()),
                        "{item}", WorthService.name(line.sample()), "{total}", worth.format(line.total()),
                        "{each}", worth.format(line.each()));
            } else {
                send(player, Messages.SELL_SOLD_BULK, "{items}", describe(plan), "{total}", worth.format(plan.total()));
            }
            UtilitySupport.audit(plugin, player, "sell", "sale=" + sale + " items=" + plan.count() + " total=" + plan.total().minor());
        } else if (result instanceof Refused refused) {
            if (!here) return;
            giveBack(player, sale, plan.removed());
            EconomyRuntime.sendRefusal(plugin, player, refused.reason());
            send(player, Messages.SELL_RETURNED);
        } else {
            send(player, Messages.SELL_PENDING, "{operation}", sale.toString());
        }
    }

    /** IO thread: pays {@code sale}, or learns how it was decided before. */
    private Settlement settle(EconomyRuntime runtime, UUID account, UUID sale, Money total) {
        UUID operation = SaleReceipts.operation(sale);
        EconomyRequest request = runtime.engine().request(operation, "SELL", sale.toString(), EconomyRequest.Kind.DEPOSIT,
                account.toString(), account, null, total);
        EconomyResult.Mutation result = runtime.synchronous(request);
        if (result instanceof EconomyResult.Committed done) {
            return new Paid(request, done);
        }
        if (result instanceof EconomyResult.Rejected rejected && !rejected.reason().equals("IDEMPOTENCY_CONFLICT")) {
            return new Refused(rejected.reason());
        }
        if (result instanceof EconomyResult.Rejected) {
            // Decided before under other economy settings: the recorded outcome says how.
            try {
                var outcome = runtime.engine().outcome(operation);
                if (outcome.isPresent() && outcome.get().source().equals(account) && outcome.get().amount().equals(total)) {
                    return outcome.get().committed() ? new Paid(request, null) : new Refused(outcome.get().code());
                }
                return new Unsettled("operation " + operation + " belongs to another request");
            } catch (SQLException unavailable) {
                return new Unsettled(String.valueOf(unavailable.getMessage()));
            }
        }
        return new Unsettled(result instanceof EconomyResult.Unknown unknown ? "outcome unknown: " + unknown.diagnostic()
                : "economy unavailable");
    }

    private void logTrade(Paid paid, Player player) {
        try {
            PaidWorkflowSupport.runtime(plugin).logTrade(paid.request(), paid.done(), player.getName(), player.getName());
        } catch (SQLException ignored) {
            // The economy closed after paying; the payment stands and only the log line is lost.
        }
    }

    // -- Recovery ----------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        recover(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        busy.remove(id);
        confirmations.remove(id);
    }

    /**
     * Settles the sales listed both in {@code player}'s data and in their saved file: paid, or their
     * items given back. A sale only in the live data is not saved yet and waits.
     */
    private void recover(Player player) {
        if (!PaidWorkflowSupport.ready(plugin) || busy(player)) {
            return;
        }
        Map<UUID, Money> live;
        try {
            live = SaleReceipts.parse(receipt(player));
        } catch (IllegalArgumentException malformed) {
            plugin.logger().warning(player.getName() + "'s sale receipt is unreadable and was not settled: " + malformed.getMessage());
            return;
        }
        if (live.isEmpty()) {
            return;
        }
        UUID account = player.getUniqueId();
        busy.add(account);
        player.saveData();
        Path file = file(account);
        if (!submit(player, () -> {
            Map<UUID, Settlement> settled = new LinkedHashMap<>();
            try {
                Map<UUID, Money> saved = SaleReceipts.parse(PlayerDataReceiptProof.read(file, PlayerDataReceiptProof.SALE_RECEIPTS));
                EconomyRuntime runtime = PaidWorkflowSupport.runtime(plugin);
                for (var sale : live.entrySet()) {
                    if (sale.getValue().equals(saved.get(sale.getKey()))) {
                        settled.put(sale.getKey(), settle(runtime, account, sale.getKey(), sale.getValue()));
                    }
                }
            } catch (SQLException | IOException | RuntimeException error) {
                plugin.logger().warning("Sale recovery for " + player.getName() + " is retried on their next join: " + error.getMessage());
            }
            onServer(() -> {
                busy.remove(account);
                if (plugin.getServer().getPlayer(account) != player || !player.isOnline()) {
                    return;
                }
                boolean returned = false;
                for (var entry : settled.entrySet()) {
                    UUID sale = entry.getKey();
                    Settlement result = entry.getValue();
                    if (result instanceof Paid paid) {
                        forget(player, sale);
                        if (paid.done() != null) logTrade(paid, player);
                        send(player, Messages.SELL_RECOVERED_PAID, "{total}", worth.format(live.get(sale)));
                        plugin.logger().info("Paid interrupted sale " + sale + " of " + live.get(sale) + " to " + player.getName() + ".");
                    } else if (result instanceof Refused refused) {
                        List<ItemStack> items = items(player, sale);
                        if (items == null) {
                            plugin.logger().warning("Sale " + sale + " by " + player.getName() + " was refused (" + refused.reason()
                                    + ") but its items could not be read; the receipt is kept for staff.");
                            continue;
                        }
                        giveBack(player, sale, items);
                        returned = true;
                        send(player, Messages.SELL_RECOVERED_RETURNED);
                        plugin.logger().info("Gave back the items of refused sale " + sale + " (" + refused.reason() + ") to "
                                + player.getName() + ".");
                    } else if (result instanceof Unsettled unsettled) {
                        plugin.logger().warning("Sale " + sale + " by " + player.getName()
                                + " is still unsettled (" + unsettled.why() + "); retried on their next join.");
                    }
                }
                if (!returned && !settled.isEmpty()) player.saveData();
            });
        })) {
            busy.remove(account);
        }
    }

    // -- Helpers -----------------------------------------------------------------------------------

    /** Gives {@code items} back and drops the sale, saved together; what does not fit falls at the player's feet. */
    private void giveBack(Player player, UUID sale, List<ItemStack> items) {
        var leftover = player.getInventory().addItem(items.stream().map(ItemStack::clone).toArray(ItemStack[]::new));
        forget(player, sale);
        player.saveData();
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    private void forget(Player player, UUID sale) {
        try {
            Map<UUID, Money> receipts = SaleReceipts.parse(receipt(player));
            receipts.remove(sale);
            setReceipt(player, receipts);
        } catch (IllegalArgumentException malformed) {
            plugin.logger().warning(player.getName() + "'s sale receipt is unreadable; sale " + sale + " was settled but stays listed.");
        }
        player.getPersistentDataContainer().remove(itemsKey(sale));
    }

    @Nullable
    private List<ItemStack> items(Player player, UUID sale) {
        byte[] packed = player.getPersistentDataContainer().get(itemsKey(sale), PersistentDataType.BYTE_ARRAY);
        if (packed == null) return null;
        try {
            return codec.decode(unpack(packed));
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static byte[] pack(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (bytes.size() > MAX_ITEM_BYTES) throw new IOException("sold items are too large to keep");
        return bytes.toByteArray();
    }

    private static String unpack(byte[] packed) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            byte[] text = gzip.readNBytes(MailItemCodec.MAX_CHARS * 4 + 1);
            if (text.length > MailItemCodec.MAX_CHARS * 4) throw new IOException("stored items are too large");
            return new String(text, StandardCharsets.UTF_8);
        }
    }

    private NamespacedKey itemsKey(UUID sale) {
        return new NamespacedKey(plugin, "sale_items_" + sale);
    }

    private static boolean unchanged(Player player, SalePlan plan) {
        var inventory = player.getInventory();
        for (var slot : plan.before().entrySet()) {
            ItemStack now = inventory.getItem(slot.getKey());
            if (now == null || !now.equals(slot.getValue())) return false;
        }
        return true;
    }

    private static boolean emptyCursor(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        return cursor == null || cursor.getType().isAir();
    }

    @Nullable
    private String receipt(Player player) {
        return player.getPersistentDataContainer().get(receiptKey, PersistentDataType.STRING);
    }

    private void setReceipt(Player player, Map<UUID, Money> sales) {
        String value = SaleReceipts.format(sales);
        if (value == null) player.getPersistentDataContainer().remove(receiptKey);
        else player.getPersistentDataContainer().set(receiptKey, PersistentDataType.STRING, value);
    }

    private Path file(UUID account) {
        return playerData.resolve(account + ".dat");
    }

    private void send(Player player, Messages message, String... replacements) {
        if (player.isOnline()) plugin.getMessageManager().send(player, message, replacements);
    }

    private boolean submit(Player player, Runnable task) {
        try {
            io.execute(task);
            return true;
        } catch (RejectedExecutionException full) {
            plugin.logger().warning("The sell queue is full; " + player.getName() + "'s sale was not started.");
            return false;
        }
    }

    private void onServer(Runnable task) {
        plugin.getScheduler().runOnServer(() -> {
            if (!closed) task.run();
        });
    }
}
