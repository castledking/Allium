package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.database.ServerIdentity;
import codes.castled.allium.command.kits.KitCommand;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.kit.KitDefinitionLoader;
import codes.castled.allium.kit.KitRepository;
import codes.castled.allium.kit.KitService;
import codes.castled.allium.storage.SqlDialect;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Kits (S16): {@code /kit}, {@code /kits}, {@code /createkit} and {@code /delkit}, backed by
 * {@code plugins/Allium/kits/*.yml} definitions and durable SQLite/MySQL delivery records.
 */
public final class KitsModule extends AbstractModule {

    private KitService kits;

    public KitsModule(PluginStart plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "kits";
    }

    @Override
    public void onEnable() {
        if (!(plugin.getModuleManager().getModule("items") instanceof ItemsModule items) || !items.isEnabled()) {
            throw new IllegalStateException("kits requires the items module to be enabled");
        }
        KitRepository repository = new KitRepository(
                plugin.getDatabaseManager().connectionSource(),
                plugin.getDatabaseManager().dialect(),
                System::currentTimeMillis
        );
        try {
            repository.migrate();
        } catch (SQLException e) {
            throw new IllegalStateException("kit storage is unavailable: " + e.getMessage(), e);
        }
        String serverId = serverId();
        KitDefinitionLoader loader = new KitDefinitionLoader(
                plugin.getDataFolder(),
                plugin::getResource,
                plugin.logger()::info,
                plugin.logger()::warning
        );
        kits = new KitService(
                plugin,
                repository,
                loader,
                items::parser,
                items::library,
                items::delivery,
                plugin.getScheduler().boundedPool("Allium-Kits",
                        plugin.getDatabaseManager().dialect() == SqlDialect.SQLITE ? 1 : 2, 512),
                serverId
        );
        kits.enable();
        for (CommandSpec spec : CoreCommandSpecs.KIT_SPECS) {
            plugin.getCommandManager().register(new KitCommand(kits, spec));
        }
        plugin.logger().info("Kits enabled on " + plugin.getDatabaseManager().getType()
                + " (server " + serverId + "): " + kits.definitions().size() + " active kit(s).");
    }

    @Override
    public void onDisable() {
        if (kits != null) {
            kits.disable();
            kits = null;
        }
    }

    public KitService kits() {
        return kits;
    }

    @Override
    public int reloadOrder() {
        return 65;
    }

    @Override
    public codes.castled.allium.config.ReloadCoordinator.Prepared prepareReload(org.bukkit.configuration.file.FileConfiguration candidateRoot) {
        return kits == null ? null : codes.castled.allium.config.ReloadCoordinator.Prepared.of(kits::reloadDefinitions);
    }

    private String serverId() {
        try {
            return ServerIdentity.load(plugin.getDataFolder().toPath());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the Allium server identity", e);
        }
    }
}
