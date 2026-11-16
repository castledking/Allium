package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.essentials.WorldTimeParser;
import codes.castled.allium.message.Messages;
import org.bukkit.World;

import java.util.List;

/** Shared executor for {@code /time}, {@code /day}, and {@code /night}. */
public final class TimeCommand implements SpecCommandHandler {

    public static final long DAY_TICKS = 1000L;
    public static final long NIGHT_TICKS = 13_000L;

    private final PluginStart plugin;
    private final CommandSpec spec;
    private final TimePauseCoordinator pauseCoordinator;

    public TimeCommand(PluginStart plugin, CommandSpec spec, TimePauseCoordinator pauseCoordinator) {
        this.plugin = plugin;
        this.spec = spec;
        this.pauseCoordinator = pauseCoordinator;
    }

    @Override
    public CommandSpec spec() {
        return spec;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        String name = spec.canonicalName();
        if ("day".equals(name) || "night".equals(name)) {
            executeFixedShortcut(context, "day".equals(name) ? DAY_TICKS : NIGHT_TICKS);
            return;
        }

        var parsedOpt = WorldTimeParser.parseTimeCommand(context.args());
        if (parsedOpt.isEmpty()) {
            context.sendInvalidArgs(spec.selfUsage());
            return;
        }
        var parsed = parsedOpt.get();
        switch (parsed.action()) {
            case QUERY -> handleQuery(context, parsed.worldSelector());
            case SET -> {
                if (!checkPerm(context, "allium.time.set", "time set")) return;
                handleSet(context, parsed.ticks(), parsed.worldSelector());
            }
            case ADD -> {
                if (!checkPerm(context, "allium.time.add", "time add")) return;
                handleAdd(context, parsed.ticks(), parsed.worldSelector());
            }
            case PAUSE -> {
                if (!checkPerm(context, "allium.time.pause", "time pause")) return;
                handlePause(context, parsed.pauseState(), parsed.worldSelector());
            }
        }
    }

    private void executeFixedShortcut(BukkitCommandContext context, long ticks) {
        String[] args = context.args();
        if (args.length > 1) {
            context.sendInvalidArgs(spec.selfUsage());
            return;
        }
        String worldSelector = args.length == 1 ? args[0] : null;
        handleSet(context, ticks, worldSelector);
    }

    private void handleQuery(BukkitCommandContext context, String worldSelector) {
        List<World> worlds = UtilitySupport.resolveWorlds(plugin, context.sender(), worldSelector);
        if (worlds == null) {
            return;
        }
        for (World world : worlds) {
            String status = pauseCoordinator != null && pauseCoordinator.isPaused(world) ? " &7[paused]" : "";
            UtilitySupport.send(plugin, context.sender(), Messages.TIME_QUERY,
                    "{world}", world.getName(),
                    "{time}", WorldTimeParser.formatTime(world.getTime()),
                    "{status}", status);
        }
    }

    private void handleSet(BukkitCommandContext context, long ticks, String worldSelector) {
        List<World> worlds = UtilitySupport.resolveWorlds(plugin, context.sender(), worldSelector);
        if (worlds == null) {
            return;
        }
        for (World world : worlds) {
            world.setTime(ticks);
            if (pauseCoordinator != null) {
                pauseCoordinator.updatePinnedTime(world, ticks);
            }
            UtilitySupport.send(plugin, context.sender(), Messages.TIME_SET,
                    "{time}", WorldTimeParser.formatTime(ticks),
                    "{world}", world.getName());
            UtilitySupport.audit(plugin, context.sender(), "time_set",
                    "world=" + world.getName() + " ticks=" + ticks);
        }
    }

    private void handleAdd(BukkitCommandContext context, long delta, String worldSelector) {
        List<World> worlds = UtilitySupport.resolveWorlds(plugin, context.sender(), worldSelector);
        if (worlds == null) {
            return;
        }
        for (World world : worlds) {
            long updated = world.getTime() + delta;
            world.setTime(updated);
            if (pauseCoordinator != null) {
                pauseCoordinator.updatePinnedTime(world, updated);
            }
            UtilitySupport.send(plugin, context.sender(), Messages.TIME_ADD,
                    "{delta}", Long.toString(delta),
                    "{time}", WorldTimeParser.formatTime(updated),
                    "{world}", world.getName());
            UtilitySupport.audit(plugin, context.sender(), "time_add",
                    "world=" + world.getName() + " delta=" + delta + " now=" + updated);
        }
    }

    private void handlePause(BukkitCommandContext context, Boolean desired, String worldSelector) {
        List<World> worlds = UtilitySupport.resolveWorlds(plugin, context.sender(), worldSelector);
        if (worlds == null || pauseCoordinator == null) {
            return;
        }
        for (World world : worlds) {
            boolean nowPaused;
            if (desired == null) {
                nowPaused = pauseCoordinator.toggle(world);
            } else if (desired) {
                pauseCoordinator.pause(world);
                nowPaused = true;
            } else {
                pauseCoordinator.unpause(world);
                nowPaused = false;
            }
            UtilitySupport.send(plugin, context.sender(), Messages.TIME_PAUSE,
                    "{state}", nowPaused ? "&epaused" : "&aresumed",
                    "{world}", world.getName(),
                    "{time}", WorldTimeParser.formatTime(world.getTime()));
            UtilitySupport.audit(plugin, context.sender(), "time_pause",
                    "world=" + world.getName() + " paused=" + nowPaused);
        }
    }

    private boolean checkPerm(BukkitCommandContext context, String perm, String label) {
        if (context.sender().hasPermission(perm)) {
            return true;
        }
        UtilitySupport.send(plugin, context.sender(), Messages.NO_PERMISSION, "{cmd}", label);
        return false;
    }
}
