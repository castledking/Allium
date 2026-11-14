package codes.castled.allium.command.kits;

import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.kit.KitService;
import codes.castled.allium.message.Messages;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * Handles {@code /kit}, {@code /kits}, {@code /createkit} and {@code /delkit}.
 */
public final class KitCommand implements SpecCommandHandler {

    private final KitService service;
    private final CommandSpec spec;

    public KitCommand(KitService service, CommandSpec spec) {
        this.service = Objects.requireNonNull(service, "service");
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    @Override
    public CommandSpec spec() {
        return spec;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        switch (spec.canonicalName()) {
            case "kits" -> {
                if (args.length != 0) {
                    context.sendInvalidArgs(spec.selfUsage());
                    return;
                }
                service.listKits(sender);
            }
            case "kit" -> {
                if (args.length == 0) {
                    service.listKits(sender);
                    return;
                }
                if (args.length > 2) {
                    context.sendInvalidArgs(spec.selfUsage());
                    return;
                }
                if (args.length == 1) {
                    if (!(sender instanceof Player player)) {
                        context.plugin().getMessageManager().send(sender, Messages.PLAYER_ONLY);
                        return;
                    }
                    service.claim(player, player, args[0]);
                    return;
                }
                if (!sender.hasPermission(KitService.PERMISSION_KIT_OTHERS)) {
                    context.plugin().getMessageManager().send(sender, Messages.NO_PERMISSION);
                    return;
                }
                Player target = context.plugin().getServer().getPlayerExact(args[1]);
                if (target == null) {
                    context.plugin().getMessageManager().send(sender, Messages.PLAYER_NOT_FOUND, "{player}", args[1]);
                    return;
                }
                service.claim(sender, target, args[0]);
            }
            case "createkit" -> {
                if (!(sender instanceof Player player)) {
                    context.plugin().getMessageManager().send(sender, Messages.PLAYER_ONLY);
                    return;
                }
                if (args.length < 1 || args.length > 3) {
                    context.sendInvalidArgs(spec.selfUsage());
                    return;
                }
                long cooldown = 0L;
                boolean oneTime = false;
                if (args.length == 2) {
                    if (args[1].equalsIgnoreCase("once") || args[1].equalsIgnoreCase("onetime")) {
                        oneTime = true;
                    } else {
                        Long parsed = parseCooldown(args[1]);
                        if (parsed == null) {
                            context.plugin().getMessageManager().send(sender, Messages.NUMBER_INVALID, "{input}", args[1]);
                            return;
                        }
                        cooldown = parsed;
                    }
                } else if (args.length == 3) {
                    Long parsed = parseCooldown(args[1]);
                    if (parsed == null) {
                        context.plugin().getMessageManager().send(sender, Messages.NUMBER_INVALID, "{input}", args[1]);
                        return;
                    }
                    if (!args[2].equalsIgnoreCase("once") && !args[2].equalsIgnoreCase("onetime")) {
                        context.sendInvalidArgs(spec.selfUsage());
                        return;
                    }
                    cooldown = parsed;
                    oneTime = true;
                }
                service.createKit(player, args[0], cooldown, oneTime);
            }
            case "delkit" -> {
                if (args.length != 1) {
                    context.sendInvalidArgs(spec.selfUsage());
                    return;
                }
                service.deleteKit(sender, args[0]);
            }
            default -> context.sendInvalidArgs(spec.selfUsage());
        }
    }

    private static Long parseCooldown(String raw) {
        try {
            long value = Long.parseLong(raw);
            return value < 0L ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
