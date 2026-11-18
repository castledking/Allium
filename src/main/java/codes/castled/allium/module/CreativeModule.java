package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.creative.CreativeManager;

/**
 * Creative manager: per-action creative restrictions, separate creative and survival inventories,
 * gamemode restore on join and gamemode audits. Configured under {@code creative-manager}.
 */
public final class CreativeModule extends AbstractModule {

    private CreativeManager manager;

    public CreativeModule(PluginStart plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "creative";
    }

    @Override
    public void onEnable() {
        manager = new CreativeManager(plugin);
        manager.enable();
        plugin.logger().info("Creative manager enabled (separate inventories: "
                + plugin.getConfig().getBoolean("creative-manager.separate-inventories", true) + ").");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.disable();
            manager = null;
        }
    }

    /** Re-reads {@code creative-manager}; the listener and stored inventories are kept. */
    public void reloadSettings() {
        if (manager != null) {
            manager.reload();
        }
    }
}
