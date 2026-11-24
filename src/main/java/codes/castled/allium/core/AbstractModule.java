package codes.castled.allium.core;

import codes.castled.allium.PluginStart;
import codes.castled.allium.config.ReloadCoordinator;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;

public abstract class AbstractModule {

    protected final PluginStart plugin;
    private boolean enabled;

    protected AbstractModule(PluginStart plugin) {
        this.plugin = plugin;
    }

    public abstract String getName();

    public abstract void onEnable();

    public abstract void onDisable();

    public void enable() {
        if (enabled) return;
        onEnable();
        enabled = true;
        plugin.logger().info("Module [" + getName() + "] enabled.");
    }

    public void disable() {
        if (!enabled) return;
        onDisable();
        enabled = false;
        plugin.logger().info("Module [" + getName() + "] disabled.");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void reload() {
        if (enabled) {
            disable();
            enable();
        }
    }

    /** Relative reload priority (lower publishes earlier). */
    public int reloadOrder() {
        return 100;
    }

    /**
     * Prepares and validates a reload candidate against {@code candidateRoot} without mutating
     * live module state. Return {@code null} if this module has no reloadable settings.
     */
    @Nullable
    public ReloadCoordinator.Prepared prepareReload(FileConfiguration candidateRoot) throws Exception {
        return null;
    }
}
