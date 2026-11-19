package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.broadcast.BroadcastService;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.message.Messages;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

/**
 * {@code /broadcast <message>} sends staff's message to everyone in {@code chat.broadcast};
 * {@code /broadcasts} shows the timed broadcasts and their settings, {@code /broadcasts send <name>}
 * sends one now and {@code /broadcasts next} the next in the rotation.
 */
public final class BroadcastCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final CommandSpec spec;
    private final BroadcastService service;

    public BroadcastCommand(PluginStart plugin, CommandSpec spec, BroadcastService service) {
        this.plugin = plugin;
        this.spec = spec;
        this.service = service;
    }

    @Override
    public CommandSpec spec() {
        return spec;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (spec.canonicalName().equals("broadcast")) {
            String message = String.join(" ", args).trim();
            if (message.isEmpty()) {
                context.sendInvalidArgs(spec.selfUsage());
                return;
            }
            service.announce(sender, message);
            return;
        }
        if (args.length == 0) {
            status(sender);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "send" -> {
                if (args.length != 2) {
                    context.sendInvalidArgs("/broadcasts send <name>");
                    return;
                }
                String name = service.file().document() == null ? args[1]
                        : service.file().document().primaryName(args[1]).orElse(args[1]);
                if (!service.send(name)) {
                    UtilitySupport.send(plugin, sender, Messages.BROADCASTS_UNKNOWN, "{name}", args[1]);
                    return;
                }
                UtilitySupport.send(plugin, sender, Messages.BROADCASTS_SENT, "{name}", name);
            }
            case "next" -> {
                if (args.length != 1) {
                    context.sendInvalidArgs("/broadcasts next");
                    return;
                }
                var next = service.next();
                if (next.isEmpty() || !service.send(next.get())) {
                    UtilitySupport.send(plugin, sender, Messages.CUSTOMTEXT_EMPTY, "{file}", BroadcastService.FILE);
                    return;
                }
                UtilitySupport.send(plugin, sender, Messages.BROADCASTS_SENT, "{name}", next.get());
            }
            default -> context.sendInvalidArgs(spec.selfUsage());
        }
    }

    private void status(CommandSender sender) {
        BroadcastService.Settings settings = service.settings();
        List<String> names = service.broadcasts();
        String state = plugin.getMessageManager().resolve(settings.enabled() ? Messages.BROADCASTS_ON : Messages.BROADCASTS_OFF);
        UtilitySupport.send(plugin, sender, Messages.BROADCASTS_STATUS, "{state}", state,
                "{interval}", Long.toString(settings.intervalSeconds()),
                "{order}", settings.order().name().toLowerCase(Locale.ROOT),
                "{minimum}", Integer.toString(settings.minimumPlayers()),
                "{broadcasts}", names.isEmpty() ? "-" : String.join(", ", names));
    }

    /** Broadcast names for {@code /broadcasts send <tab>}. */
    public List<String> complete(CommandSender sender, String[] args) {
        if (!spec.canonicalName().equals("broadcasts") || !sender.hasPermission(spec.basePermission())) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("send", "next").stream().filter(v -> v.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("send")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return service.broadcasts().stream().filter(n -> n.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
