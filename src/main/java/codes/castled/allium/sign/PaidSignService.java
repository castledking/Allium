package codes.castled.allium.sign;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.economy.EconomyDisplay;
import codes.castled.allium.economy.EconomyRequest;
import codes.castled.allium.economy.EconomyReservations;
import codes.castled.allium.economy.EconomyResult;
import codes.castled.allium.economy.EconomyRuntime;
import codes.castled.allium.economy.PaidWorkflowSupport;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Reserves before delivery and captures only after the action confirms success. */
public final class PaidSignService {
    @FunctionalInterface
    interface Action {
        /** Reports actual delivery, once. Deferred actions check validity again before delivering. */
        void deliver(BooleanSupplier valid, Consumer<Boolean> complete);
    }

    private final PluginStart plugin;
    private final Executor io;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public PaidSignService(PluginStart plugin) {
        this.plugin = plugin;
        this.io = plugin.getScheduler().boundedPool("Allium-PaidSign", 1, 128);
    }

    public void close() {
        closed = true;
    }

    public boolean busy(Player player) {
        return player != null && busy.contains(player.getUniqueId());
    }

    void start(Player player, SignIdentity identity, BooleanSupplier allowed, Action action) {
        if (closed || !PaidWorkflowSupport.ready(plugin)) {
            plugin.getMessageManager().send(player, Messages.SIGN_PAID_UNAVAILABLE);
            return;
        }
        UUID account = player.getUniqueId();
        if (!busy.add(account)) {
            plugin.getMessageManager().send(player, Messages.ECO_BUSY);
            return;
        }
        var purchase = new Purchase(player, account, UUID.randomUUID(), identity,
                EconomyDisplay.read(plugin.getConfig()).format(identity.price()));
        try {
            io.execute(() -> reserve(purchase, allowed, action));
        } catch (RejectedExecutionException full) {
            busy.remove(account);
            plugin.getMessageManager().send(player, Messages.ECO_BUSY);
        }
    }

    private record Purchase(Player player, UUID account, UUID workflow, SignIdentity sign, String price) {}

    private void reserve(Purchase purchase, BooleanSupplier allowed, Action action) {
        if (closed) {
            forget(purchase);
            return;
        }
        EconomyResult.Mutation result;
        try {
            var sign = purchase.sign();
            result = PaidWorkflowSupport.reservations(plugin).reserve(EconomyReservations.Kind.SIGN,
                    purchase.workflow(), purchase.account(), sign.price(),
                    EconomyReservations.fingerprint("SIGN", sign.id().toString(), sign.type().key(),
                            sign.argument(), Long.toString(sign.price().minor())), c -> true);
        } catch (SQLException | RuntimeException failure) {
            warn(purchase, "could not reserve the price", failure);
            onPlayer(purchase, () -> finish(purchase, Messages.ECO_STORAGE), () -> forget(purchase));
            return;
        }
        if (result instanceof EconomyResult.Committed && closed) {
            settleOnWorker(purchase, false);
        } else {
            onPlayer(purchase, () -> {
                if (result instanceof EconomyResult.Committed) {
                    deliver(purchase, allowed, action);
                } else if (result instanceof EconomyResult.Rejected refused) {
                    forget(purchase);
                    if (!current(purchase)) return;
                    if ("INSUFFICIENT_FUNDS".equals(refused.reason()) || "MISSING_ACCOUNT".equals(refused.reason())) {
                        plugin.getMessageManager().send(purchase.player(), Messages.SIGN_INSUFFICIENT_FUNDS,
                                "{price}", purchase.price());
                    } else EconomyRuntime.sendRefusal(plugin, purchase.player(), refused.reason());
                } else if (result instanceof EconomyResult.Unknown unknown) {
                    // Delivery never started, so even an uncertain reserve may be released safely.
                    if (current(purchase)) plugin.getMessageManager().send(purchase.player(), Messages.ECO_UNKNOWN,
                            "{operation}", unknown.operationId().toString());
                    settle(purchase, false);
                } else finish(purchase, Messages.ECO_STORAGE);
            }, () -> {
                if (result instanceof EconomyResult.Committed || result instanceof EconomyResult.Unknown) {
                    settle(purchase, false);
                } else {
                    forget(purchase);
                }
            });
        }
    }

