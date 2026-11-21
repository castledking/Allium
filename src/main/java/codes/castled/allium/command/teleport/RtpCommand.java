package codes.castled.allium.command.teleport;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.rtp.RtpService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /rtp} ({@code /tpr}, {@code /tprandom}): {@code [location] [player]} as in Essentials' {@code /tpr},
 * plus {@code info [location]} and {@code list}. From the console a single argument is a player.
 */
public final class RtpCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final RtpService rtp;

    public RtpCommand(PluginStart plugin, RtpService rtp) {
        this.plugin = plugin;
        this.rtp = rtp;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.RTP;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (args.length >= 1 && args[0].equalsIgnoreCase("info")) {
            if (args.length > 2) {
                context.sendInvalidArgs("/rtp info [location]");
            } else {
                rtp.info(sender, args.length == 2 ? args[1] : null);
            }
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            rtp.list(sender);
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("biome")) {
            biome(context, sender, args);
            return;
        }
        if (args.length > 2) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        String location = null;
        String targetName = null;
        if (args.length == 2) {
            location = args[0];
            targetName = args[1];
        } else if (args.length == 1) {
            if (sender instanceof Player) {
                location = args[0];
            } else {
                targetName = args[0];
            }
        }
        Player target;
        if (targetName == null) {
            if (!(sender instanceof Player player)) {
                context.sendInvalidArgs(spec().consoleUsage());
                return;
            }
            target = player;
        } else {
            if (!sender.hasPermission(RtpService.PERMISSION_OTHERS)) {
                plugin.getMessageManager().send(sender, Messages.NO_PERMISSION);
                return;
            }
            target = plugin.getServer().getPlayerExact(targetName);
            if (target == null || !PlayerNameVisibility.canSeeTarget(sender, target)) {
                plugin.getMessageManager().send(sender, Messages.PLAYER_NOT_FOUND, "{player}", targetName);
                return;
            }
        }
        rtp.random(sender, target, location);
    }

    /** {@code /rtp biome <biome[,biome...]> [location]}: a spot in one of those biomes, for yourself. */
    private void biome(BukkitCommandContext context, CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().send(sender, Messages.PLAYER_ONLY);
            return;
        }
        if (!sender.hasPermission(RtpService.PERMISSION_BIOME)) {
            plugin.getMessageManager().send(sender, Messages.NO_PERMISSION);
            return;
        }
        if (args.length < 2 || args.length > 3) {
            context.sendInvalidArgs("/rtp biome <biome[,biome...]> [location]");
            return;
        }
        java.util.Set<String> wanted = new java.util.LinkedHashSet<>();
        List<String> known = rtp.biomeKeys();
        for (String raw : args[1].split(",")) {
            if (raw.isBlank()) continue;
            String key = codes.castled.allium.rtp.RtpSettings.biomeKey(raw);
            if (known.isEmpty() ? !key.matches("[a-z0-9_./-]{1,64}") : !known.contains(key)) {
                plugin.getMessageManager().send(sender, Messages.RTP_UNKNOWN_BIOME, "{biome}", raw.trim());
                return;
            }
            wanted.add(key);
        }
        if (wanted.isEmpty()) {
            context.sendInvalidArgs("/rtp biome <biome[,biome...]> [location]");
            return;
        }
        rtp.random(player, player, args.length == 3 ? args[2] : null, java.util.Set.copyOf(wanted));
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 0 || !sender.hasPermission(RtpService.PERMISSION) && !sender.hasPermission(RtpService.PERMISSION_OTHERS)) {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> values = new ArrayList<>();
        if (args.length >= 2 && args[0].equalsIgnoreCase("biome")) {
            if (!sender.hasPermission(RtpService.PERMISSION_BIOME)) return List.of();
            if (args.length == 3) {
                return rtp.names().stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(64).toList();
            }
            if (args.length != 2) return List.of();
            // Complete the entry after the last comma, keeping those before it.
            int comma = args[1].lastIndexOf(',');
            String before = comma < 0 ? "" : args[1].substring(0, comma + 1);
            String partial = args[1].substring(comma + 1).toLowerCase(Locale.ROOT);
            return rtp.biomeKeys().stream().filter(key -> key.startsWith(partial)).map(key -> before + key).limit(64).toList();
        }
        if (args.length == 1) {
            values.addAll(rtp.names());
            values.add("info");
            values.add("list");
            if (sender.hasPermission(RtpService.PERMISSION_BIOME)) values.add("biome");
            if (!(sender instanceof Player) && sender.hasPermission(RtpService.PERMISSION_OTHERS)) {
                onlineNames(sender, values);
            }
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("info")) {
                values.addAll(rtp.names());
            } else if (sender.hasPermission(RtpService.PERMISSION_OTHERS)) {
                onlineNames(sender, values);
            }
        }
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).distinct().limit(64).toList();
    }

    private void onlineNames(CommandSender sender, List<String> out) {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (PlayerNameVisibility.canSeeTarget(sender, online)) out.add(online.getName());
        }
    }
}
