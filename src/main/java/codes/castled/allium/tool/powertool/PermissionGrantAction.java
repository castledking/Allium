package codes.castled.allium.tool.powertool;

import codes.castled.allium.creative.manage.CreativeHolder;
import codes.castled.allium.creative.manage.PermissionBackend;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * The {@code grant} action: gives the player permission nodes they lack, through LuckPerms (or Vault
 * with a permissions plugin), as {@code lp user <player> permission set <node> true} would. See
 * {@link PowerToolGrants} for the value and {@code pick}.
 *
 * <p>A node counts as held when LuckPerms gives it to the player (on their own, from a group or a
 * wildcard), not merely because they are an operator or by a plugin's default, so an operator can
 * still be given one. Without LuckPerms it counts when it is set for them and true. With
 * nothing left to give it does not run, which as a {@code required} action keeps a voucher unspent.
 * If the permissions plugin fails to save a node, a single-use consumable tool is given back once.
 */
public final class PermissionGrantAction implements PowerToolActionHandler {

    /** Sends a lang message to a player. */
    @FunctionalInterface
    public interface Notifier {
        void send(CommandSender recipient, Messages message, String... replacements);
    }

    private final Supplier<PermissionBackend> permissions;
    private final TaskScheduler scheduler;
    private final Notifier notifier;
    private final Consumer<String> info;
    private final Consumer<String> warning;
    private final RandomGenerator random;
    private final Function<String, ItemStack> refund;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /**
     * @param refund for a tool id, a fresh copy to give back when the tool is a single-use
     *               consumable, else null
     */
    public PermissionGrantAction(Supplier<PermissionBackend> permissions, TaskScheduler scheduler, Notifier notifier,
                                 Consumer<String> info, Consumer<String> warning, RandomGenerator random,
                                 Function<String, ItemStack> refund) {
        this.permissions = permissions;
        this.scheduler = scheduler;
        this.notifier = notifier;
        this.info = info;
        this.warning = warning;
        this.random = random;
        this.refund = refund;
    }

    @Override
    public boolean execute(PowerToolContext ctx, PowerToolAction action) {
        Player player = ctx.player();
        List<PowerToolGrants.Choice> choices = PowerToolGrants.parse(action.value());
        if (choices.isEmpty()) {
            warnOnce("empty:" + ctx.toolId(), "PowerTool '" + ctx.toolId() + "' has a grant action with no valid permission node.");
            return false;
        }
        PermissionBackend backend = permissions.get();
        if (backend == null || !backend.canWrite()) {
            notifier.send(player, Messages.POWERTOOL_GRANT_UNAVAILABLE);
            warnOnce("backend", "PowerTool '" + ctx.toolId() + "' could not grant a permission: it needs LuckPerms, or Vault with a"
                    + " permissions plugin.");
            return false;
        }
        CreativeHolder holder = CreativeHolder.player(player.getUniqueId(), player.getName());
        List<PowerToolGrants.Choice> picked = PowerToolGrants.select(choices, node -> {
            Boolean known = backend.holds(holder, node);
            return known != null ? known : player.isPermissionSet(node) && player.hasPermission(node);
        }, PowerToolGrants.pickAll(action.params()), random);
        if (picked.isEmpty()) {
            notifier.send(player, Messages.POWERTOOL_GRANT_NOTHING);
            return false;
        }
        PermissionBackend.Actor actor = new PermissionBackend.Actor(null, "Allium powertool " + ctx.toolId());
        boolean[] refunded = {false};
        for (PowerToolGrants.Choice choice : picked) {
            CompletableFuture<Void> saved;
            try {
                saved = backend.writeNode(holder, choice.node(), true, actor);
            } catch (RuntimeException failure) {
                saved = CompletableFuture.failedFuture(failure);
            }
            saved.whenComplete((done, error) -> scheduler.runOnServer(() -> settle(ctx, player, choice.node(), error, refunded)));
        }
        return true;
    }

    private void settle(PowerToolContext ctx, Player player, String node, @Nullable Throwable error, boolean[] refunded) {
        if (error == null) {
            info.accept("PowerTool '" + ctx.toolId() + "' granted " + node + " to " + player.getName() + ".");
            if (player.isOnline()) notifier.send(player, Messages.POWERTOOL_GRANTED, "{permission}", node);
            return;
        }
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        warning.accept("PowerTool '" + ctx.toolId() + "' could not grant " + node + " to " + player.getName() + ": " + cause);
        if (!refunded[0]) {
            ItemStack back = refund.apply(ctx.toolId());
            if (back != null && player.isOnline()) {
                refunded[0] = true;
                player.getInventory().addItem(back).values()
                        .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
                info.accept("Gave " + player.getName() + " back their " + ctx.toolId() + " after the failed grant.");
                notifier.send(player, Messages.POWERTOOL_GRANT_REFUNDED, "{permission}", node);
                return;
            }
        }
        if (player.isOnline()) notifier.send(player, Messages.POWERTOOL_GRANT_FAILED, "{permission}", node);
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) warning.accept(message);
    }
}
