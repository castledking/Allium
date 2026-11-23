package codes.castled.allium.command.tools;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.importer.VoucherImport;
import codes.castled.allium.item.ResolutionResult;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.ToolsModule;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.tool.definition.ToolDefinition;
import codes.castled.allium.tool.powertool.PowerToolBehavior;
import codes.castled.allium.tool.powertool.PowerToolBehaviorType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@code /voucher give <player> <voucher> [amount]}, {@code /voucher list} and {@code /voucher reload}
 * ({@code /vouchers}), pre-recode Allium's voucher command. A voucher is a powertool named
 * {@code voucher_<name>}: one imported by {@code /allium import vouchers}, or one saved with
 * {@code /pt save voucher_<name>}. Its name may be given as in {@code vouchers.yml} ({@code TFly10m})
 * or as the tool ({@code voucher_tfly10m}).
 */
public final class VoucherCommand implements SpecCommandHandler {

    public static final String PERM_GIVE = "allium.voucher.give";
    public static final String PERM_RELOAD = "allium.voucher.reload";

    private final PluginStart plugin;
    private final Supplier<PowerToolBehavior> powerTools;
    private final Supplier<ToolsModule> tools;

    public VoucherCommand(PluginStart plugin, Supplier<PowerToolBehavior> powerTools, Supplier<ToolsModule> tools) {
        this.plugin = plugin;
        this.powerTools = powerTools;
        this.tools = tools;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.VOUCHER;
    }

