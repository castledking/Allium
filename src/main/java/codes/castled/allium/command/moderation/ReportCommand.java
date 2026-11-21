package codes.castled.allium.command.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.moderation.ReportService;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /report <player> <what happened>} or {@code /report <message>}, and
 * {@code /escalate <target> <reason>}: see {@link ReportService}.
 */
public final class ReportCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final ReportService reports;
    private final boolean escalate;

    private ReportCommand(PluginStart plugin, ReportService reports, boolean escalate) {
        this.plugin = plugin;
        this.reports = reports;
        this.escalate = escalate;
    }

    public static ReportCommand report(PluginStart plugin, ReportService reports) {
        return new ReportCommand(plugin, reports, false);
    }

    public static ReportCommand escalate(PluginStart plugin, ReportService reports) {
        return new ReportCommand(plugin, reports, true);
    }

    @Override
    public CommandSpec spec() {
        return escalate ? CoreCommandSpecs.ESCALATE : CoreCommandSpecs.REPORT;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        String[] args = context.args();
        if (!escalate) {
            reports.report(context.sender(), args, () -> context.sendInvalidArgs(spec().selfUsage()));
            return;
        }
        if (args.length < 2) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        String staff = context.sender() instanceof Player player ? player.getName() : "Console";
        reports.escalate(context.sender(), staff, args[0], String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player == sender || !PlayerNameVisibility.canSeeTarget(sender, player)) continue;
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) names.add(player.getName());
        }
        return names;
    }
}
