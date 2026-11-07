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
    /** Vanilla's selector permission (permission level 2); Paper's {@code players()} argument enforces it too. */
    public static final String PERMISSION_SELECTOR = "minecraft.command.selector";

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
        String[] args = context.args();
        if (args.length < 2) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        List<Player> targets = targets(context, args[0]);
        if (targets.isEmpty()) {
            return;
        }
        give(context.sender(), targets, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
    }

    /**
     * Gives {@code specText} ({@code <item[,item...]> [amount] [key:value...] [flags]}) to each
     * target. Shared by the Bukkit command and Paper's Brigadier registration, which resolves
     * targets from the full execution context.
     */
    public void give(CommandSender sender, List<Player> targets, String specText) {
        GiveSpec spec;
        try {
            spec = parser.get().parse(specText);
        } catch (ItemSpecException e) {
            send(sender, "invalid", "{reason}", e.getMessage());
            return;
        }
        OverflowPolicy policy = defaultPolicy.get();
        if (spec.overflow().isPresent()) {
            policy = OverflowPolicy.fromConfig(spec.overflow().get()).orElse(null);
            if (policy == null) {
                send(sender, "invalid", "{reason}", "--overflow must be drop, refuse or partial");
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
                send(sender, "privileged", "{item}", itemText, "{behavior}", e.behavior(), "{permission}", PERMISSION_PRIVILEGED);
                return;
            } catch (ItemBuildException.Unresolved e) {
                send(sender, "unresolved", "{item}", itemText, "{status}", e.status().name(), "{reason}", e.getMessage());
                return;
            } catch (ItemBuildException e) {
                send(sender, "failed", "{item}", itemText, "{reason}", e.getMessage());
                return;
            }
            if (prepared.async()) {
                send(sender, "pending", "{player}", target.getName());
            }
            String targetName = target.getName();
            OverflowPolicy chosen = policy;
            delivery.deliver(sender, target, prepared, spec.equip(), chosen)
                    .whenComplete((outcome, error) -> report(sender, target, targetName, itemText, chosen, outcome, error));
        }
    }

    /** A target selector or name that resolved to no player (vanilla's own message text). */
    public void reportTargetError(CommandSender sender, String message) {
        send(sender, "no-target", "{reason}", message);
    }

    private void send(CommandSender recipient, String outcome, String... replacements) {
        String key = spec().messageKeys().outcomeKey(outcome);
        if (key != null) {
            plugin.getMessageManager().sendByKey(recipient, key, replacements);
        }
    }

    private void report(CommandSender sender, Player target, String targetName, String item, OverflowPolicy policy,
                        DeliveryOutcome outcome, Throwable error) {
        if (error != null) {
            plugin.logger().warning("Give to " + targetName + " failed: " + error);
            send(sender, "failed", "{item}", item, "{reason}", String.valueOf(error.getMessage()));
            return;
        }
        String[] values = {"{player}", targetName, "{item}", item, "{amount}", Integer.toString(outcome.requested()),
                "{given}", Integer.toString(outcome.given()), "{dropped}", Integer.toString(outcome.dropped()),
                "{leftover}", Integer.toString(outcome.leftover()), "{policy}", policy.configName()};
        switch (outcome.status()) {
            case DELIVERED -> {
                send(sender, outcome.dropped() > 0 ? "dropped" : "delivered", values);
                if (target.isOnline() && target != sender) {
                    send(target, "received", values);
                }
            }
            case DEGRADED -> {
                send(sender, "degraded", values);
                if (target.isOnline() && target != sender) {
                    send(target, "received", values);
                }
            }
            case PARTIAL -> send(sender, "partial", values);
            case REFUSED_FULL -> send(sender, "refused", values);
            case REFUSED_UNRESOLVED -> send(sender, "refused-unresolved", values);
            case TARGET_OFFLINE -> send(sender, "offline", values);
            case CANCELLED_BY_EVENT -> send(sender, "cancelled", values);
            case FAILED -> send(sender, "failed", "{item}", item, "{reason}", "delivery failed");
        }
        for (String warning : outcome.warnings()) {
            send(sender, "warning", "{warning}", warning);
        }
    }

    /**
     * Vanilla {@code /give} targeting: a player name, a UUID, or a selector ({@code @p}, {@code @a},
     * {@code @r}, {@code @s}, ...) resolved through the server's own selector parser from the
     * sender's context (a command block's position, {@code /execute as ... at ...}). Like vanilla,
     * a selector that matches only non-players is an error rather than an empty give, and selectors
     * need vanilla's {@code minecraft.command.selector} permission.
     */
    private List<Player> targets(BukkitCommandContext context, String raw) {
        List<Player> out = new ArrayList<>();
        if (raw.startsWith("@")) {
            if (!context.sender().hasPermission(PERMISSION_SELECTOR)) {
                context.sendOutcome(context.sender(), "invalid", "{reason}", "selectors such as " + raw + " need "
                        + PERMISSION_SELECTOR + "; name the player instead");
                return List.of();
            }
            List<Entity> matched;
            try {
                matched = plugin.getServer().selectEntities(context.sender(), raw);
            } catch (IllegalArgumentException e) {
                context.sendOutcome(context.sender(), "invalid", "{reason}", "bad selector " + raw + ": " + e.getMessage());
                return List.of();
            }
            for (Entity entity : matched) {
                if (entity instanceof Player player) {
                    out.add(player);
                }
            }
            if (out.isEmpty() && !matched.isEmpty()) {
                context.sendOutcome(context.sender(), "invalid", "{reason}", "only players may be given items (" + raw + ")");
                return List.of();
            }
        } else if (raw.length() == 36 && raw.chars().filter(c -> c == '-').count() == 4) {
            try {
                Player player = plugin.getServer().getPlayer(java.util.UUID.fromString(raw));
                if (player != null) {
                    out.add(player);
                }
            } catch (IllegalArgumentException ignored) {
                // not a UUID after all; reported as an unknown player below
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
