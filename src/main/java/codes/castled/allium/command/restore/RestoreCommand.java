package codes.castled.allium.command.restore;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.restore.RestoreMenus;
import codes.castled.allium.restore.RestoreService;
import codes.castled.allium.scheduler.BukkitServerThread;
import org.bukkit.entity.Player;

/** {@code /restore [player]}: opens the inventory snapshot history (yours, or another player's with .others). */
public final class RestoreCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final RestoreMenus menus;

    public RestoreCommand(PluginStart plugin, RestoreMenus menus) {
        this.plugin = plugin;
        this.menus = menus;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.RESTORE;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player viewer)) {
            plugin.getMessageManager().send(context.sender(), Messages.PLAYER_ONLY);
            return;
        }
        String[] args = context.args();
        if (args.length > 1) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase(viewer.getName())) {
            menus.open(viewer, viewer.getUniqueId(), viewer.getName(), 0);
            return;
        }
        if (!viewer.hasPermission(RestoreService.PERMISSION_OTHERS)) {
            plugin.getMessageManager().send(viewer, Messages.NO_PERMISSION, "{cmd}", "restore " + args[0]);
            return;
        }
        String name = args[0];
        if (plugin.getPlayerNames() == null || !name.matches("[A-Za-z0-9_]{1,16}")) {
            plugin.getMessageManager().send(viewer, Messages.INFO_SEEN_UNKNOWN, "{player}", name);
            return;
        }
        plugin.getPlayerNames().resolve(name).whenComplete((entry, error) -> BukkitServerThread.runFor(plugin, plugin.getScheduler(), viewer, () -> {
            if (!viewer.isOnline()) return;
            if (error != null || entry.isEmpty()) {
                plugin.getMessageManager().send(viewer, Messages.INFO_SEEN_UNKNOWN, "{player}", name);
                return;
            }
            menus.open(viewer, entry.get().uuid(), entry.get().name(), 0);
        }, () -> {}, 0L));
    }
}
