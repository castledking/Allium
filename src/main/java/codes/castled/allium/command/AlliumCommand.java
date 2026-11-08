package codes.castled.allium.command;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.text.TextSender;
import org.bukkit.command.CommandSender;

import java.util.Locale;

/** {@code /allium version} and {@code /allium reload} (see {@link PluginStart#reloadConfiguration}). */
public final class AlliumCommand implements SpecCommandHandler {

    private final PluginStart plugin;

    public AlliumCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.ALLIUM;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String action = context.args().length == 0 ? "version" : context.args()[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "version" -> context.sendOutcome(sender, "version", "{version}", plugin.getDescription().getVersion());
            case "reload" -> {
                if (!sender.hasPermission("allium.reload")) {
                    plugin.getMessageManager().sendByKey(sender, spec().messageKeys().noPermissionKey(), "{cmd}", "allium reload");
                    return;
                }
                boolean ok = plugin.reloadConfiguration(line -> TextSender.send(sender, "&7" + line));
                context.sendOutcome(sender, ok ? "reloaded" : "reload-failed");
            }
            default -> context.sendInvalidArgs(spec().selfUsage());
        }
    }
}
