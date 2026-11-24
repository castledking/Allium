package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.essentials.WeatherState;
import codes.castled.allium.message.Messages;
import org.bukkit.World;

import java.util.List;
import java.util.function.Consumer;

public final class WeatherCommand implements SpecCommandHandler.Immediate {

    private final PluginStart plugin;

    public WeatherCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.WEATHER;
    }

    @Override
    public AliasTransform transformExecutionArgs(String label, String[] args) {
        String fixedWeather = weatherAlias(label);
        return fixedWeather == null ? new AliasTransform.Pass(args)
                : new AliasTransform.Pass(SpecCommandHandler.prepend(fixedWeather, args));
    }

    static String weatherAlias(String label) {
        if (label == null) return null;
        String bare = label.substring(label.lastIndexOf(':') + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (bare) {
            case "rain", "storm" -> "storm";
            case "sun", "sky" -> "sun";
            default -> null;
        };
    }

    @Override
    public boolean executeWithResult(BukkitCommandContext context) {
        boolean[] applied = {true};
        executeForSign(context, result -> applied[0] = result);
        return applied[0];
    }

    /** Deferred sign entry point: reports whether the global weather change actually succeeded. */
    public void executeForSign(BukkitCommandContext context, Consumer<Boolean> complete) {
        var parsedOpt = WeatherState.parse(context.args());
        if (parsedOpt.isEmpty()) {
            context.sendInvalidArgs(spec().selfUsage());
            complete.accept(false);
            return;
        }
        var parsed = parsedOpt.get();
        List<World> worlds = UtilitySupport.resolveWorlds(plugin, context.sender(), parsed.worldSelector());
        if (worlds == null || worlds.isEmpty()) {
            complete.accept(false);
            return;
        }
        var condition = parsed.condition();
        Integer duration = parsed.durationSeconds();
        UtilitySupport.onGlobal(plugin, () -> {
            boolean applied = false;
            try {
                for (World world : worlds) {
                    world.setStorm(condition.storm());
                    world.setThundering(condition.thunder());
                    if (duration != null) {
                        int ticks = duration * 20;
                        world.setWeatherDuration(ticks);
                        if (condition.thunder()) {
                            world.setThunderDuration(ticks);
                        }
                    }
                    UtilitySupport.send(plugin, context.sender(), Messages.WEATHER_SET,
                            "{weather}", condition.displayName(),
                            "{world}", world.getName());
                    UtilitySupport.audit(plugin, context.sender(), "weather",
                            "world=" + world.getName() + " condition=" + condition.displayName()
                                    + (duration == null ? "" : " duration=" + duration + "s"));
                }
                applied = worlds.stream().allMatch(world ->
                        world.hasStorm() == condition.storm() && world.isThundering() == condition.thunder());
            } finally {
                complete.accept(applied);
            }
        });
    }
}
