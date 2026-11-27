package codes.castled.allium.legacy;

import codes.castled.allium.redeem.RedeemConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

final class LegacyRedeemCommand implements Listener {
    private final JavaPlugin plugin;
    private final LegacyConfigManager config;
    private final LegacyCommandManager commandManager;
    private final LegacyMessageManager messages;
    private final File file;

    LegacyRedeemCommand(JavaPlugin plugin, LegacyConfigManager config, LegacyCommandManager commandManager,
                        LegacyMessageManager messages) {
        this.plugin = plugin;
        this.config = config;
        this.commandManager = commandManager;
        this.messages = messages;
        this.file = new File(plugin.getDataFolder(), "config.yml");
    }

    boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            messages.send(sender, "error.player-only", "{prefix} &cThis command can only be used by players.");
            return true;
        }
        if (!sender.hasPermission("allium.redeem")) {
            messages.send(sender, "error.no-permission", "{prefix} &cYou don't have permission to do that.");
            return true;
        }
        if (args.length != 0) return false;

        Player player = (Player) sender;
        UUID id = player.getUniqueId();
        YamlConfiguration saved = new YamlConfiguration();
        String rank;
        List<String> commands;
        try {
            if (file.exists()) saved.load(file);
            rank = RedeemConfiguration.pending(saved, id).orElse(null);
            if (rank == null) {
                messages.send(player, "redeem.unavailable", "{prefix} &cYou have no rank rewards to claim.");
                return true;
            }
            commands = RedeemConfiguration.commands(saved, rank);
            if (commands.isEmpty()) {
                plugin.getLogger().warning("No commands configured for redemption rank " + rank);
                failed(player);
                return true;
            }
            // Persist before dispatch because commands from other plugins cannot be rolled back.
            RedeemConfiguration.consume(saved, id, rank);
            LegacyCompat.saveYamlAtomically(saved, file);
            RedeemConfiguration.publishClaims(saved, config.raw());
        } catch (IOException | InvalidConfigurationException error) {
            plugin.getLogger().warning("Could not persist redemption for " + id + ": " + error.getMessage());
            failed(player);
            return true;
        }

        for (int i = 0; i < commands.size(); i++) {
            String command = RedeemConfiguration.expand(commands.get(i), id, player.getName());
            try {
                if (plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command)) continue;
            } catch (RuntimeException error) {
                plugin.getLogger().warning("Redemption dispatch threw " + error.getClass().getSimpleName() + " for " + id);
            }
            plugin.getLogger().warning("Redemption recorded for " + id + " (" + rank + "), but command #" + (i + 1)
                    + " did not complete. Review before issuing any remaining rewards.");
            failed(player);
            return true;
        }
        messages.send(player, "redeem.success", "{prefix} &aClaimed your &e{rank} &arewards.", "{rank}", rank);
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (commandManager.isDisabled("redeem") || !player.hasPermission("allium.redeem")) return;
        RedeemConfiguration.pending(config.raw(), player.getUniqueId()).ifPresent(rank ->
                messages.send(player, "redeem.join", "{prefix} &aWelcome back, &e{name}&a. Use &f/redeem &ato claim your {rank} rewards.",
                        "{rank}", rank, "{name}", player.getName()));
    }

    private void failed(Player player) {
        messages.send(player, "redeem.failed", "{prefix} &cYour rewards could not be delivered. Contact staff.");
    }
}
