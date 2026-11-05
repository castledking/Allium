package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public final class GamemodeCommand implements SpecCommandHandler {

    private final PluginStart plugin;

    public GamemodeCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.GAMEMODE;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        String[] args = context.args();
        if (args.length == 0) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }

        GameMode mode = parseGamemode(args[0]);
        if (mode == null) {
            context.sendInvalidArgs("/gamemode <survival|creative|adventure|spectator> [player]");
            return;
        }

        Player target = context.resolveSelfOrTarget(1);
        if (target == null) {
            return;
        }

        target.setGameMode(mode);
        String modeName = mode.name().toLowerCase(Locale.ROOT);
        if (target != context.sender()) {
            context.sendOutcome(context.sender(), "other", "{player}", target.getName(), "{mode}", modeName);
        }
        context.sendOutcome(target, "self", "{mode}", modeName);
    }

    @Nullable
    public static GameMode parseGamemode(String input) {
        if (input == null) {
            return null;
        }
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "0", "s", "survival" -> GameMode.SURVIVAL;
            case "1", "c", "creative" -> GameMode.CREATIVE;
            case "2", "a", "adventure" -> GameMode.ADVENTURE;
            case "3", "sp", "spectator" -> GameMode.SPECTATOR;
            default -> null;
        };
    }
}
