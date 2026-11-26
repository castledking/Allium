package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public final class ItemFlagCommand implements SpecCommandHandler {
    private static final String ATTRIBUTES = "allium.itemflag.attribute";
    private static final String ATTRIBUTE_USAGE =
            "/itemflag attribute add <attribute> <amount> [operation] [slot] or remove <attribute|slot|all>";
    private final PluginStart plugin;

    public ItemFlagCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override public CommandSpec spec() {
        return CoreCommandSpecs.ITEMFLAG;
    }

    @Override public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            UtilitySupport.send(plugin, context.sender(), Messages.PLAYER_ONLY);
            return;
        }
        String[] args = context.args();
        if (args.length == 0) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        boolean attributes = lower(args[0]).equals("attribute") || lower(args[0]).equals("attributes");
        if (attributes && !player.hasPermission(ATTRIBUTES)) {
            UtilitySupport.send(plugin, player, Messages.NO_PERMISSION);
            return;
        }
        if (!UtilitySupport.guardInventoryNotBusy(plugin, player, player)) return;
        var held = player.getInventory().getItemInMainHand();
        ItemMeta meta = UtilitySupport.isAir(held) ? null : held.getItemMeta();
        if (meta == null) {
            UtilitySupport.send(plugin, player, Messages.UTILITY_HOLD_ITEM);
            return;
        }
        try {
            boolean changed = attributes ? attributes(player, meta, args) : flags(player, meta, args);
            if (changed && held.setItemMeta(meta)) player.getInventory().setItemInMainHand(held);
        } catch (IllegalArgumentException invalid) {
            player.sendMessage("§c" + invalid.getMessage());
        }
    }

    private static boolean flags(Player player, ItemMeta meta, String[] args) {
        String action = lower(args[0]);
        if (action.equals("list") && args.length == 1) {
            var names = meta.getItemFlags().stream().map(ItemFlagCommand::flagName).sorted().toList();
            player.sendMessage("§7Hidden item flags: " + (names.isEmpty() ? "none" : String.join(", ", names)));
            return false;
        }
        if (args.length < 2 || args.length > 3 || !action.equals("hide") && !action.equals("show")
                || action.equals("show") && args.length != 2) {
            throw new IllegalArgumentException("Use /itemflag hide <flag|all> [on|off], show <flag|all>, or list.");
        }
        String name = lower(args[1]);
        ItemFlag[] flags = name.equals("all") ? ItemFlag.values() : new ItemFlag[]{findFlag(name)};
        boolean hide = action.equals("hide") && (args.length == 3 ? state(args[2])
                : name.equals("all") || !meta.hasItemFlag(flags[0]));
        if (hide) meta.addItemFlags(flags);
        else meta.removeItemFlags(flags);
        player.sendMessage("§aTooltip " + name + (hide ? " hidden." : " shown."));
        return true;
    }

    private static ItemFlag findFlag(String name) {
        return Arrays.stream(ItemFlag.values())
                .filter(flag -> flagName(flag).equals(name) || lower(flag.name()).equals(name)
                        || name.equals("enchantments") && flagName(flag).equals("enchants"))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown item flag. Use tab completion."));
    }

    private static boolean state(String raw) {
        return switch (lower(raw)) {
            case "on", "true", "yes" -> true;
            case "off", "false", "no" -> false;
            default -> throw new IllegalArgumentException("Expected on or off.");
        };
    }

    private static boolean attributes(Player player, ItemMeta meta, String[] args) {
        if (args.length == 2 && lower(args[1]).equals("list")) {
            var modifiers = meta.getAttributeModifiers();
            if (modifiers == null || modifiers.isEmpty()) player.sendMessage("§7No custom attribute modifiers.");
            else modifiers.forEach((attribute, mod) -> player.sendMessage("§7" + attribute.getKey() + " "
                    + mod.getAmount() + " " + lower(mod.getOperation().name())
                    + (mod.getSlot() == null ? "" : " " + lower(mod.getSlot().name()))));
            return false;
        }
        if (args.length >= 4 && args.length <= 6 && lower(args[1]).equals("add")) {
            Attribute attribute = attribute(args[2]);
            double amount = Double.parseDouble(args[3]);
            if (!Double.isFinite(amount)) throw new IllegalArgumentException("Attribute amount must be finite.");
            var operation = args.length >= 5 ? AttributeModifier.Operation.valueOf(args[4].toUpperCase(Locale.ROOT))
                    : AttributeModifier.Operation.ADD_NUMBER;
            EquipmentSlot slot = args.length == 6 && !lower(args[5]).equals("any") ? slot(args[5]) : null;
            var modifier = new AttributeModifier(UUID.randomUUID(), "allium:itemflag", amount, operation, slot);
            if (!meta.addAttributeModifier(attribute, modifier)) throw new IllegalArgumentException("Could not add the modifier.");
            player.sendMessage("§aAdded modifier for " + attribute.getKey() + ".");
            return true;
        }
        if (args.length == 3 && lower(args[1]).equals("remove")) {
            String selector = lower(args[2]);
            if (selector.equals("all")) meta.setAttributeModifiers(null);
            else {
                EquipmentSlot slot = Arrays.stream(EquipmentSlot.values())
                        .filter(value -> lower(value.name()).equals(selector)).findFirst().orElse(null);
                if (slot != null) meta.removeAttributeModifier(slot);
                else meta.removeAttributeModifier(attribute(selector));
            }
            player.sendMessage("§aRemoved matching custom attribute modifiers.");
            return true;
        }
        throw new IllegalArgumentException("Use " + ATTRIBUTE_USAGE + ".");
    }

    private static EquipmentSlot slot(String raw) {
        return EquipmentSlot.valueOf(raw.toUpperCase(Locale.ROOT));
    }

    private static Attribute attribute(String raw) {
        NamespacedKey key = NamespacedKey.fromString(lower(raw));
        Attribute attribute = key == null ? null : Registry.ATTRIBUTE.get(key);
        if (attribute == null && key != null && key.getNamespace().equals("minecraft")) {
            String path = key.getKey();
            attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(
                    path.startsWith("generic.") ? path.substring("generic.".length()) : "generic." + path));
        }
        if (attribute == null) throw new IllegalArgumentException("Unknown attribute. Use tab completion.");
        return attribute;
    }

    @Override public Optional<List<String>> customCompletions(CommandSender sender, String label, String[] args) {
        if (args.length == 0) return Optional.empty();
        boolean mayEditAttributes = sender.hasPermission(ATTRIBUTES);
        String command = lower(args[0]);
        Stream<String> values = Stream.empty();
        if (args.length == 1) {
            values = mayEditAttributes ? Stream.of("hide", "show", "list", "attribute") : Stream.of("hide", "show", "list");
        } else if (args.length == 2 && (command.equals("hide") || command.equals("show"))) {
            values = Stream.concat(Stream.of("all"), Arrays.stream(ItemFlag.values()).map(ItemFlagCommand::flagName));
        } else if (args.length == 3 && command.equals("hide")) {
            values = Stream.of("on", "off");
        } else if (mayEditAttributes && (command.equals("attribute") || command.equals("attributes"))) {
            String action = lower(args[1]);
            if (args.length == 2) values = Stream.of("add", "remove", "list");
            else if (args.length == 3 && (action.equals("add") || action.equals("remove"))) {
                values = java.util.stream.StreamSupport.stream(Registry.ATTRIBUTE.spliterator(), false).map(attribute -> attribute.getKey().getKey());
                if (action.equals("remove")) values = Stream.concat(values, slots("all"));
            } else if (args.length == 5 && action.equals("add")) {
                values = Arrays.stream(AttributeModifier.Operation.values()).map(op -> lower(op.name()));
            } else if (args.length == 6 && action.equals("add")) {
                values = slots("any");
            }
        }
        String prefix = lower(args[args.length - 1]);
        return Optional.of(values.filter(value -> value.startsWith(prefix)).distinct().sorted().toList());
    }

    private static Stream<String> slots(String extra) {
        return Stream.concat(Stream.of(extra), Arrays.stream(EquipmentSlot.values()).map(slot -> lower(slot.name())));
    }

    private static String flagName(ItemFlag flag) {
        return lower(flag.name()).replaceFirst("^hide_", "");
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
