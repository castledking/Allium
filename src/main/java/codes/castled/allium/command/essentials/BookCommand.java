package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.*;
import codes.castled.allium.message.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BookCommand implements SpecCommandHandler {

    private static final String USAGE = "/book [author|title|sign|unsign] [value]";
    public static final CommandSpec SPEC = new CommandSpec(
            "book", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of(), "Edit or sign the book you are holding",
            USAGE, USAGE, "allium.book", null,
            List.of(CommandArgSpec.optional("action", null, SuggestionSpec.literals(List.of("author", "title", "sign", "unsign"), null))),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of()));

    private final PluginStart plugin;

    public BookCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            UtilitySupport.send(plugin, context.sender(), Messages.PLAYER_ONLY);
            return;
        }
        if (UtilitySupport.isInventoryBusy(plugin, player)) {
            UtilitySupport.send(plugin, player, Messages.UTILITY_INVENTORY_BUSY, "{player}", player.getName());
            return;
        }
        var inventory = player.getInventory();
        ItemStack stack = inventory.getItemInMainHand();
        boolean written = stack.getType() == Material.WRITTEN_BOOK;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !written && stack.getType() != Material.WRITABLE_BOOK
                || plugin.getItemService() != null && plugin.getItemService().isCustomItem(stack)) {
            UtilitySupport.send(plugin, player, Messages.BOOK_HOLD);
            return;
        }
        String[] args = context.args();
        String operation = args.length == 0 ? written ? "unsign" : "sign" : args[0].toLowerCase(Locale.ROOT);
        String value = args.length < 2 ? "" : String.join(" ", Arrays.copyOfRange(args, 1, args.length)).strip();
        if (written && (!(meta instanceof BookMeta book) || !player.getName().equalsIgnoreCase(book.getAuthor()))
                && !player.hasPermission("allium.book.others")) {
            UtilitySupport.send(plugin, player, Messages.NO_PERMISSION, "{cmd}", "book");
            return;
        }

        ItemStack updated = stack.clone();
        switch (operation) {
            case "title", "author" -> {
                if (!(meta instanceof BookMeta book) || !written || value.isBlank() || value.length() > 32) {
                    context.sendInvalidArgs(USAGE);
                    return;
                }
                if (!player.hasPermission("allium.book." + operation)) {
                    UtilitySupport.send(plugin, player, Messages.NO_PERMISSION, "{cmd}", "book " + operation);
                    return;
                }
                if (operation.equals("author")) book.setAuthor(value);
                else if (!book.setTitle(value)) {
                    context.sendInvalidArgs(USAGE);
                    return;
                }
                updated.setItemMeta(book);
            }
            case "sign" -> {
                if (written || value.length() > 32 || !convert(updated, meta, true, player.getName(), value)) {
                    context.sendInvalidArgs(USAGE);
                    return;
                }
            }
            case "unsign" -> {
                if (!written || !value.isEmpty() || !convert(updated, meta, false, player.getName(), "")) {
                    context.sendInvalidArgs(USAGE);
                    return;
                }
            }
            default -> {
                context.sendInvalidArgs(USAGE);
                return;
            }
        }
        inventory.setItemInMainHand(updated);
        UtilitySupport.send(plugin, player, Messages.BOOK_UPDATED);
    }

    private static boolean convert(ItemStack stack, ItemMeta source, boolean sign, String author, String title) {
        try {
            List<?> pages = source instanceof BookMeta book ? book.getPages()
                    : (List<?>) Class.forName("org.bukkit.inventory.meta.WritableBookMeta").getMethod("getPages").invoke(source);
            stack.setType(sign ? Material.WRITTEN_BOOK : Material.WRITABLE_BOOK);
            ItemMeta destination = stack.getItemMeta();
            if (destination instanceof BookMeta book) {
                if (sign) {
                    book.setAuthor(author);
                    if (!book.setTitle(title.isEmpty() ? "Untitled" : title)) return false;
                }
                @SuppressWarnings("unchecked") List<String> text = (List<String>) pages;
                book.setPages(text);
            } else {
                Class.forName("org.bukkit.inventory.meta.WritableBookMeta")
                        .getMethod("setPages", List.class).invoke(destination, pages);
            }
            return stack.setItemMeta(destination);
        } catch (ReflectiveOperationException | LinkageError | IllegalArgumentException unavailable) {
            return false;
        }
    }
}
