package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.importer.LegacyTidy;
import codes.castled.allium.modguard.ModGuard;
import codes.castled.allium.modguard.ModGuardFiles;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;

/** ModGuard, legacy Allium's modified-client detection; settings in {@code modguard.yml}. */
public final class ModGuardModule extends AbstractModule {
    private ModGuard modGuard;

    public ModGuardModule(PluginStart plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "modguard";
    }

    @Override
    public void onEnable() {
        try {
            var files = new ModGuardFiles(plugin.getDataFolder().toPath(), plugin::getResource, plugin.logger()::info,
                    relative -> LegacyTidy.source(plugin.getDataFolder().toPath(), relative));
            modGuard = new ModGuard(plugin, plugin.getScheduler(), files.load(), files.bundled());
        } catch (IOException | InvalidConfigurationException unreadable) {
            throw new IllegalStateException("modguard.yml could not be read: " + unreadable.getMessage(), unreadable);
        }
        modGuard.start();
    }

    @Override
    public void onDisable() {
        if (modGuard != null) modGuard.close();
        modGuard = null;
    }

    /** Re-reads modguard.yml. False while the module is off. */
    public boolean reloadSettings() throws IOException, InvalidConfigurationException {
        if (modGuard == null) return false;
        var files = new ModGuardFiles(plugin.getDataFolder().toPath(), plugin::getResource, plugin.logger()::info,
                relative -> LegacyTidy.source(plugin.getDataFolder().toPath(), relative));
        modGuard.reload(files.load());
        return true;
    }

    public ModGuard modGuard() {
        return modGuard;
    }
}