    private void deliver(Purchase purchase, BooleanSupplier allowed, Action action) {
        var completed = new AtomicBoolean();
        BooleanSupplier valid = () -> !completed.get() && current(purchase) && allowed.getAsBoolean();
        Consumer<Boolean> outcome = delivered -> {
            if (completed.compareAndSet(false, true)) settle(purchase, delivered);
        };
        try {
            if (valid.getAsBoolean()) action.deliver(valid, outcome);
            else outcome.accept(false);
        } catch (RuntimeException failure) {
            warn(purchase, "action failed", failure);
            outcome.accept(false);
        }
    }

    private void settle(Purchase purchase, boolean delivered) {
        try {
            io.execute(() -> settleOnWorker(purchase, delivered));
        } catch (RejectedExecutionException full) {
            warn(purchase, "could not queue settlement; restart recovery will release any outstanding hold", full);
            onPlayer(purchase, () -> finish(purchase, Messages.ECO_STORAGE), () -> forget(purchase));
        }
    }

    private void settleOnWorker(Purchase purchase, boolean delivered) {
        boolean settled = false;
        try {
            var holds = PaidWorkflowSupport.reservations(plugin);
            var result = delivered ? holds.capture(EconomyReservations.Kind.SIGN, purchase.workflow(), c -> true)
                    : holds.release(EconomyReservations.Kind.SIGN, purchase.workflow(), c -> true);
            settled = result instanceof EconomyResult.Committed
                    || !delivered && result instanceof EconomyResult.Rejected refused
                    && "MISSING_RESERVATION".equals(refused.reason());
            if (!settled) warn(purchase, "settlement unconfirmed; restart recovery will release any outstanding hold", null);
        } catch (SQLException | RuntimeException failure) {
            warn(purchase, "settlement failed; restart recovery will release any outstanding hold", failure);
        }
        boolean confirmed = settled;
        onPlayer(purchase, () -> {
            finish(purchase, confirmed ? null : Messages.ECO_STORAGE);
            if (confirmed && delivered && current(purchase)) {
                String key = purchase.sign().type().key();
                String type = Character.toUpperCase(key.charAt(0)) + key.substring(1);
                plugin.getMessageManager().send(purchase.player(), Messages.SIGN_PAID,
                        "{price}", purchase.price(), "{type}", type);
                UUID operation = EconomyReservations.operation(EconomyReservations.Kind.SIGN, purchase.workflow(),
                        EconomyRequest.Kind.CAPTURE);
                UtilitySupport.audit(plugin, purchase.player(), "paid_sign",
                        "type=" + key + " arg=" + purchase.sign().argument() + " price=" + purchase.sign().price().minor()
                                + " op=" + operation + " workflow=" + purchase.workflow());
            }
        }, () -> forget(purchase));
    }

    private void onPlayer(Purchase purchase, Runnable action, Runnable retired) {
        var started = new AtomicBoolean();
        Runnable unavailable = () -> {
            if (started.compareAndSet(false, true)) retired.run();
        };
        try {
            TaskHandle handle = BukkitServerThread.runFor(plugin, plugin.getScheduler(), purchase.player(), () -> {
                if (started.compareAndSet(false, true)) action.run();
            }, unavailable, 0L);
            if (BukkitServerThread.isFolia() && handle == TaskHandle.NONE) unavailable.run();
        } catch (RuntimeException failure) {
            warn(purchase, "could not schedule player work", failure);
            unavailable.run();
        }
    }

    private void forget(Purchase purchase) {
        busy.remove(purchase.account());
    }

    private void finish(Purchase purchase, Messages message) {
        forget(purchase);
        if (message != null && current(purchase)) plugin.getMessageManager().send(purchase.player(), message);
    }

    private boolean current(Purchase purchase) {
        return !closed && purchase.player().isOnline()
                && plugin.getServer().getPlayer(purchase.account()) == purchase.player();
    }

    private void warn(Purchase purchase, String message, Throwable cause) {
        plugin.logger().warning("Paid sign " + purchase.workflow() + ": " + message
                + (cause == null ? "" : ": " + cause.getMessage()));
    }
}
