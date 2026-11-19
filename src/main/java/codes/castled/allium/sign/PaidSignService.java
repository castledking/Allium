package codes.castled.allium.sign;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.economy.EconomyDisplay;
import codes.castled.allium.economy.EconomyReservations;
import codes.castled.allium.economy.EconomyResult;
import codes.castled.allium.economy.Money;
import codes.castled.allium.economy.PaidWorkflowSupport;
import codes.castled.allium.message.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Journalled atomic charging for priced service signs ({@code [Warp]}, {@code [Heal]},
 * {@code [Disposal]}, {@code [Workbench]}, {@code [Anvil]}, {@code [Balance]}, {@code [Mail]},
 * {@code [Info]}). Priced {@code [Repair]} and {@code [Kit]} signs use their own receipt-backed
 * reservation services.
 */
public final class PaidSignService implements Listener {

    private final PluginStart plugin;
    private final Executor io;
    private final Set<UUID> busy = new HashSet<>();
    private volatile boolean closed;

    public PaidSignService(PluginStart plugin) {
        this.plugin = plugin;
        this.io = plugin.getScheduler().boundedPool("Allium-PaidSign", 1, 128);
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void close() {
        closed = true;
        HandlerList.unregisterAll(this);
    }

    public boolean busy(Player player) {
        return player != null && busy.contains(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        busy.remove(event.getPlayer().getUniqueId());
    }

    public void start(Player player, SignIdentity identity, Runnable action) {
        if (closed || !PaidWorkflowSupport.ready(plugin)) {
            plugin.getMessageManager().send(player, Messages.SIGN_PAID_UNAVAILABLE);
            return;
        }
        UUID account = player.getUniqueId();
        if (busy.contains(account)) {
            plugin.getMessageManager().send(player, Messages.ECO_BUSY);
            return;
        }
        Money price = identity.price();
        String formatted = EconomyDisplay.read(plugin.getConfig()).format(price);
        String typeLabel = Character.toUpperCase(identity.type().key().charAt(0)) + identity.type().key().substring(1);
        UUID workflow = UUID.randomUUID();
        busy.add(account);
        try {
            io.execute(() -> {
                EconomyResult.Mutation result;
                try {
                    result = PaidWorkflowSupport.reservations(plugin).spend(
                            EconomyReservations.Kind.SIGN,
                            workflow,
                            account,
                            price,
                            EconomyReservations.fingerprint("SIGN", workflow.toString(), identity.id().toString(),
                                    identity.type().key(), identity.argument(), Long.toString(price.minor())),
                            c -> true);
                } catch (SQLException error) {
                    plugin.logger().warning("Paid sign [" + typeLabel + "] for " + player.getName()
                            + " failed: " + error.getMessage());
                    onServer(() -> {
                        busy.remove(account);
                        plugin.getMessageManager().send(player, Messages.ECO_STORAGE);
                    });
                    return;
                }
                onServer(() -> {
                    busy.remove(account);
                    if (closed || plugin.getServer().getPlayer(account) != player || !player.isOnline()) return;
                    if (result instanceof EconomyResult.Committed done) {
                        plugin.getMessageManager().send(player, Messages.SIGN_PAID,
                                "{price}", formatted, "{type}", typeLabel);
                        UtilitySupport.audit(plugin, player, "paid_sign",
                                "type=" + identity.type().key() + " arg=" + identity.argument()
                                        + " price=" + price.minor() + " op=" + done.operationId());
                        action.run();
                    } else if (result instanceof EconomyResult.Rejected refused) {
                        if ("INSUFFICIENT_FUNDS".equals(refused.reason()) || "MISSING_ACCOUNT".equals(refused.reason())) {
                            plugin.getMessageManager().send(player, Messages.SIGN_INSUFFICIENT_FUNDS, "{price}", formatted);
                        } else {
                            plugin.getMessageManager().send(player, Messages.ECO_OPERATION_REFUSED, "{reason}", refused.reason());
                        }
                    } else if (result instanceof EconomyResult.Unknown unknown) {
                        plugin.getMessageManager().send(player, Messages.ECO_UNKNOWN, "{operation}", unknown.operationId().toString());
                    } else {
                        plugin.getMessageManager().send(player, Messages.ECO_STORAGE);
                    }
                });
            });
        } catch (RejectedExecutionException full) {
            busy.remove(account);
            plugin.getMessageManager().send(player, Messages.ECO_BUSY);
        }
    }

    private void onServer(Runnable task) {
        plugin.getScheduler().runOnServer(() -> {
            if (!closed) task.run();
        });
    }
}
