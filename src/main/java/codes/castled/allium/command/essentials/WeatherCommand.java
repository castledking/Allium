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
    public boolean executeWithResult(BukkitCommandContext context) {
        var parsedOpt = WeatherState.parse(context.args());
        if (parsedOpt.isEmpty()) {
            context.sendInvalidArgs(spec().selfUsage());
            return false;
        }
        var parsed = parsedOpt.get();
        List<World> worlds = UtilitySupport.resolveWorlds(plugin, context.sender(), parsed.worldSelector());
        if (worlds == null) {
            return false;
        }
        var condition = parsed.condition();
        Integer duration = parsed.durationSeconds();
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
        return !worlds.isEmpty() && worlds.stream().allMatch(world ->
                world.hasStorm() == condition.storm() && world.isThundering() == condition.thunder());
    }
}
