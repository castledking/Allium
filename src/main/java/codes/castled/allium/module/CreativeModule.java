package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.command.creative.CreativeManageCommand;
import codes.castled.allium.creative.CreativeManager;
import codes.castled.allium.creative.manage.CreativeAccess;
import codes.castled.allium.creative.manage.CreativeManageMenus;

/**
 * Creative manager: per-action creative restrictions, separate creative and survival inventories,
 * gamemode restore on join and gamemode audits. Configured under {@code creative-manager}.
 */
public final class CreativeModule extends AbstractModule {

    private CreativeManager manager;
    private CreativeManageMenus menus;
    private CreativeManageCommand manageCommand;

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
        CreativeAccess access = new CreativeAccess(plugin);
        menus = new CreativeManageMenus(plugin, access, () -> plugin.getModuleManager() != null
                && plugin.getModuleManager().getModule("items") instanceof ItemsModule items && items.isEnabled()
                ? items.skulls() : null);
        menus.enable();
        manageCommand = new CreativeManageCommand(plugin, access, menus);
        plugin.getCommandManager().register(manageCommand);
        plugin.logger().info("Creative manager enabled (separate inventories: "
                + plugin.getConfig().getBoolean("creative-manager.separate-inventories", true) + ").");
    }

    @Override
    public void onDisable() {
        if (menus != null) {
            menus.disable();
            menus = null;
        }
        manageCommand = null;
        if (manager != null) {
            manager.disable();
            manager = null;
        }
    }

    /** {@code /creativemanager}, also reached as {@code /allium creativemanager}; null while disabled. */
    public CreativeManageCommand manageCommand() {
        return manageCommand;
    }

    /** Re-reads {@code creative-manager}; the listener and stored inventories are kept. */
    public void reloadSettings() {
        if (manager != null) {
            manager.reload();
        }
    }
}
