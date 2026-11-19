package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.UntrustedText;
import codes.castled.allium.text.FormattedText;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code /rename <name>|clear} and {@code /lore add|set|insert|remove|clear|copy|paste}: edit the
 * held item's custom name and lore. {@code copy} keeps the lore in the player's
 * {@link LoreClipboard}; {@code paste} replaces the held item's lore with it. Colour codes, hex colours and gradients need {@code allium.rename.color}
 * / {@code allium.lore.color} (granted with the base node by default); without them the text is
 * kept literally. Only the name and lore change, so Allium item identity and signatures survive.
 */
public final class ItemTextCommand implements SpecCommandHandler {

    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_LORE_LENGTH = 100;
    public static final int MAX_LORE_LINES = 32;

    private final PluginStart plugin;
    private final CommandSpec spec;
    private final LoreClipboard clipboard;

    public ItemTextCommand(PluginStart plugin, CommandSpec spec) {
        this(plugin, spec, new LoreClipboard());
    }

    public ItemTextCommand(PluginStart plugin, CommandSpec spec, LoreClipboard clipboard) {
        this.plugin = plugin;
        this.spec = spec;
        this.clipboard = clipboard;
    }

    @Override
    public CommandSpec spec() {
        return spec;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            UtilitySupport.send(plugin, context.sender(), Messages.PLAYER_ONLY);
            return;
        }
        String[] args = context.args();
        if (args.length == 0) {
            context.sendInvalidArgs(spec.selfUsage());
            return;
        }
        if (!UtilitySupport.guardInventoryNotBusy(plugin, player, player)) {
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        ItemMeta meta = UtilitySupport.isAir(held) ? null : held.getItemMeta();
        if (meta == null) {
            UtilitySupport.send(plugin, player, Messages.UTILITY_HOLD_ITEM);
            return;
        }
        if (spec.canonicalName().equals("rename")) {
            rename(context, player, held, meta, args);
        } else {
            lore(context, player, held, meta, args);
        }
    }

