package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
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

        String modeName = mode.name().toLowerCase(Locale.ROOT);
        // Each mode has its own node (legacy Allium): /gamemode alone grants none of them.
        if (!context.sender().hasPermission(modePermission(mode))) {
            plugin.getMessageManager().send(context.sender(), Messages.GAMEMODE_MODE_DENIED, "{mode}", modeName);
            return;
        }

        Player target = context.resolveSelfOrTarget(1);
        if (target == null) {
            return;
        }

        target.setGameMode(mode);
        if (target != context.sender()) {
            context.sendOutcome(context.sender(), "other", "{player}", target.getName(), "{mode}", modeName);
        }
        context.sendOutcome(target, "self", "{mode}", modeName);
    }

    /** {@code allium.gamemode.<mode>}, required to switch anyone (yourself or, with .others, another player) to that mode. */
    public static String modePermission(GameMode mode) {
        return "allium.gamemode." + mode.name().toLowerCase(Locale.ROOT);
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
