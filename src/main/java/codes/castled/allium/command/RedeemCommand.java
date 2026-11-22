package codes.castled.allium.command;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.spec.CommandMessageKeys;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.config.YamlFiles;
import codes.castled.allium.message.Messages;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class RedeemCommand implements SpecCommandHandler, Listener {
    private static final String PENDING = "redeem-settings.players-to-redeem";
    private static final String CLAIMED = "redeem-settings.claimed";
    private static final CommandSpec SPEC = new CommandSpec("redeem", CommandSpec.DEFAULT_NAMESPACE, "core", List.of("claimrank"),
            "Claim configured rank rewards", "/redeem", "/redeem", "allium.redeem", null, List.of(), List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    private final PluginStart plugin;

    public RedeemCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return SPEC;
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
        plugin.getScheduler().runOnServer(() -> {
            if (player.isOnline() && player.hasPermission("allium.redeem")) redeem(player);
        });
    }

    private void redeem(Player player) {
        UUID id = player.getUniqueId();
        var file = plugin.getDataFolder().toPath().resolve("config.yml");
        List<String> commands;
        String rank;
        try {
            var config = new YamlConfiguration();
            config.load(file.toFile());
            var claim = pending(config, id);
            if (claim.isEmpty()) {
                send(player, Messages.REDEEM_UNAVAILABLE);
                return;
            }
            rank = claim.get();
            commands = config.getStringList("redeem-settings.commands-to-run." + rank).stream()
                    .map(String::strip).filter(command -> !command.isEmpty()).toList();
            if (commands.isEmpty()) {
                plugin.logger().warning("No commands configured for redemption rank " + rank);
                send(player, Messages.REDEEM_FAILED);
                return;
            }
            // Persist the claim before invoking other plugins. Their commands have no shared rollback.
            var entries = new ArrayList<>(config.getStringList(PENDING));
            entries.removeIf(entry -> claimKey(id, rank).equalsIgnoreCase(entry.strip()));
            var claimed = new ArrayList<>(config.getStringList(CLAIMED));
            claimed.add(claimKey(id, rank));
            config.set(PENDING, entries);
            config.set(CLAIMED, claimed);
            YamlFiles.normalize(file, config);
            plugin.reloadConfig();
        } catch (IOException | InvalidConfigurationException error) {
            plugin.logger().warning("Could not persist redemption for " + id + ": " + error.getClass().getSimpleName());
            send(player, Messages.REDEEM_FAILED);
            return;
        }
        for (int i = 0; i < commands.size(); i++) {
            String command = commands.get(i).replace("{player}", player.getName()).replace("{uuid}", id.toString());
            try {
                if (plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command.replaceFirst("^/", ""))) {
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
        pending(plugin.getConfig(), player.getUniqueId()).ifPresent(rank ->
                send(player, Messages.REDEEM_JOIN, "{rank}", rank, "{name}", player.getName()));
    }

    static Optional<String> pending(ConfigurationSection config, UUID player) {
        List<String> claimed = config.getStringList(CLAIMED);
        for (String entry : config.getStringList(PENDING)) {
            String[] parts = entry.strip().split(":", 2);
            if (parts.length != 2 || !player.toString().equalsIgnoreCase(parts[0])) continue;
            String rank = parts[1].toLowerCase(Locale.ROOT);
            if (rank.matches("[a-z0-9_-]+") && claimed.stream().noneMatch(claimKey(player, rank)::equalsIgnoreCase)) {
                return Optional.of(rank);
            }
        }
        return Optional.empty();
    }

    private static String claimKey(UUID player, String rank) {
        return player + ":" + rank;
    }

    private void send(Player player, Messages message, String... placeholders) {
        plugin.getMessageManager().send(player, message, placeholders);
    }
}
