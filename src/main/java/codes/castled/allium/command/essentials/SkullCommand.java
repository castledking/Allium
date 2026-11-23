package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.item.library.ItemBuildException;
import codes.castled.allium.item.library.SkullProfiles;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.BukkitServerThread;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class SkullCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final Supplier<SkullProfiles> skullsSupplier;
    private final Supplier<ItemStack> headFactory;

    public SkullCommand(PluginStart plugin, Supplier<SkullProfiles> skullsSupplier) {
        this(plugin, skullsSupplier, () -> new ItemStack(Material.PLAYER_HEAD));
    }

    SkullCommand(PluginStart plugin, Supplier<SkullProfiles> skullsSupplier, Supplier<ItemStack> headFactory) {
        this.plugin = plugin;
        this.skullsSupplier = skullsSupplier;
        this.headFactory = headFactory;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.SKULL;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        String[] args = context.args();
        if (args.length > 2) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }

        String ownerName;
        Player target;
        if (args.length == 0) {
            if (!(context.sender() instanceof Player self)) {
                context.sendInvalidArgs(spec().consoleUsage());
                return;
            }
            target = self;
            ownerName = self.getName();
        } else if (args.length == 1) {
            if (!(context.sender() instanceof Player self)) {
                context.sendInvalidArgs(spec().consoleUsage());
                return;
            }
            target = self;
            ownerName = args[0];
        } else {
            ownerName = args[0];
            target = context.resolveSelfOrTarget(1);
            if (target == null) {
                return;
            }
        }

        if (!UtilitySupport.guardInventoryNotBusy(plugin, context.sender(), target)) {
            return;
        }

        SkullProfiles skulls = skullsSupplier.get();
        if (skulls == null) {
            UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_SKULL_INVALID, "{owner}", ownerName);
            return;
        }

        CompletableFuture<SkullProfiles.Resolved> future;
        try {
            future = skulls.resolve(ownerName);
        } catch (ItemBuildException invalid) {
            UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_SKULL_INVALID, "{owner}", ownerName);
            return;
        }

        if (future.isDone()) {
            scheduleDelivery(context, target, ownerName, future.join());
            return;
        }

        UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_SKULL_PENDING, "{owner}", ownerName);
        future.whenComplete((resolved, error) -> {
            if (BukkitServerThread.isFolia()) {
                scheduleDelivery(context, target, ownerName, resolved);
                return;
            }
            Runnable finish = () -> deliverOnTarget(context, target, ownerName, resolved);
            if (plugin.getScheduler() != null) {
                plugin.getScheduler().runOnServer(finish);
            } else {
                finish.run();
            }
        });
    }

    private void scheduleDelivery(BukkitCommandContext context, Player target, String ownerName, SkullProfiles.Resolved resolved) {
        String targetName = target.getName();
        UtilitySupport.onTarget(plugin, context.sender(), target,
                () -> deliverOnTarget(context, target, ownerName, resolved),
                () -> UtilitySupport.send(plugin, context.sender(), Messages.PLAYER_NOT_FOUND, "{player}", targetName));
    }

    private void deliverOnTarget(BukkitCommandContext context, Player target, String ownerName, SkullProfiles.Resolved resolved) {
        if (!target.isOnline()) {
            UtilitySupport.send(plugin, context.sender(), Messages.PLAYER_NOT_FOUND, "{player}", target.getName());
            return;
        }
        if (!UtilitySupport.guardInventoryNotBusy(plugin, context.sender(), target)) {
            return;
        }
        if (resolved == null) {
            UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_SKULL_INVALID, "{owner}", ownerName);
            return;
        }
        deliver(context, target, ownerName, resolved);
    }

    private void deliver(BukkitCommandContext context, Player target, String ownerName, SkullProfiles.Resolved resolved) {
        ItemStack head = headFactory.get();
        if (head.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwnerProfile(resolved.profile());
            head.setItemMeta(meta);
        }
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(head);
        if (leftover != null && !leftover.isEmpty() && target.getWorld() != null) {
            for (ItemStack drop : leftover.values()) {
                if (!UtilitySupport.isAir(drop)) {
                    target.getWorld().dropItemNaturally(target.getLocation(), drop);
                }
            }
        }
        UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_SKULL_GIVEN,
                "{owner}", ownerName,
                "{player}", target.getName());
        if (target != context.sender()) {
            UtilitySupport.send(plugin, target, Messages.UTILITY_SKULL_GIVEN,
                    "{owner}", ownerName,
                    "{player}", target.getName());
            UtilitySupport.audit(plugin, context.sender(), "skull",
                    "owner=" + ownerName + " target=" + target.getName() + " degraded=" + resolved.degraded());
        }
        if (resolved.degraded() && resolved.warning() != null) {
            UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_SKULL_DEGRADED,
                    "{owner}", ownerName,
                    "{reason}", resolved.warning());
        }
    }
}
