package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import net.survivalfun.core.managers.core.Text;

import java.util.logging.Level;

public class Rename implements CommandExecutor {

    private final PluginStart plugin;


    public Rename(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label
            , String @NotNull [] args) {
        Lang lang = plugin.getLangManager();
        if (lang == null) {
            sender.sendMessage("Error: Language system not initialized");
            plugin.getLogger().log(Level.SEVERE, "LanguageManager not initialized when executing GC command");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("error-prefix") + lang.get("not-a-player"));
            return true;
        }

        if (!player.hasPermission("core.rename")) {
            player.sendMessage(lang.get("error-prefix") + lang.get("no-permission"));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(lang.get("command-usage")
                    .replace("{cmd}", label)
                    .replace("{args}", "<name>"));
            return true;
        }

        ItemStack item = player.getInventory().getItemInMainHand();


        if (item.getType().isAir()) {
            player.sendMessage(lang.get("error-prefix") + lang.get("hold-item")
                    .replace("{modify}", "rename"));
            return true;
        }

        String name = String.join(" ", args);

        // Process the name based on permissions
        if (player.hasPermission("core.rename.color")) {
            // Use ColorUtils.parseColors for comprehensive color handling
            name = Text.parseColors(name);
        } else {
            // Strip all colors if player doesn't have color permission
            name = ChatColor.stripColor(name);
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
}
