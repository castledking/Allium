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
                if (!player.hasPermission("core.god")) {
                    Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", label);
                    return true;
                }
                boolean godModeToggled = toggleGodMode(player);
                if (godModeToggled) {
                    String enabledStyle = lang.get("styles.state.true");
                    sender.sendMessage(lang.get("god.toggle")
                            .replace("{state}", enabledStyle + "enabled" + firstColorOfGodToggle)
                            .replace(" {name}", ""));
                } else {
                    String disabledStyle = lang.get("styles.state.false");
                    sender.sendMessage(lang.get("god.toggle")
                            .replace("{state}", disabledStyle + "disabled" + firstColorOfGodToggle)
                            .replace(" {name}", ""));
                }


            } else if (args.length == 1) {
                // /god <player>
                if (!(sender instanceof Player) && !sender.hasPermission("core.god.others")) {
                    Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label + " on others.");
                    return true;
                }

                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
                    return true;
                }

                boolean godModeToggled = toggleGodMode(target);
                String stateStyle = godModeToggled ? 
                    lang.get("styles.state.true") : 
                    lang.get("styles.state.false");
                String stateText = godModeToggled ? "enabled" : "disabled";
                // Use lang.get("god.toggle") here to ensure we have the raw message for replacement
                // if godToggleMessage was intended to be the raw message, this is fine.
                // Otherwise, if godToggleMessage was already processed, this might be an issue.
                // Assuming godToggleMessage was NOT yet processed by Text.parseColors for this specific construction.
                // For consistency with other parts, it might be better to use lang.get("god.toggle") directly here too.
                String formattedMessage = lang.get("god.toggle") // Changed from godToggleMessage to lang.get()
                    .replace("{state}", stateStyle + stateText + firstColorOfGodToggle);

                if (sender instanceof Player && ((Player) sender).getUniqueId().equals(target.getUniqueId())) {
                    // If sender is targeting themselves, show self message
                    sender.sendMessage(formattedMessage.replace(" {name}", ""));
                } else {
                    // Show different messages for sender and target
                    sender.sendMessage(formattedMessage.replace("{name}", "for " + target.getName()));
                    target.sendMessage(formattedMessage.replace(" {name}", ""));
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
