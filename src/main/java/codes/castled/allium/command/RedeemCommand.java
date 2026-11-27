package codes.castled.allium.command;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.config.YamlFiles;
import codes.castled.allium.message.Messages;
import codes.castled.allium.redeem.RedeemConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

public final class RedeemCommand implements SpecCommandHandler, Listener {
    private final PluginStart plugin;

    public RedeemCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.REDEEM;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            plugin.getMessageManager().send(context.sender(), Messages.PLAYER_ONLY);
            return;
        }
        if (context.args().length != 0) {
            context.sendInvalidArgs("/redeem");
            return;
        }
        UtilitySupport.onOwner(plugin, player, () -> {
            if (!player.isOnline() || !player.hasPermission("allium.redeem")) return;
            UUID id = player.getUniqueId();
            String name = player.getName();
            // The claim file and console command dispatch are shared across all players.
            plugin.getScheduler().runOnServer(() -> {
                if (plugin.getServer().getPlayer(id) == player) redeem(player, id, name);
            });
        });
    }

    private void redeem(Player player, UUID id, String name) {
        var file = plugin.getDataFolder().toPath().resolve("config.yml");
        List<String> commands;
        String rank;
        try {
            var config = new YamlConfiguration();
            config.load(file.toFile());
            var claim = RedeemConfiguration.pending(config, id);
            if (claim.isEmpty()) {
                send(player, Messages.REDEEM_UNAVAILABLE);
                return;
            }
            rank = claim.get();
            commands = RedeemConfiguration.commands(config, rank);
            if (commands.isEmpty()) {
                plugin.logger().warning("No commands configured for redemption rank " + rank);
                send(player, Messages.REDEEM_FAILED);
                return;
            }
            // Persist the claim before invoking other plugins. Their commands have no shared rollback.
            RedeemConfiguration.consume(config, id, rank);
            YamlFiles.normalize(file, config);
            plugin.reloadConfig();
        } catch (IOException | InvalidConfigurationException error) {
            plugin.logger().warning("Could not persist redemption for " + id + ": " + error.getClass().getSimpleName());
            send(player, Messages.REDEEM_FAILED);
            return;
        }
        for (int i = 0; i < commands.size(); i++) {
            String command = RedeemConfiguration.expand(commands.get(i), id, name);
            try {
                if (plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command)) {
                    continue;
                }
            } catch (RuntimeException error) {
                plugin.logger().warning("Redemption dispatch threw " + error.getClass().getSimpleName() + " for " + id);
            }
            plugin.logger().warning("Redemption recorded for " + id + " (" + rank + "), but command #" + (i + 1)
                    + " did not complete. Review before issuing any remaining rewards.");
            send(player, Messages.REDEEM_FAILED);
            return;
        }
        send(player, Messages.REDEEM_SUCCESS, "{rank}", rank);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("allium.redeem")) return;
        RedeemConfiguration.pending(plugin.getConfig(), player.getUniqueId()).ifPresent(rank ->
                send(player, Messages.REDEEM_JOIN, "{rank}", rank, "{name}", player.getName()));
    }

    private void send(Player player, Messages message, String... placeholders) {
        UtilitySupport.send(plugin, player, message, placeholders);
    }
}
