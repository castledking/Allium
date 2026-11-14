package codes.castled.allium.command.info;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.info.PlayerInfoService;
import codes.castled.allium.message.Messages;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** {@code /list} (alias {@code /online}): vanish-aware online player listing grouped by permission group. */
public final class ListCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final PlayerInfoService info;

    public ListCommand(PluginStart plugin, PlayerInfoService info) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.info = Objects.requireNonNull(info, "info");
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.LIST;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (context.args().length > 0) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        CommandSender sender = context.sender();
        Map<String, List<PlayerInfoService.ListedEntry>> grouped = info.collectVisibleGroupedPlayers(sender);
        int visibleCount = grouped.values().stream().mapToInt(List::size).sum();
        int max = plugin.getServer().getMaxPlayers();
        if (visibleCount == 0) {
            plugin.getMessageManager().send(sender, Messages.INFO_LIST_EMPTY, "{max}", Integer.toString(max));
            return;
        }
        plugin.getMessageManager().send(
                sender,
                Messages.INFO_LIST_HEADER,
                "{online}", Integer.toString(visibleCount),
                "{max}", Integer.toString(max)
        );
        for (Map.Entry<String, List<PlayerInfoService.ListedEntry>> entry : grouped.entrySet()) {
            String formattedPlayers = entry.getValue().stream()
                    .map(p -> p.name()
                            + (p.afk() ? " &7[AFK]&f" : "")
                            + (p.vanished() ? " &8[Vanished]&f" : ""))
                    .collect(Collectors.joining(", "));
            plugin.getMessageManager().send(
                    sender,
                    Messages.INFO_LIST_GROUP,
                    "{group}", entry.getKey(),
                    "{count}", Integer.toString(entry.getValue().size()),
                    "{players}", formattedPlayers
            );
        }
    }
}