    private void rename(BukkitCommandContext context, Player player, ItemStack held, ItemMeta meta, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("clear")) {
            if (!meta.hasDisplayName()) {
                UtilitySupport.send(plugin, player, Messages.UTILITY_RENAME_NOTHING, "{item}", UtilitySupport.itemName(held));
                return;
            }
            meta.setDisplayName(null);
            save(player, held, meta);
            UtilitySupport.send(plugin, player, Messages.UTILITY_RENAME_CLEARED, "{item}", UtilitySupport.itemName(held));
            return;
        }
        FormattedText name = format(player, String.join(" ", args), "allium.rename.color");
        if (name == null) {
            context.sendInvalidArgs(spec.selfUsage());
            return;
        }
        if (visibleLength(name) > MAX_NAME_LENGTH) {
            UtilitySupport.send(plugin, player, Messages.UTILITY_RENAME_TOO_LONG, "{max}", Integer.toString(MAX_NAME_LENGTH));
            return;
        }
        String before = UtilitySupport.itemName(held);
        meta.setDisplayName(name.toLegacy());
        save(player, held, meta);
        UtilitySupport.send(plugin, player, Messages.UTILITY_RENAME_SET, "{item}", before, "{name}", name.toPlainText());
    }

    private void lore(BukkitCommandContext context, Player player, ItemStack held, ItemMeta meta, String[] args) {
        String action = args[0].toLowerCase(Locale.ROOT);
        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        String item = UtilitySupport.itemName(held);
        switch (action) {
            case "clear" -> {
                if (args.length != 1) {
                    context.sendInvalidArgs("/lore clear");
                    return;
                }
                if (lore.isEmpty()) {
                    UtilitySupport.send(plugin, player, Messages.UTILITY_LORE_NONE, "{item}", item);
                    return;
                }
                meta.setLore(null);
                save(player, held, meta);
                UtilitySupport.send(plugin, player, Messages.UTILITY_LORE_CLEARED, "{item}", item);
            }
            case "copy" -> {
                if (args.length != 1) {
                    context.sendInvalidArgs("/lore copy");
                    return;
                }
                if (lore.isEmpty()) {
                    UtilitySupport.send(plugin, player, Messages.UTILITY_LORE_NONE, "{item}", item);
                    return;
                }
                clipboard.put(player.getUniqueId(), lore);
                UtilitySupport.send(plugin, player, Messages.UTILITY_LORE_COPIED, "{count}", Integer.toString(lore.size()), "{item}", item);
            }
            case "paste" -> {
                if (args.length != 1) {
                    context.sendInvalidArgs("/lore paste");
                    return;
                }
                List<String> copied = clipboard.get(player.getUniqueId()).orElse(null);
                if (copied == null) {
                    UtilitySupport.send(plugin, player, Messages.UTILITY_LORE_CLIPBOARD_EMPTY);
                    return;
                }
                // Without the colour node the lines keep their text but lose their formatting.
                List<String> pasted = player.hasPermission("allium.lore.color")
                        ? copied
                        : copied.stream().map(line -> Objects.toString(ChatColor.stripColor(line), "")).toList();
                meta.setLore(new ArrayList<>(pasted));
                save(player, held, meta);
                UtilitySupport.send(plugin, player, Messages.UTILITY_LORE_PASTED, "{count}", Integer.toString(pasted.size()), "{item}", item);
            }
            case "remove" -> {
                if (args.length != 2) {
                    context.sendInvalidArgs("/lore remove <line>");
                    return;
                }
                Integer line = line(player, args[1], lore.size(), false);
                if (line == null) {
                    return;
                }
                lore.remove(line - 1);
                meta.setLore(lore.isEmpty() ? null : lore);
                save(player, held, meta);
                UtilitySupport.send(plugin, player, Messages.UTILITY_LORE_REMOVED, "{line}", Integer.toString(line), "{item}", item);
            }
            case "add", "set", "insert" -> {
                boolean add = action.equals("add");
                int textStart = add ? 1 : 2;
                if (args.length <= textStart) {
                    context.sendInvalidArgs("/lore " + action + (add ? " <text>" : " <line> <text>"));
                    return;
                }
                Integer line = add ? Integer.valueOf(lore.size() + 1) : line(player, args[1], lore.size(), action.equals("insert"));
                if (line == null) {
                    return;
                }
                if (!action.equals("set") && lore.size() >= MAX_LORE_LINES) {
                    UtilitySupport.send(plugin, player, Messages.UTILITY_LORE_FULL, "{max}", Integer.toString(MAX_LORE_LINES));
                    return;
                }
                FormattedText text = format(player, String.join(" ", Arrays.copyOfRange(args, textStart, args.length)), "allium.lore.color");
                if (text == null) {
                    context.sendInvalidArgs("/lore " + action + (add ? " <text>" : " <line> <text>"));
                    return;
                }
                if (visibleLength(text) > MAX_LORE_LENGTH) {
                    UtilitySupport.send(plugin, player, Messages.UTILITY_LORE_TOO_LONG, "{max}", Integer.toString(MAX_LORE_LENGTH));
                    return;
                }
                if (action.equals("set")) {
                    lore.set(line - 1, text.toLegacy());
                } else {
                    lore.add(line - 1, text.toLegacy());
                }
                meta.setLore(lore);
                save(player, held, meta);
                Messages done = switch (action) {
                    case "add" -> Messages.UTILITY_LORE_ADDED;
                    case "set" -> Messages.UTILITY_LORE_SET;
                    default -> Messages.UTILITY_LORE_INSERTED;
                };
                UtilitySupport.send(plugin, player, done, "{line}", Integer.toString(line), "{item}", item);
            }
            default -> context.sendInvalidArgs(spec.selfUsage());
        }
    }

    /** A 1-based line number of the lore; {@code insert} may also name the line after the last. */
    @Nullable
    private Integer line(Player player, String raw, int size, boolean insert) {
        int max = insert ? size + 1 : size;
        try {
            int line = Integer.parseInt(raw.trim());
            if (line >= 1 && line <= max) {
                return line;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        UtilitySupport.send(plugin, player, Messages.UTILITY_LORE_NO_LINE, "{line}", raw, "{count}", Integer.toString(size));
        return null;
    }

    /** The text with formatting the player may use; null when nothing visible is left. */
    @Nullable
    private static FormattedText format(Player player, String raw, String colorPermission) {
        boolean color = player.hasPermission(colorPermission);
        FormattedText text = UntrustedText.parse(raw, new UntrustedText.Allow(color, color, color));
        return text.toPlainText().isBlank() ? null : text;
    }

    private static int visibleLength(FormattedText text) {
        String plain = text.toPlainText();
        return plain.codePointCount(0, plain.length());
    }

    private static void save(Player player, ItemStack held, ItemMeta meta) {
        held.setItemMeta(meta);
        player.getInventory().setItemInMainHand(held);
    }
}
