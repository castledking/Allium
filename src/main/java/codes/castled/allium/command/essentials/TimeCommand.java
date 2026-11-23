package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.essentials.WorldTimeParser;
import codes.castled.allium.message.Messages;
import org.bukkit.World;

import java.util.List;
import java.util.function.Consumer;

/** Shared executor for {@code /time}, {@code /day}, and {@code /night}. */
public final class TimeCommand implements SpecCommandHandler.Immediate {

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
    public boolean executeWithResult(BukkitCommandContext context) {
        String name = spec.canonicalName();
        if ("day".equals(name) || "night".equals(name)) {
            return executeFixedShortcut(context, "day".equals(name) ? DAY_TICKS : NIGHT_TICKS);
        }

        var parsedOpt = WorldTimeParser.parseTimeCommand(context.args());
        if (parsedOpt.isEmpty()) {
            context.sendInvalidArgs(spec.selfUsage());
            return false;
        }
        var parsed = parsedOpt.get();
        return switch (parsed.action()) {
            case QUERY -> handleQuery(context, parsed.worldSelector());
            case SET -> {
                yield checkPerm(context, "allium.time.set", "time set")
                        && handleSet(context, parsed.ticks(), parsed.worldSelector());
            }
            case ADD -> {
                yield checkPerm(context, "allium.time.add", "time add")
                        && handleAdd(context, parsed.ticks(), parsed.worldSelector());
            }
            case PAUSE -> {
                yield checkPerm(context, "allium.time.pause", "time pause")
                        && handlePause(context, parsed.pauseState(), parsed.worldSelector());
            }
        };
    }

    private boolean executeFixedShortcut(BukkitCommandContext context, long ticks) {
        String[] args = context.args();
        if (args.length > 1) {
            context.sendInvalidArgs(spec.selfUsage());
            return false;
        }
        String worldSelector = args.length == 1 ? args[0] : null;
        return handleSet(context, ticks, worldSelector);
    }

    private boolean handleQuery(BukkitCommandContext context, String worldSelector) {
        List<World> worlds = UtilitySupport.resolveWorlds(plugin, context.sender(), worldSelector);
        if (worlds == null) {
            return false;
        }
        for (World world : worlds) {
            String status = pauseCoordinator != null && pauseCoordinator.isPaused(world) ? " &7[paused]" : "";
            UtilitySupport.send(plugin, context.sender(), Messages.TIME_QUERY,
                    "{world}", world.getName(),
                    "{time}", WorldTimeParser.formatTime(world.getTime()),
                    "{status}", status);
        }
        return !worlds.isEmpty();
    }

    /** Deferred sign entry point: reports whether global time change actually succeeded. */
    public void executeForSign(BukkitCommandContext context, Consumer<Boolean> complete) {
        String name = spec.canonicalName();
        if ("day".equals(name) || "night".equals(name)) {
            if (context.args().length > 1) {
                context.sendInvalidArgs(spec.selfUsage());
                complete.accept(false);
                return;
            }
            handleSetDeferred(context, "day".equals(name) ? DAY_TICKS : NIGHT_TICKS,
                    context.args().length == 1 ? context.args()[0] : null, complete);
            return;
        }
        var parsedOpt = WorldTimeParser.parseTimeCommand(context.args());
        if (parsedOpt.isEmpty()) {
            context.sendInvalidArgs(spec.selfUsage());
            complete.accept(false);
            return;
        }
        var parsed = parsedOpt.get();
        if (parsed.action() == WorldTimeParser.Action.SET) {
            if (!checkPerm(context, "allium.time.set", "time set")) {
                complete.accept(false);
                return;
            }
            handleSetDeferred(context, parsed.ticks(), parsed.worldSelector(), complete);
            return;
        }
        complete.accept(executeWithResult(context));
    }

    private boolean handleSet(BukkitCommandContext context, long ticks, String worldSelector) {
        boolean[] changed = {true};
        handleSetDeferred(context, ticks, worldSelector, result -> changed[0] = result);
        return changed[0];
    }

    private void handleSetDeferred(BukkitCommandContext context, long ticks, String worldSelector, Consumer<Boolean> complete) {
        List<World> worlds = UtilitySupport.resolveWorlds(plugin, context.sender(), worldSelector);
        if (worlds == null || worlds.isEmpty()) {
            complete.accept(false);
            return;
        }
        UtilitySupport.onGlobal(plugin, () -> {
            boolean anyChanged = false;
            try {
                for (World world : worlds) {
                    world.setTime(ticks);
                    if (world.getTime() != Math.floorMod(ticks, 24000L)) continue;
                    anyChanged = true;
                    if (pauseCoordinator != null) {
                        pauseCoordinator.updatePinnedTime(world, ticks);
                    }
                    UtilitySupport.send(plugin, context.sender(), Messages.TIME_SET,
                            "{time}", WorldTimeParser.formatTime(ticks),
                            "{world}", world.getName());
                    UtilitySupport.audit(plugin, context.sender(), "time_set",
                            "world=" + world.getName() + " ticks=" + ticks);
                }
            } finally {
                complete.accept(anyChanged);
            }
        });
    }

    private boolean handleAdd(BukkitCommandContext context, long delta, String worldSelector) {
        List<World> worlds = UtilitySupport.resolveWorlds(plugin, context.sender(), worldSelector);
        if (worlds == null || worlds.isEmpty()) {
            return false;
        }
        UtilitySupport.onGlobal(plugin, () -> {
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
        });
        return true;
    }

    private boolean handlePause(BukkitCommandContext context, Boolean desired, String worldSelector) {
        List<World> worlds = UtilitySupport.resolveWorlds(plugin, context.sender(), worldSelector);
        if (worlds == null || worlds.isEmpty() || pauseCoordinator == null) {
            return false;
        }
        UtilitySupport.onGlobal(plugin, () -> {
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
        });
        return true;
    }

    private boolean checkPerm(BukkitCommandContext context, String perm, String label) {
        if (context.sender().hasPermission(perm)) {
            return true;
        }
        UtilitySupport.send(plugin, context.sender(), Messages.NO_PERMISSION, "{cmd}", label);
        return false;
    }
}
