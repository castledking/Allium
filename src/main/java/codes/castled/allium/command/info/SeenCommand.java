package codes.castled.allium.command.info;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.info.PlayerInfoRecord;
import codes.castled.allium.info.PlayerInfoService;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.command.essentials.UtilitySupport;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;

/** {@code /seen <player>}: online/offline status, last-seen, and first-join enforcing vanish "looks offline". */
public final class SeenCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final PlayerInfoService info;

    public SeenCommand(PluginStart plugin, PlayerInfoService info) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.info = Objects.requireNonNull(info, "info");
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.SEEN;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        String[] args = context.args();
        if (args.length != 1 || args[0].isBlank()) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        CommandSender sender = context.sender();
        String query = args[0].trim();

        info.lookupProfile(sender, query, false).whenComplete((opt, error) -> info.runFor(sender, () -> {
            if (error != null) {
                plugin.logger().warning("Failed /seen lookup for " + query + ": " + error.getMessage());
                plugin.getMessageManager().send(sender, Messages.INFO_STORAGE_FAILED);
                return;
            }
            if (opt.isEmpty()) {
                plugin.getMessageManager().send(sender, Messages.INFO_SEEN_UNKNOWN, "{player}", query);
                return;
            }

            PlayerInfoRecord record = opt.get().record();
            Player liveOnline = plugin.getServer().getPlayer(record.uuid());
            String firstJoin = info.formatTimestamp(record.firstJoin());
            var extras = new codes.castled.allium.info.WhoisExtras(plugin);
            Runnable offline = () -> {
                plugin.getMessageManager().send(sender, Messages.INFO_SEEN_OFFLINE,
                        "{player}", record.lastName(),
                        "{last_seen}", info.formatDurationSince(record.lastSeen()),
                        "{last_seen_date}", info.formatTimestamp(record.lastSeen()),
                        "{first_join}", firstJoin);
                extras.sendSeen(sender, record.uuid());
            };
            if (liveOnline == null) {
                offline.run();
                return;
            }
            boolean seeVanished = !(sender instanceof Player) || sender == liveOnline || sender.hasPermission("allium.vanish.see");
            UtilitySupport.onTarget(plugin, sender, liveOnline, () -> {
                boolean vanished = PlayerNameVisibility.isVanished(liveOnline);
                if (!liveOnline.isOnline() || vanished && !seeVanished) {
                    UtilitySupport.onOwner(plugin, sender, offline);
                    return;
                }
                String tags = (info.isAfk(liveOnline) ? " &7[AFK]" : "") + (vanished ? " &8[Vanished]" : "");
                String duration = PlayerInfoService.formatElapsed(info.onlineDurationMillis(liveOnline, record));
                String name = liveOnline.getName();
                UtilitySupport.onOwner(plugin, sender, () -> {
                    plugin.getMessageManager().send(sender, Messages.INFO_SEEN_ONLINE,
                            "{player}", name, "{tags}", tags, "{duration}", duration, "{first_join}", firstJoin);
                    extras.sendSeen(sender, record.uuid());
                });
            }, offline);
        }));
    }
}
