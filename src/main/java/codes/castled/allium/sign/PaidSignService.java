package codes.castled.allium.sign;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.economy.EconomyDisplay;
import codes.castled.allium.economy.EconomyRequest;
import codes.castled.allium.economy.EconomyReservations;
import codes.castled.allium.economy.EconomyResult;
import codes.castled.allium.economy.EconomyRuntime;
import codes.castled.allium.economy.PaidActionOutcome;
import codes.castled.allium.economy.PaidActionResult;
import codes.castled.allium.economy.PaidActionService;
import codes.castled.allium.economy.PaidWorkflowSupport;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.BukkitServerThread;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Paid sign adapter backed by {@link PaidActionService}: delegates reservation, revalidation,
 * typed delivery outcomes, disconnect/retirement handling, and capture/release settlement to the
 * shared paid-action lifecycle, and handles server shop {@code [Buy]} and {@code [Sell]} signs.
 */
public final class PaidSignService {

    @FunctionalInterface
    interface Action {
        /** Reports actual delivery, once. Deferred actions check validity again before delivering. */
        void deliver(BooleanSupplier valid, Consumer<Boolean> complete);
    }

    @FunctionalInterface
    interface TypedAction {
        /** Reports a typed delivery outcome, once. */
        void deliver(BooleanSupplier valid, Consumer<PaidActionOutcome> complete);
    }

    private final PluginStart plugin;
    private final Executor io;
    private final PaidActionService actions;
    private final Map<UUID, Player> selling = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public PaidSignService(PluginStart plugin) {
        this.plugin = plugin;
        this.io = plugin.getScheduler().boundedPool("Allium-PaidSign", 1, 128);
        this.actions = new PaidActionService(plugin, io);
    }

    public void close() {
        closed = true;
        actions.close();
        selling.clear();
    }

    public boolean busy(Player player) {
        UUID id = player.getUniqueId();
        return actions.busy(player) || selling.containsKey(id);
    }

    void start(Player player, SignIdentity identity, BooleanSupplier allowed, Action action) {
        startTyped(player, identity, allowed, (valid, complete) ->
                action.deliver(valid, delivered -> complete.accept(PaidActionOutcome.of(delivered))));
    }

    void startTyped(Player player, SignIdentity identity, BooleanSupplier allowed, TypedAction action) {
        UUID workflow = UUID.randomUUID();
        String price = EconomyDisplay.read(plugin.getConfig()).format(identity.price());
        String fingerprint = EconomyReservations.fingerprint(
                "SIGN", identity.id().toString(), identity.type().key(),
                identity.argument(), Long.toString(identity.price().minor()));

        actions.execute(new PaidActionService.LiveRequest(
                EconomyReservations.Kind.SIGN,
                workflow,
                player,
                identity.price(),
                fingerprint,
                "Paid sign",
                allowed,
                action::deliver,
                result -> handleResult(player, identity, price, result),
                true
        ));
    }

    /**
     * Executes a {@code [Buy]} sign via {@link PaidActionService}: reserves funds first, revalidates
     * inventory capacity on the player's thread before delivering items, and captures only once added.
     */
    void startBuy(Player player, SignIdentity identity, ItemStack template, int amount, String itemLabel, BooleanSupplier allowed) {
        if (selling.containsKey(player.getUniqueId())) {
            plugin.getMessageManager().send(player, Messages.ECO_BUSY);
            return;
        }
        UUID workflow = UUID.randomUUID();
        String price = EconomyDisplay.read(plugin.getConfig()).format(identity.price());
        String fingerprint = EconomyReservations.fingerprint(
                "BUY_SIGN", identity.id().toString(), identity.argument(), Long.toString(identity.price().minor()));

        actions.execute(new PaidActionService.LiveRequest(
                EconomyReservations.Kind.SIGN,
                workflow,
                player,
                identity.price(),
                fingerprint,
                "Buy sign",
                allowed,
                (valid, complete) -> {
                    if (!valid.getAsBoolean() || UtilitySupport.isInventoryBusy(plugin, player)) {
                        complete.accept(PaidActionOutcome.refused("inventory_busy"));
                        return;
                    }
                    if (!canFit(player.getInventory().getStorageContents(), template, amount)) {
                        plugin.getMessageManager().send(player, Messages.SIGN_BUY_INVENTORY_FULL,
                                "{amount}", Integer.toString(amount), "{item}", itemLabel);
                        complete.accept(PaidActionOutcome.refused("inventory_full"));
                        return;
                    }
                    deliverItems(player, template, amount);
                    complete.accept(PaidActionOutcome.Delivered.OK);
                },
                result -> {
                    if (result.status() == PaidActionResult.Status.CAPTURED) {
                        if (actions.isCurrentSession(player)) {
                            plugin.getMessageManager().send(player, Messages.SIGN_BUY_BOUGHT,
                                    "{amount}", Integer.toString(amount), "{item}", itemLabel, "{price}", price);
                            UtilitySupport.audit(plugin, player, "buy_sign",
                                    "arg=" + identity.argument() + " price=" + identity.price().minor()
                                            + " op=" + result.captureOperationId() + " workflow=" + result.workflowId());
                        }
                    } else {
                        handleResult(player, identity, price, result);
                    }
                },
                true
        ));
    }

