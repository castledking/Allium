package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.glow.GlowService;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /glow <colour|rainbow|off|spectator> [player]}, ported from legacy Allium: a colour needs
 * {@code allium.glow.<colour>} (or {@code allium.glow.*}), rainbow {@code allium.glow.rainbow},
 * another player {@code allium.glow.others}, and {@code spectator} (show the glow only in
 * spectator) {@code allium.gamemode.spectator}.
 */
public final class GlowCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final Supplier<GlowService> glow;

    public GlowCommand(PluginStart plugin, Supplier<GlowService> glow) {
        this.plugin = plugin;
        this.glow = glow;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.GLOW;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        GlowService service = glow.get();
        if (args.length == 0 || args.length > 2 || service == null) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        String choice = args[0].toLowerCase(Locale.ROOT).replace(' ', '_');
        Player target = target(context, args);
        if (target == null) return;
        boolean self = target == sender;
        switch (choice) {
            case "off", "none", "remove" -> {
                service.choose(target, null);
                send(sender, self ? Messages.GLOW_OFF : Messages.GLOW_OFF_OTHER, "{player}", target.getName());
                if (!self) send(target, Messages.GLOW_OFF);
            }
            case "spectator" -> {
                if (!sender.hasPermission("allium.gamemode.spectator")) {
                    send(sender, Messages.NO_PERMISSION);
                    return;
                }
                boolean on = service.toggleSpectatorOnly(target);
                send(target, on ? Messages.GLOW_SPECTATOR_ON : Messages.GLOW_SPECTATOR_OFF);
                if (!self) send(sender, on ? Messages.GLOW_SPECTATOR_ON_OTHER : Messages.GLOW_SPECTATOR_OFF_OTHER, "{player}", target.getName());
            }
            default -> {
                if (!choice.equals(GlowService.RAINBOW) && !GlowService.COLORS.containsKey(choice)) {
                    send(sender, Messages.GLOW_INVALID, "{color}", args[0], "{colors}", String.join(", ", GlowService.COLORS.keySet()));
                    return;
                }
                if (!allowed(sender, choice)) {
                    send(sender, Messages.GLOW_COLOR_DENIED, "{color}", GlowService.display(choice));
                    return;
                }
                service.choose(target, choice);
                String shown = GlowService.display(choice);
                send(sender, self ? Messages.GLOW_SET : Messages.GLOW_SET_OTHER, "{color}", shown, "{player}", target.getName());
                if (!self) send(target, Messages.GLOW_SET, "{color}", shown);
                if (service.overridden(target)) send(target, Messages.GLOW_OVERRIDDEN);
                else if (service.spectatorOnly(target) && target.getGameMode() != GameMode.SPECTATOR) send(target, Messages.GLOW_HELD);
            }
        }
    }

    private Player target(BukkitCommandContext context, String[] args) {
        CommandSender sender = context.sender();
        if (args.length == 1) {
            if (sender instanceof Player player) return player;
            context.sendInvalidArgs(spec().consoleUsage());
            return null;
        }
        if (!sender.hasPermission("allium.glow.others")) {
            send(sender, Messages.NO_PERMISSION);
            return null;
        }
        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target == null || !PlayerNameVisibility.canSeeTarget(sender, target)) {
            send(sender, Messages.PLAYER_NOT_FOUND, "{player}", args[1]);
            return null;
        }
        return target;
    }

    static boolean allowed(CommandSender sender, String choice) {
        if (choice.equals(GlowService.RAINBOW)) return sender.hasPermission("allium.glow.rainbow");
        return sender.hasPermission("allium.glow." + choice) || sender.hasPermission("allium.glow.*");
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.glow")) return List.of();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String name : GlowService.COLORS.keySet()) if (allowed(sender, name)) out.add(name);
            if (allowed(sender, GlowService.RAINBOW)) out.add(GlowService.RAINBOW);
            out.add("off");
            if (sender.hasPermission("allium.gamemode.spectator")) out.add("spectator");
            return out.stream().filter(v -> v.startsWith(prefix)).toList();
        }
        if (args.length == 2 && sender.hasPermission("allium.glow.others")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return plugin.getServer().getOnlinePlayers().stream().filter(p -> PlayerNameVisibility.canSee(sender, p))
                    .map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().limit(50).toList();
        }
        return List.of();
    }

    private void send(CommandSender to, Messages message, String... args) {
        plugin.getMessageManager().send(to, message, args);
    }
}
