package codes.castled.allium.command.teleport;

import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.rtp.RtpService;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code /settpr <location> <center|minrange|maxrange|shape|miny|maxy|world|remove> [value]} ({@code /settprandom}). */
public final class SetTprCommand implements SpecCommandHandler {

    private final RtpService rtp;
    private final java.util.function.Supplier<List<String>> worlds;

    public SetTprCommand(RtpService rtp, java.util.function.Supplier<List<String>> worlds) {
        this.rtp = rtp;
        this.worlds = worlds;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.SETTPR;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        rtp.set(context.sender(), context.args(), context::sendInvalidArgs);
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 0 || !sender.hasPermission(RtpService.PERMISSION_SET)) {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> values = new ArrayList<>();
        if (args.length == 1) {
            values.addAll(rtp.names());
        } else if (args.length == 2) {
            values.addAll(List.of("center", "minrange", "maxrange", "shape", "miny", "maxy", "world", "remove"));
        } else if (args.length == 3) {
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "shape" -> values.addAll(List.of("square", "circle"));
                case "miny", "maxy" -> values.add("none");
                case "world" -> values.addAll(worlds.get());
                case "minrange" -> values.addAll(List.of("0", "100", "500"));
                case "maxrange" -> values.addAll(List.of("1000", "5000", "10000"));
                default -> {
                }
            }
        }
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).distinct().limit(64).toList();
    }
}
