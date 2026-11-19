package codes.castled.allium.command.info;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.info.PlayerInfoRecord;
import codes.castled.allium.info.PlayerInfoService;
import codes.castled.allium.info.Playtime;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.placeholder.PlaceholderFormats;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * {@code /playtime [player]}: time played and the first-join date. Another player needs
 * {@code allium.playtime.others}; an offline (or hidden) player's playtime comes from their stats
 * file, read off the server thread.
 */
public final class PlaytimeCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final PlayerInfoService info;

    public PlaytimeCommand(PluginStart plugin, PlayerInfoService info) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.info = Objects.requireNonNull(info, "info");
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.PLAYTIME;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (args.length > 1 || args.length == 0 && !(sender instanceof Player)) {
            context.sendInvalidArgs(sender instanceof Player ? spec().selfUsage() : spec().consoleUsage());
            return;
        }
        if (args.length == 0 || sender instanceof Player self && args[0].equalsIgnoreCase(self.getName())) {
            Player self = (Player) sender;
            plugin.getMessageManager().send(self, Messages.INFO_PLAYTIME_SELF,
                    "{playtime}", PlaceholderFormats.duration(Playtime.seconds(self)),
                    "{first_join}", Playtime.date(plugin, Playtime.firstJoin(plugin, self)));
            return;
        }
        if (!sender.hasPermission("allium.playtime.others")) {
            plugin.getMessageManager().send(sender, Messages.NO_PERMISSION);
            return;
        }
        String query = args[0].trim();
        info.lookupProfile(sender, query, false).whenComplete((found, error) -> info.runOnServer(() -> {
            if (error != null) {
                plugin.getMessageManager().send(sender, Messages.INFO_STORAGE_FAILED);
                return;
            }
            if (found.isEmpty()) {
                plugin.getMessageManager().send(sender, Messages.INFO_SEEN_UNKNOWN, "{player}", query);
                return;
            }
            PlayerInfoRecord record = found.get().record();
            Player online = plugin.getServer().getPlayer(record.uuid());
            if (online != null && PlayerNameVisibility.canSeeTarget(sender, online)) {
                tell(sender, online.getName(), Playtime.seconds(online), Playtime.firstJoin(plugin, online));
                return;
            }
            Path world = plugin.getServer().getWorlds().get(0).getWorldFolder().toPath();
            plugin.getScheduler().runAsyncLater(() -> {
                OptionalLong seconds;
                try {
                    seconds = Playtime.offlineSeconds(world, record.uuid());
                } catch (java.io.IOException | RuntimeException unreadable) {
                    seconds = OptionalLong.empty();
                }
                long played = seconds.orElse(0);
                info.runOnServer(() -> {
                    long first = record.firstJoin() > 0 ? record.firstJoin() : plugin.getServer().getOfflinePlayer(record.uuid()).getFirstPlayed();
                    tell(sender, record.lastName(), played, first);
                });
            }, 0);
        }));
    }

    private void tell(CommandSender sender, String name, long seconds, long firstJoin) {
        plugin.getMessageManager().send(sender, Messages.INFO_PLAYTIME_OTHER, "{player}", name,
                "{playtime}", PlaceholderFormats.duration(seconds), "{first_join}", Playtime.date(plugin, firstJoin));
    }
}
