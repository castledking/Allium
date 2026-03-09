package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;
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
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        Lang lang = plugin.getLangManager();
        if (lang == null) {
            sender.sendMessage("Error: Language system not initialized");
            Text.sendDebugLog(ERROR, "LanguageManager not initialized when executing GC command");
            return true;
        }
        // Get the first color code using the new Lang method
        String firstColorOfGodToggle = lang.getFirstColorCode("god.toggle");
        if (command.getName().equalsIgnoreCase("god")) {
            if (args.length == 0) {
                // /god (self)
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(lang.get("command-usage")
                            .replace("{cmd}", label)
                            .replace("{args}", "<player>"));
                    return true;
                }
                if (!player.hasPermission("allium.god")) {
                    Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", label);
                    return true;
                }
                boolean godModeToggled = toggleGodMode(player);
                String stateValue = godModeToggled
                    ? lang.get("styles.state.true") + "enabled" + firstColorOfGodToggle
                    : lang.get("styles.state.false") + "disabled" + firstColorOfGodToggle;
                lang.sendMessage(sender, "god.toggle", "state", stateValue, "name", "");


            } else if (args.length == 1) {
                // /god <player>
                if (!(sender instanceof Player) && !sender.hasPermission("allium.god.others")) {
                    Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label + " &con others.");
                    return true;
                }

                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    if (Bukkit.getOfflinePlayer(args[0]).hasPlayedBefore()) {
                        String targetName = Bukkit.getOfflinePlayer(args[0]).getName();
                        Text.sendErrorMessage(sender, "player-not-online", lang, "{name}", targetName);
                        return true;
                    } else {
                        Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
                        return true;
                    }
                }

                boolean godModeToggled = toggleGodMode(target);
                String stateValue = godModeToggled
                    ? lang.get("styles.state.true") + "enabled" + firstColorOfGodToggle
                    : lang.get("styles.state.false") + "disabled" + firstColorOfGodToggle;

                if (sender instanceof Player && ((Player) sender).getUniqueId().equals(target.getUniqueId())) {
                    lang.sendMessage(sender, "god.toggle", "state", stateValue, "name", "");
                } else {
                    lang.sendMessage(sender, "god.toggle", "state", stateValue, "name", "for " + target.getName());
                    lang.sendMessage(target, "god.toggle", "state", stateValue, "name", "");
                }

            } else {
                sender.sendMessage(lang.get("command-usage")
                        .replace("{cmd}", label)
                        .replace("{args}", "<player>"));
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
