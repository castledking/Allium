package codes.castled.allium.command.items;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.item.delivery.DeliveryOutcome;
import codes.castled.allium.item.delivery.ItemDeliveryService;
import codes.castled.allium.item.delivery.OverflowPolicy;
import codes.castled.allium.item.library.ItemBuildException;
import codes.castled.allium.item.library.ItemLibrary;
import codes.castled.allium.item.spec.GiveSpec;
import codes.castled.allium.item.spec.ItemReference;
import codes.castled.allium.item.spec.ItemSpecException;
import codes.castled.allium.item.spec.ItemSpecParser;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * {@code /give <player> <item[,item...]> [amount] [key:value...] [-e] [--overflow=...]}: the M1
 * harness for the item library. Each target gets its own freshly built items, so signed items
 * are never shared between players.
 */
public final class GiveCommand implements SpecCommandHandler {

    public static final String PERMISSION_PRIVILEGED = "allium.give.privileged";

    private final PluginStart plugin;
    private final Supplier<ItemSpecParser> parser;
    private final ItemLibrary library;
    private final ItemDeliveryService delivery;
    private final Supplier<OverflowPolicy> defaultPolicy;

    public GiveCommand(PluginStart plugin, Supplier<ItemSpecParser> parser, ItemLibrary library,
                       ItemDeliveryService delivery, Supplier<OverflowPolicy> defaultPolicy) {
        this.plugin = plugin;
        this.parser = parser;
        this.library = library;
        this.delivery = delivery;
        this.defaultPolicy = defaultPolicy;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.GIVE;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (args.length < 2) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        List<Player> targets = targets(context, args[0]);
        if (targets.isEmpty()) {
            return;
        }
        GiveSpec spec;
        try {
            spec = parser.get().parse(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        } catch (ItemSpecException e) {
            context.sendOutcome(sender, "invalid", "{reason}", e.getMessage());
            return;
        }
        OverflowPolicy policy = defaultPolicy.get();
        if (spec.overflow().isPresent()) {
            policy = OverflowPolicy.fromConfig(spec.overflow().get()).orElse(null);
            if (policy == null) {
                context.sendOutcome(sender, "invalid", "{reason}", "--overflow must be drop, refuse or partial");
                return;
            }
        }
        String itemText = spec.items().stream().map(ItemReference::text).collect(Collectors.joining(","));
        boolean mayGivePrivileged = sender.hasPermission(PERMISSION_PRIVILEGED);
        for (Player target : targets) {
            ItemLibrary.Prepared prepared;
            try {
                prepared = library.build(spec, mayGivePrivileged);
            } catch (ItemBuildException.Privileged e) {
                context.sendOutcome(sender, "privileged", "{item}", itemText, "{behavior}", e.behavior(), "{permission}", PERMISSION_PRIVILEGED);
                return;
            } catch (ItemBuildException.Unresolved e) {
                context.sendOutcome(sender, "unresolved", "{item}", itemText, "{status}", e.status().name(), "{reason}", e.getMessage());
                return;
            } catch (ItemBuildException e) {
                context.sendOutcome(sender, "failed", "{item}", itemText, "{reason}", e.getMessage());
                return;
            }
            if (prepared.async()) {
                context.sendOutcome(sender, "pending", "{player}", target.getName());
            }
            String targetName = target.getName();
            OverflowPolicy chosen = policy;
            delivery.deliver(sender, target, prepared, spec.equip(), chosen)
                    .whenComplete((outcome, error) -> report(context, target, targetName, itemText, chosen, outcome, error));
        }
    }

    private void report(BukkitCommandContext context, Player target, String targetName, String item, OverflowPolicy policy,
                        DeliveryOutcome outcome, Throwable error) {
        CommandSender sender = context.sender();
        if (error != null) {
            plugin.logger().warning("Give to " + targetName + " failed: " + error);
            context.sendOutcome(sender, "failed", "{item}", item, "{reason}", String.valueOf(error.getMessage()));
            return;
        }
        String[] values = {"{player}", targetName, "{item}", item, "{amount}", Integer.toString(outcome.requested()),
                "{given}", Integer.toString(outcome.given()), "{dropped}", Integer.toString(outcome.dropped()),
                "{leftover}", Integer.toString(outcome.leftover()), "{policy}", policy.configName()};
        switch (outcome.status()) {
            case DELIVERED -> {
                context.sendOutcome(sender, outcome.dropped() > 0 ? "dropped" : "delivered", values);
                if (target.isOnline() && target != sender) {
                    context.sendOutcome(target, "received", values);
                }
            }
            case PARTIAL -> context.sendOutcome(sender, "partial", values);
            case REFUSED_FULL -> context.sendOutcome(sender, "refused", values);
            case TARGET_OFFLINE -> context.sendOutcome(sender, "offline", values);
            case CANCELLED_BY_EVENT -> context.sendOutcome(sender, "cancelled", values);
            case FAILED -> context.sendOutcome(sender, "failed", "{item}", item, "{reason}", "delivery failed");
        }
        for (String warning : outcome.warnings()) {
            context.sendOutcome(sender, "warning", "{warning}", warning);
        }
    }

    /** A player name, or a selector ({@code @p}, {@code @a}, {@code @r}, {@code @s}, ...) filtered to players. */
    private List<Player> targets(BukkitCommandContext context, String raw) {
        List<Player> out = new ArrayList<>();
        if (raw.startsWith("@")) {
            try {
                for (Entity entity : plugin.getServer().selectEntities(context.sender(), raw)) {
                    if (entity instanceof Player player) {
                        out.add(player);
                    }
                }
            } catch (IllegalArgumentException e) {
                context.sendOutcome(context.sender(), "invalid", "{reason}", "bad selector " + raw + ": " + e.getMessage());
                return List.of();
            }
        } else {
            Player player = plugin.getServer().getPlayerExact(raw);
            if (player != null) {
                out.add(player);
            }
        }
        if (out.isEmpty()) {
            plugin.getMessageManager().sendByKey(context.sender(), spec().messageKeys().playerNotFoundKey(), "{player}", raw);
        }
        return out;
    }
}