    /**
     * Executes a {@code [Sell]} sign: pre-checks account/cap off-thread, revalidates and removes
     * exact matching items on the player's thread, deposits asynchronously, and restores items on
     * any pre-commit refusal or storage error.
     */
    void startSell(Player player, SignIdentity identity, ItemStack template, int amount, String itemLabel, BooleanSupplier allowed) {
        if (closed || io == null || !PaidWorkflowSupport.ready(plugin)) {
            plugin.getMessageManager().send(player, Messages.SIGN_PAID_UNAVAILABLE);
            return;
        }
        UUID account = player.getUniqueId();
        if (actions.busy(player) || selling.putIfAbsent(account, player) != null) {
            plugin.getMessageManager().send(player, Messages.ECO_BUSY);
            return;
        }
        String price = EconomyDisplay.read(plugin.getConfig()).format(identity.price());
        try {
            io.execute(() -> {
                String preRefusal;
                try {
                    EconomyRuntime runtime = PaidWorkflowSupport.runtime(plugin);
                    if (runtime == null || !runtime.ready()) {
                        preRefusal = "UNAVAILABLE";
                    } else {
                        EconomyResult.Balance balance = runtime.synchronousBalance(account);
                        if (balance instanceof EconomyResult.Found found) {
                            long max = runtime.engine().settings().maximum().minor();
                            preRefusal = found.book().minor() > max - identity.price().minor() ? "CAP" : null;
                        } else {
                            preRefusal = balance instanceof EconomyResult.Missing ? "MISSING_ACCOUNT" : "UNAVAILABLE";
                        }
                    }
                } catch (SQLException | RuntimeException failure) {
                    preRefusal = "UNAVAILABLE";
                }
                String refusal = preRefusal;
                onPlayer(player, () -> {
                    if (closed || !actions.isCurrentSession(player)) {
                        selling.remove(account, player);
                        return;
                    }
                    if (refusal != null) {
                        selling.remove(account, player);
                        if ("UNAVAILABLE".equals(refusal)) {
                            plugin.getMessageManager().send(player, Messages.ECO_STORAGE);
                        } else {
                            EconomyRuntime.sendRefusal(plugin, player, refusal);
                        }
                        return;
                    }
                    if (!allowed.getAsBoolean() || UtilitySupport.isInventoryBusy(plugin, player)) {
                        selling.remove(account, player);
                        return;
                    }
                    int available = countMatching(player.getInventory().getStorageContents(), template);
                    if (available < amount) {
                        selling.remove(account, player);
                        plugin.getMessageManager().send(player, Messages.SIGN_SELL_NOT_ENOUGH,
                                "{amount}", Integer.toString(amount),
                                "{item}", itemLabel,
                                "{available}", Integer.toString(available));
                        return;
                    }
                    List<ItemStack> removed = removeMatching(player, template, amount);
                    UUID saleId = UUID.randomUUID();
                    try {
                        io.execute(() -> {
                            EconomyResult.Mutation mutation;
                            try {
                                EconomyRuntime runtime = PaidWorkflowSupport.runtime(plugin);
                                if (runtime == null || !runtime.ready()) {
                                    mutation = new EconomyResult.Unavailable("economy unavailable");
                                } else {
                                    EconomyRequest request = runtime.engine().request(
                                            saleId, "SELL_SIGN", saleId.toString(),
                                            EconomyRequest.Kind.DEPOSIT, account.toString(),
                                            account, null, identity.price());
                                    mutation = runtime.synchronous(request);
                                }
                            } catch (SQLException | RuntimeException failure) {
                                mutation = new EconomyResult.Unavailable(String.valueOf(failure.getMessage()));
                            }
                            EconomyResult.Mutation finalMutation = mutation;
                            onPlayer(player, () -> {
                                selling.remove(account, player);
                                if (!actions.isCurrentSession(player)) {
                                    if (finalMutation instanceof EconomyResult.Rejected
                                            || finalMutation instanceof EconomyResult.Unavailable) {
                                        Player live = plugin.getServer() == null ? null : plugin.getServer().getPlayer(account);
                                        if (live != null && live.isOnline()) {
                                            onPlayer(live, () -> restoreItems(live, removed), () ->
                                                    plugin.logger().warning("Could not return refused sell-sign items to retired player "
                                                            + live.getName() + " (op=" + saleId + ")."));
                                        } else {
                                            plugin.logger().warning("Could not return refused sell-sign items to offline player "
                                                    + player.getName() + " (op=" + saleId + ", arg=" + identity.argument() + ").");
                                        }
                                    }
                                    return;
                                }
                                if (finalMutation instanceof EconomyResult.Committed) {
                                    plugin.getMessageManager().send(player, Messages.SIGN_SELL_SOLD,
                                            "{amount}", Integer.toString(amount),
                                            "{item}", itemLabel,
                                            "{price}", price);
                                    UtilitySupport.audit(plugin, player, "sell_sign",
                                            "arg=" + identity.argument() + " price=" + identity.price().minor()
                                                    + " op=" + saleId);
                                } else if (finalMutation instanceof EconomyResult.Rejected rejected) {
                                    restoreItems(player, removed);
                                    EconomyRuntime.sendRefusal(plugin, player, rejected.reason());
                                } else if (finalMutation instanceof EconomyResult.Unknown) {
                                    plugin.logger().warning("Sell sign deposit outcome unknown for " + player.getName()
                                            + " (op=" + saleId + ", price=" + identity.price().minor() + ").");
                                    plugin.getMessageManager().send(player, Messages.ECO_UNKNOWN,
                                            "{operation}", saleId.toString());
                                } else {
                                    restoreItems(player, removed);
                                    plugin.getMessageManager().send(player, Messages.ECO_STORAGE);
                                }
                            }, () -> {
                                selling.remove(account, player);
                                if (finalMutation instanceof EconomyResult.Rejected
                                        || finalMutation instanceof EconomyResult.Unavailable) {
                                    plugin.logger().warning("Could not return refused sell-sign items to retired player "
                                            + player.getName() + " (op=" + saleId + ").");
                                }
                            });
                        });
                    } catch (RejectedExecutionException busy) {
                        selling.remove(account, player);
                        restoreItems(player, removed);
                        plugin.getMessageManager().send(player, Messages.ECO_BUSY);
                    }
                }, () -> selling.remove(account, player));
            });
        } catch (RejectedExecutionException busy) {
            selling.remove(account, player);
            plugin.getMessageManager().send(player, Messages.ECO_BUSY);
        }
    }

