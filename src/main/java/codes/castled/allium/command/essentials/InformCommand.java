package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /inform [player] [chapter] [page]} (staff): shows a player their {@code /info} pages, from
 * the file they read ({@code info_<name>.txt}, {@code info_<group>.txt} or {@code info.txt}) and
 * with their own placeholders. The sender is told it was shown, or why not. With no player it shows
 * your own, as {@code /info} does.
 */
public final class InformCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final TextFileCommand info;

    public InformCommand(PluginStart plugin, TextFileCommand info) {
        this.plugin = plugin;
        this.info = info;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.INFORM;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        Runnable usage = () -> context.sendInvalidArgs(spec().selfUsage());
        if (args.length > 3 || args.length == 0 && !(sender instanceof Player)) {
            usage.run();
            return;
        }
        if (args.length == 0) {
            info.show(sender, sender, args, usage);
            return;
        }
        Player target = UtilitySupport.resolveVisibleOnlinePlayer(plugin, sender, args[0]);
        if (target == null) {
            return;
        }
        String[] page = Arrays.copyOfRange(args, 1, args.length);
        if (!info.show(target, sender, page, usage) || target == sender) {
            return;
        }
        boolean chapter = page.length > 0 && !page[0].matches("[0-9]{1,6}");
        UtilitySupport.send(plugin, sender, chapter ? Messages.INFORM_SENT_CHAPTER : Messages.INFORM_SENT,
                "{player}", target.getName(), "{section}", chapter ? page[0] : "");
    }

    /** Players you can see, then the chapters of the file that player reads. */
    public List<String> complete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(spec().basePermission()) || args.length == 0 || args.length > 2) {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            return plugin.getServer().getOnlinePlayers().stream()
                    .filter(player -> PlayerNameVisibility.canSeeTarget(sender, player))
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .limit(50)
                    .toList();
        }
        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null || !PlayerNameVisibility.canSeeTarget(sender, target)) {
            return List.of();
        }
        return info.sections(target, prefix);
    }
}
