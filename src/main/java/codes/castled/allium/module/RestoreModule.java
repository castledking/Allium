package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.restore.RestoreCommand;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.restore.RestoreMenus;
import codes.castled.allium.restore.RestoreService;
import codes.castled.allium.restore.SnapshotRepository;

import java.sql.SQLException;

/** Inventory snapshots and {@code /restore} (configured under {@code restore}). */
public final class RestoreModule extends AbstractModule {

    private RestoreService restore;
    private RestoreMenus menus;

    public RestoreModule(PluginStart plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "restore";
    }

    @Override
    public void onEnable() {
        var db = plugin.getDatabaseManager();
        SnapshotRepository repository = new SnapshotRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        try {
            repository.migrate();
        } catch (SQLException e) {
            throw new IllegalStateException("inventory snapshot storage is unavailable: " + e.getMessage(), e);
        }
        restore = new RestoreService(plugin, repository, plugin.getScheduler().boundedPool("Allium-Restore", 1, 1024));
        restore.enable();
        menus = new RestoreMenus(plugin, restore, () -> plugin.getModuleManager() != null
                && plugin.getModuleManager().getModule("items") instanceof ItemsModule items && items.isEnabled()
                ? items.skulls() : null);
        menus.enable();
        plugin.getCommandManager().register(new RestoreCommand(plugin, menus));
        plugin.logger().info("Inventory snapshots and /restore enabled.");
    }

    @Override
    public void onDisable() {
        if (menus != null) menus.disable();
        if (restore != null) restore.disable();
        menus = null;
        restore = null;
    }

    public void reloadSettings() {
        if (restore != null) restore.reload();
    }

    public RestoreService restore() {
        return restore;
    }

    @Override
    public int reloadOrder() {
        return 110;
    }

    @Override
    public codes.castled.allium.config.ReloadCoordinator.Prepared prepareReload(org.bukkit.configuration.file.FileConfiguration candidateRoot) {
        return codes.castled.allium.config.ReloadCoordinator.Prepared.of(this::reloadSettings);
    }
}
