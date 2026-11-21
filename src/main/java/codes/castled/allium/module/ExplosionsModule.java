package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.explosion.AntiochCommand;
import codes.castled.allium.command.explosion.CannonCommand;
import codes.castled.allium.command.explosion.ExplodeCommand;
import codes.castled.allium.command.explosion.FireballCommand;
import codes.castled.allium.command.explosion.NukeCommand;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.explosion.ExplosionSettings;
import codes.castled.allium.explosion.RegenerationService;

import java.util.ArrayList;
import java.util.List;

/**
 * Explosion regeneration and the explosive commands: {@code /antioch}, {@code /fireball},
 * {@code /beezooka}, {@code /kittycannon}, {@code /nuke} and {@code /explode}; {@code explosions.yml}.
 */
public final class ExplosionsModule extends AbstractModule {

    private RegenerationService regeneration;

    public ExplosionsModule(PluginStart plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "explosions";
    }

    @Override
    public void onEnable() {
        regeneration = new RegenerationService(plugin);
        try {
            regeneration.enable();
        } catch (Exception invalid) {
            regeneration = null;
            throw new IllegalStateException("explosions.yml could not be read", invalid);
        }
        plugin.getCommandManager().register(new AntiochCommand(plugin, regeneration));
        plugin.getCommandManager().register(new FireballCommand(plugin, regeneration));
        plugin.getCommandManager().register(CannonCommand.beezooka(plugin, regeneration));
        plugin.getCommandManager().register(CannonCommand.kittycannon(plugin, regeneration));
        plugin.getCommandManager().register(new NukeCommand(plugin, regeneration));
        plugin.getCommandManager().register(new ExplodeCommand(plugin, regeneration));
        ExplosionSettings settings = regeneration.settings();
        List<String> sources = new ArrayList<>();
        for (ExplosionSettings.Source source : ExplosionSettings.Source.values()) {
            if (settings.regenerates(source)) sources.add(source.key());
        }
        for (ExplosionSettings.Explosive explosive : ExplosionSettings.Explosive.values()) {
            if (settings.regenerates(explosive)) sources.add("/" + explosive.key());
        }
        plugin.logger().info("Explosion regeneration enabled for " + (sources.isEmpty() ? "nothing" : String.join(", ", sources))
                + "; blocks return after " + settings.delayTicks() / 20.0D + "s at " + settings.blocksPerSecond() + " per second.");
    }

    @Override
    public void onDisable() {
        if (regeneration != null) {
            regeneration.disable();
            regeneration = null;
        }
    }

    public RegenerationService regeneration() {
        return regeneration;
    }

    /** Re-reads {@code explosions.yml}; false keeps the previous settings. */
    public boolean reloadSettings() {
        return regeneration == null || regeneration.reload();
    }
}