    private void onPlayer(Player player, Runnable task, Runnable retired) {
        if (BukkitServerThread.isFolia()) {
            var owner = BukkitServerThread.entity(plugin, player);
            if (owner.isServerThread()) {
                task.run();
                return;
            }
            if (plugin.getScheduler().runOn(owner, task, retired, 0L) == codes.castled.allium.scheduler.TaskHandle.NONE) {
                retired.run();
            }
            return;
        }
        if (plugin.getScheduler().isServerThread()) {
            task.run();
        } else {
            plugin.getScheduler().runOnServer(task);
        }
    }

    private static boolean matchesItem(ItemStack template, ItemStack candidate) {
        if (template == null || candidate == null) return false;
        if (org.bukkit.Bukkit.getServer() != null) {
            return template.isSimilar(candidate);
        }
        return template.getType() == candidate.getType();
    }

    static int maxFit(ItemStack[] storage, ItemStack template) {
        if (storage == null || template == null) return 0;
        int maxStack = Math.max(1, template.getMaxStackSize());
        long capacity = 0L;
        for (ItemStack slot : storage) {
            if (slot == null || slot.getType().isAir()) {
                capacity += maxStack;
            } else if (matchesItem(template, slot) && slot.getAmount() < maxStack) {
                capacity += (maxStack - slot.getAmount());
            }
            if (capacity >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) capacity;
    }

    static boolean canFit(ItemStack[] storage, ItemStack template, int amount) {
        if (storage == null || template == null || amount <= 0) return false;
        return maxFit(storage, template) >= amount;
    }

    static void deliverItems(Player player, ItemStack template, int amount) {
        int maxStack = Math.max(1, template.getMaxStackSize());
        int remaining = amount;
        List<ItemStack> toAdd = new ArrayList<>();
        while (remaining > 0) {
            int piece = Math.min(remaining, maxStack);
            ItemStack copy = template.clone();
            copy.setAmount(piece);
            toAdd.add(copy);
            remaining -= piece;
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(toAdd.toArray(new ItemStack[0]));
        if (overflow != null && !overflow.isEmpty() && player.getWorld() != null) {
            for (ItemStack drop : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
        player.updateInventory();
    }

    static int countMatching(ItemStack[] storage, ItemStack template) {
        if (storage == null || template == null) return 0;
        int total = 0;
        for (ItemStack slot : storage) {
            if (slot != null && !slot.getType().isAir() && matchesItem(template, slot)) {
                total += slot.getAmount();
            }
        }
        return total;
    }

    static List<ItemStack> removeMatching(Player player, ItemStack template, int amount) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int remaining = amount;
        List<ItemStack> removed = new ArrayList<>();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack slot = contents[i];
            if (slot == null || slot.getType().isAir() || !matchesItem(template, slot)) continue;
            int take = Math.min(remaining, slot.getAmount());
            ItemStack taken = slot.clone();
            taken.setAmount(take);
            removed.add(taken);
            if (take >= slot.getAmount()) {
                player.getInventory().setItem(i, null);
            } else {
                ItemStack left = slot.clone();
                left.setAmount(slot.getAmount() - take);
                player.getInventory().setItem(i, left);
            }
            remaining -= take;
        }
        player.updateInventory();
        return removed;
    }

    static void restoreItems(Player player, List<ItemStack> items) {
        if (items.isEmpty()) return;
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(items.toArray(new ItemStack[0]));
        if (overflow != null && !overflow.isEmpty() && player.getWorld() != null) {
            for (ItemStack drop : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
        player.updateInventory();
    }

    private void handleResult(Player player, SignIdentity sign, String price, PaidActionResult result) {
        switch (result.status()) {
            case UNAVAILABLE -> plugin.getMessageManager().send(player, Messages.SIGN_PAID_UNAVAILABLE);
            case BUSY -> plugin.getMessageManager().send(player, Messages.ECO_BUSY);
            case INSUFFICIENT_FUNDS -> {
                if (actions.isCurrentSession(player)) {
                    plugin.getMessageManager().send(player, Messages.SIGN_INSUFFICIENT_FUNDS, "{price}", price);
                }
            }
            case RESERVATION_REFUSED -> {
                if (actions.isCurrentSession(player)) {
                    EconomyRuntime.sendRefusal(plugin, player, result.detail());
                }
            }
            case UNKNOWN_RESERVE -> {
                if (actions.isCurrentSession(player) && result.unknownOperationId() != null) {
                    plugin.getMessageManager().send(player, Messages.ECO_UNKNOWN,
                            "{operation}", result.unknownOperationId().toString());
                }
            }
            case STORAGE_ERROR, CAPTURE_DEFERRED, RELEASE_DEFERRED -> {
                if (actions.isCurrentSession(player)) {
                    plugin.getMessageManager().send(player, Messages.ECO_STORAGE);
                }
            }
            case CAPTURED -> {
                if (actions.isCurrentSession(player)) {
                    String key = sign.type().key();
                    String type = Character.toUpperCase(key.charAt(0)) + key.substring(1);
                    plugin.getMessageManager().send(player, Messages.SIGN_PAID, "{price}", price, "{type}", type);
                    UtilitySupport.audit(plugin, player, "paid_sign",
                            "type=" + key + " arg=" + sign.argument() + " price=" + sign.price().minor()
                                    + " op=" + result.captureOperationId() + " workflow=" + result.workflowId());
                }
            }
            case RELEASED -> {
                // Refused or interrupted actions already messaged the player (if applicable) and released the hold.
            }
        }
    }
}