    /** The voucher tool a name means, or null: {@code TFly10m} and {@code voucher_tfly10m} are the same voucher. */
    @Nullable
    static String resolve(String name, Predicate<String> exists) {
        String lower = name.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith(VoucherImport.PREFIX) && exists.test(lower)) return lower;
        String tool = VoucherImport.toolName(name);
        return tool != null && exists.test(tool) ? tool : null;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        PowerToolBehavior behavior = powerTools.get();
        if (behavior == null) {
            send(sender, Messages.MODULE_DISABLED);
            return;
        }
        String sub = listAlias(context.label()) ? "list" : args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give" -> give(context, behavior, args);
            case "list" -> list(sender, behavior);
            case "reload" -> {
                if (!sender.hasPermission(PERM_RELOAD)) {
                    send(sender, Messages.NO_PERMISSION);
                    return;
                }
                ToolsModule module = tools.get();
                if (module != null) module.reloadPowerTools();
                PowerToolBehavior reloaded = powerTools.get();
                send(sender, Messages.VOUCHER_RELOADED, "{count}", Integer.toString(reloaded == null ? 0 : vouchers(reloaded).size()));
            }
            default -> context.sendInvalidArgs(spec().selfUsage());
        }
    }

    private void give(BukkitCommandContext context, PowerToolBehavior behavior, String[] args) {
        CommandSender sender = context.sender();
        if (args.length < 3 || args.length > 4) {
            context.sendInvalidArgs("/voucher give <player> <voucher> [amount]");
            return;
        }
        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target == null) target = plugin.getServer().getPlayer(args[1]);
        if (target == null || !PlayerNameVisibility.canSeeTarget(sender, target)) {
            send(sender, Messages.PLAYER_NOT_FOUND, "{player}", args[1]);
            return;
        }
        String tool = resolve(args[2], name -> behavior.registryTool(name).isPresent());
        if (tool == null) {
            send(sender, Messages.VOUCHER_NOT_FOUND, "{voucher}", args[2]);
            return;
        }
        int amount = 1;
        if (args.length == 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException invalid) {
                amount = -1;
            }
            if (amount < 1 || amount > 64) {
                send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", "amount must be between 1 and 64");
                return;
            }
        }
        ResolutionResult created = behavior.createToolItem(tool, amount);
        ItemStack stack = created.itemStack().orElse(null);
        if (stack == null) {
            send(sender, Messages.VOUCHER_NOT_FOUND, "{voucher}", args[2]);
            return;
        }
        stack.setAmount(amount);
        Player recipient = target;
        int quantity = amount;
        String display = display(behavior, tool);
        UtilitySupport.onTarget(plugin, sender, recipient, () -> {
            recipient.getInventory().addItem(stack).values()
                    .forEach(left -> recipient.getWorld().dropItemNaturally(recipient.getLocation(), left));
            if (sender != recipient) {
                UtilitySupport.send(plugin, sender, Messages.VOUCHER_GIVEN, "{amount}", Integer.toString(quantity), "{voucher}", display, "{player}", recipient.getName());
            }
            UtilitySupport.send(plugin, recipient, Messages.VOUCHER_RECEIVED, "{amount}", Integer.toString(quantity), "{voucher}", display);
            plugin.logger().info("[Vouchers] " + sender.getName() + " gave " + quantity + "x " + tool + " to " + recipient.getName() + ".");
        });
    }

    private void list(CommandSender sender, PowerToolBehavior behavior) {
        List<ToolDefinition> vouchers = vouchers(behavior);
        if (vouchers.isEmpty()) {
            send(sender, Messages.VOUCHER_LIST_EMPTY);
            return;
        }
        send(sender, Messages.VOUCHER_LIST_HEADER, "{count}", Integer.toString(vouchers.size()));
        for (ToolDefinition voucher : vouchers) {
            send(sender, Messages.VOUCHER_LIST_ENTRY, "{voucher}", voucher.localId().substring(VoucherImport.PREFIX.length()),
                    "{name}", display(behavior, voucher.localId()));
        }
    }

    /** The loaded powertools named {@code voucher_<name>}, by name. */
    private static List<ToolDefinition> vouchers(PowerToolBehavior behavior) {
        List<ToolDefinition> vouchers = new ArrayList<>();
        for (ToolDefinition definition : behavior.registryTools()) {
            if (PowerToolBehaviorType.ID.equals(definition.behavior()) && definition.localId().startsWith(VoucherImport.PREFIX)
                    && definition.localId().length() > VoucherImport.PREFIX.length()) {
                vouchers.add(definition);
            }
        }
        vouchers.sort((a, b) -> a.localId().compareTo(b.localId()));
        return vouchers;
    }

    /** The voucher's item name, as players see it, or its tool name. */
    private static String display(PowerToolBehavior behavior, String tool) {
        return behavior.registryTool(tool)
                .map(definition -> definition.item() != null && definition.item().name() != null ? definition.item().name()
                        : definition.name())
                .orElse(tool);
    }

    /** {@code /vouchers} (or {@code /allium:vouchers}) is {@code /voucher list}. */
    static boolean listAlias(String label) {
        if (label == null) return false;
        return label.substring(label.indexOf(':') + 1).equalsIgnoreCase("vouchers");
    }

    public List<String> complete(CommandSender sender, String alias, String[] args) {
        if (args.length == 0 || listAlias(alias)) return List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> values = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission(PERM_GIVE)) values.addAll(List.of("give", "list"));
            if (sender.hasPermission(PERM_RELOAD)) values.add("reload");
        } else if (args[0].equalsIgnoreCase("give") && sender.hasPermission(PERM_GIVE)) {
            if (args.length == 2) {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (PlayerNameVisibility.canSeeTarget(sender, player)) values.add(player.getName());
                }
            } else if (args.length == 3) {
                PowerToolBehavior behavior = powerTools.get();
                if (behavior != null) {
                    vouchers(behavior).forEach(voucher -> values.add(voucher.localId().substring(VoucherImport.PREFIX.length())));
                }
            } else if (args.length == 4) {
                values.addAll(List.of("1", "5", "16", "64"));
            }
        }
        values.removeIf(value -> !value.toLowerCase(Locale.ROOT).startsWith(prefix));
        return values;
    }

    private void send(CommandSender recipient, Messages message, String... replacements) {
        plugin.getMessageManager().send(recipient, message, replacements);
    }
}
