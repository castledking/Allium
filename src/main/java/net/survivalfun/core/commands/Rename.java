package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import net.survivalfun.core.managers.core.Dialog;
import net.survivalfun.core.managers.lang.Lang;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import net.survivalfun.core.managers.core.Text;

import java.util.Locale;
import java.util.logging.Level;

public class Rename implements CommandExecutor {

    private final PluginStart plugin;


    public Rename(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label
            , String[] args) {
        Lang lang = plugin.getLangManager();
        if (lang == null) {
            sender.sendMessage("Error: Language system not initialized");
            Text.sendDebugLog(ERROR, "LanguageManager not initialized when executing GC command");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("error-prefix") + lang.get("not-a-player"));
            return true;
        }

        if (!player.hasPermission("allium.rename")) {
            player.sendMessage(lang.get("error-prefix") + lang.get("no-permission"));
            return true;
        }

        ItemStack item = player.getInventory().getItemInMainHand();

        // /rename --apply <name> [<playerName>] (from dialog; last arg is target player when run as console)
        if (args.length >= 1 && "--apply".equalsIgnoreCase(args[0])) {
            if (args.length < 2) return true;
            int end = args.length - 1;
            String targetName = args[end];
            Player target = org.bukkit.Bukkit.getPlayerExact(targetName);
            if (target == null || !target.isOnline()) {
                if (sender instanceof Player) ((Player) sender).sendMessage("§cPlayer not found: " + targetName);
                return true;
            }
            if (!target.hasPermission("allium.rename.gui")) return true;
            ItemStack targetItem = target.getInventory().getItemInMainHand();
            if (targetItem.getType().isAir()) {
                Text.sendErrorMessage(target, "hold-item", lang, "{modify}", "rename");
                return true;
            }
            String name = (end > 1) ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, end)) : args[1];
            if (target.hasPermission("allium.rename.color")) {
                name = Text.parseColors(name);
            } else {
                name = MiniMessage.miniMessage().stripTags(name);
            }
            ItemMeta meta = targetItem.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(name);
                targetItem.setItemMeta(meta);
                target.sendMessage(lang.get("rename.success").replace("{name}", name));
                target.updateInventory();
            }
            return true;
        }

        if (args.length == 0) {
            if (player.hasPermission("allium.rename.gui")) {
                if (item.getType().isAir()) {
                    Text.sendErrorMessage(player, "hold-item", lang, "{modify}", "rename");
                    return true;
                }
                ItemMeta meta = item.getItemMeta();
                String initial = getDisplayNameForRenameDialog(item, meta, player);
                Dialog.showTextInput(plugin, player, "Edit Item Name",
                    "Edit the display name below. Supports color codes if you have allium.rename.color.",
                    "name", "Name:", initial, 256, "Apply", "rename_apply");
                return true;
            }
            player.sendMessage(lang.get("command-usage")
                    .replace("{cmd}", label)
                    .replace("{args}", "<name>"));
            return true;
        }

        if (item.getType().isAir()) {
            Text.sendErrorMessage(player, "hold-item", lang, "{modify}", "rename");
            return true;
        }

        String name = String.join(" ", args);

        // Process the name based on permissions
        if (player.hasPermission("allium.rename.color")) {
            // Use ColorUtils.parseColors for comprehensive color handling
            name = Text.parseColors(name);
        } else {
            // Strip all colors if player doesn't have color permission
            name = MiniMessage.miniMessage().stripTags(name);
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
            player.sendMessage(lang.get("rename.success")
                    .replace("{name}", name));
            player.updateInventory();
        } else {
            player.sendMessage(lang.get("error-prefix") + lang.get("rename.failure"));
        }

        return true;
    }

    /** Returns custom display name if set, otherwise the default (vanilla) item name for the rename dialog. */
    private static String getDisplayNameForRenameDialog(ItemStack item, ItemMeta meta, Player player) {
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        String key = defaultItemTranslationKey(item.getType());
        try {
            Component c = Component.translatable(key);
            Locale locale = player.getLocale() != null ? Locale.forLanguageTag(player.getLocale().replace("_", "-")) : Locale.US;
            Component rendered = GlobalTranslator.render(c, locale);
            String s = LegacyComponentSerializer.legacySection().serialize(rendered);
            return (s != null && !s.isEmpty()) ? s : formatMaterialName(item.getType());
        } catch (Exception e) {
            return formatMaterialName(item.getType());
        }
    }

    private static String defaultItemTranslationKey(Material material) {
        String type = material.isBlock() ? "block" : "item";
        return type + "." + material.getKey().getNamespace() + "." + material.getKey().getKey();
    }

    private static String formatMaterialName(Material material) {
        String key = material.getKey().getKey().replace("_", " ");
        if (key.isEmpty()) return key;
        StringBuilder sb = new StringBuilder();
        for (String word : key.split(" ")) {
            if (sb.length() > 0) sb.append(' ');
            if (!word.isEmpty()) sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
