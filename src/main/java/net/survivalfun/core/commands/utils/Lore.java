package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class Lore implements CommandExecutor {

    private final PluginStart plugin;
    private final Lang lang;

    public Lore(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(lang.get("error-prefix") + lang.get("not-a-player"));
            return true;
        }

        Player player = (Player) sender;
        ItemStack item = player.getInventory().getItemInMainHand();


        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            player.sendMessage(lang.get("error-prefix") + lang.get("lore.failure"));
            return true;
        }

        List<String> lore = net.survivalfun.core.managers.core.Lore.getLore(item);

        if (args.length == 0) {
            player.sendMessage(Component.text("Lore Command Usage:", NamedTextColor.RED));
            String usageTemplate = lang.get("command-usage");

            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    usageTemplate.replace("{cmd}", label)
                            .replace("{args}", "add <text> - &7Adds a line to the item's lore.")
            ));

            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    usageTemplate.replace("{cmd}", label)
                            .replace("{args}", "remove <line> - &7Removes a line from the lore at the specified index.")
            ));

            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    usageTemplate.replace("{cmd}", label)
                            .replace("{args}", "insert <line> <text> - &7Inserts a line into the lore at the specified index.")
            ));

            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    usageTemplate.replace("{cmd}", label)
                            .replace("{args}", "clear - &7Clears all lore from the item.")
            ));
            return true;
        }

        if (item.getType().isAir()) {
            player.sendMessage(lang.get("error-prefix") + lang.get("hold-item")
                    .replace("{modify}", " §cadd lore to"));
            return true;
        }

        String action = args[0].toLowerCase();

        switch (action) {
            case "add":
                if (args.length < 2) {
                    player.sendMessage(lang.get("command-usage")
                            .replace("{cmd}", label)
                            .replace("{args}", "add <text>"));
                    return true;
                }
                String lineToAdd = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                // Use ColorUtils.parseColors for comprehensive color handling
                lineToAdd = Text.parseColors(lineToAdd);
                lore.add(lineToAdd);
                player.sendMessage(lang.get("lore.add")
                        .replace("{text}", lineToAdd));
                break;

            case "remove":
                if (args.length != 2) {
                    player.sendMessage(lang.get("command-usage")
                            .replace("{cmd}", label)
                            .replace("{args}", "remove <line>"));

                    return true;
                }
                try {
                    int indexToRemove = Integer.parseInt(args[1]) - 1; // Adjust for 1-based indexing
                    if (indexToRemove < 0 || indexToRemove >= lore.size()) {
                        player.sendMessage(lang.get("invalid")
                                .replace("{arg}", "index"));
                        return true;
                    }
                    String removedLine = lore.remove(indexToRemove);
                    player.sendMessage(lang.get("lore.remove")
                            .replace("{line}", removedLine));
                } catch (NumberFormatException e) {
                    player.sendMessage(lang.get("invalid")
                            .replace("{arg}", "index"));
                    return true;
                }
                break;

            case "insert":
                if (args.length < 3) {
                    player.sendMessage(lang.get("command-usage")
                            .replace("{cmd}", label)
                            .replace("{args}", "insert <line> <text>"));

                    return true;
                }
                try {
                    int indexToInsert = Integer.parseInt(args[1]) - 1; // Adjust for 1-based indexing
                    if (indexToInsert < 0 || indexToInsert > lore.size()) { // Note the `>` instead of `>=` to allow inserting at the end.
                        player.sendMessage(lang.get("lore.invalid-index"));
                        return true;
                    }
                    String lineToInsert = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                    // Use ColorUtils.parseColors for comprehensive color handling
                    lineToInsert = Text.parseColors(lineToInsert);
                    lore.add(indexToInsert, lineToInsert);
                    player.sendMessage(lang.get("lore.insert")
                            .replace("{index}", Integer.toString(indexToInsert))
                            .replace("{text}", lineToInsert));
                } catch (NumberFormatException e) {
                    player.sendMessage(lang.get("invalid")
                            .replace("{arg}", "index"));
                    return true;
                }
                break;

            case "clear":
                lore.clear();
                player.sendMessage(lang.get("lore.clear"));
                break;

            default:
                player.sendMessage(Component.text("Lore Command Usage:", NamedTextColor.RED));
                String usageTemplate = lang.get("command-usage");

                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        usageTemplate.replace("{cmd}", label)
                                .replace("{args}", "add <text> - &7Adds a line to the item's lore.")
                ));

                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        usageTemplate.replace("{cmd}", label)
                                .replace("{args}", "remove <line> - &7Removes a line from the lore at the specified index.")
                ));

                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        usageTemplate.replace("{cmd}", label)
                                .replace("{args}", "insert <line> <text> - &7Inserts a line into the lore at the specified index.")
                ));

                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        usageTemplate.replace("{cmd}", label)
                                .replace("{args}", "clear - &7Clears all lore from the item.")
                ));
                return true;
        }

        // Update the item's meta with the new lore
        meta.setLore(lore);
        item.setItemMeta(meta);
        
        // Update the player's inventory to reflect the changes
        player.updateInventory();
        return true;
    }
}
