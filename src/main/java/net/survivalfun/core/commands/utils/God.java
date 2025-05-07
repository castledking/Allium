package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

public class God implements CommandExecutor {

    private final PluginStart plugin;

    public God(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        Lang lang = plugin.getLangManager();
        if (lang == null) {
            sender.sendMessage("Error: Language system not initialized");
            plugin.getLogger().log(Level.SEVERE, "LanguageManager not initialized when executing GC command");
            return true;
        }
        if (command.getName().equalsIgnoreCase("god")) {
            if (args.length == 0) {
                // /god (self)
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(lang.get("not-a-player"));
                    return true;
                }
                if (!player.hasPermission("core.god")) {
                    Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", label);
                    return true;
                }
                boolean godModeToggled = toggleGodMode(player);
                if (godModeToggled) {
                    sender.sendMessage(lang.get("god.toggle")
                            .replace("{state}", "§a§nenabled§r")
                            .replace("{name}", ""));
                } else {
                    sender.sendMessage(lang.get("god.toggle")
                            .replace("{state}", "§c§ndisabled§r")
                            .replace("{name}", ""));
                }


            } else if (args.length == 1) {
                // /god <player>
                if (sender.hasPermission("core.god.others")) {
                    Player target = Bukkit.getPlayer(args[0]);
                    if (target == null) {
                        Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
                        return true;
                    }
                    boolean godModeToggled = toggleGodMode(target);
                    if (godModeToggled) {
                        sender.sendMessage(lang.get("god.toggle")
                                .replace("{state}", "§a§nenabled§r")
                                .replace("{name}", "for " + target.getName()));
                        target.sendMessage(lang.get("god.toggle")
                                .replace("{state}", "§a§nenabled§r")
                                .replace("{name}", ""));
                    } else {
                        sender.sendMessage(lang.get("god.toggle")
                                .replace("{state}", "§c§ndisabled§r")
                                .replace("{name}", "for " + target.getName()));
                        target.sendMessage(lang.get("god.toggle")
                                .replace("{state}", "§c§ndisabled§r")
                                .replace("{name}", ""));
                    }

                } else {
                    Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label + " on others.");
                    return true;
                }

            } else {
                sender.sendMessage(lang.get("command-usage")
                        .replace("{cmd}", label)
                        .replace("{args}", "[player]"));
                return true;
            }
            return true;
        }
        return false;
    }

    private boolean toggleGodMode(Player player) {
        NamespacedKey key = new NamespacedKey(plugin, "godmode");
        PersistentDataContainer dataContainer = player.getPersistentDataContainer();
        boolean godModeEnabled;

        if (dataContainer.has(key, PersistentDataType.INTEGER)) {
            Integer godMode = dataContainer.get(key, PersistentDataType.INTEGER);
            if (godMode != null && godMode == 1) {
                dataContainer.set(key, PersistentDataType.INTEGER, 0);
                player.setInvulnerable(false);
                godModeEnabled = false;



            } else {
                dataContainer.set(key, PersistentDataType.INTEGER, 1);
                player.setInvulnerable(true);
                godModeEnabled = true;
            }
        } else {
            dataContainer.set(key, PersistentDataType.INTEGER, 1);
            player.setInvulnerable(true);
            godModeEnabled = true;
        }
        return godModeEnabled;
    }
}
