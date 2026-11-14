package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.info.AfkCommand;
import codes.castled.allium.command.info.ListCommand;
import codes.castled.allium.command.info.NearCommand;
import codes.castled.allium.command.info.SeenCommand;
import codes.castled.allium.command.info.WhoisCommand;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.info.PlayerInfoRepository;
import codes.castled.allium.info.PlayerInfoService;
import codes.castled.allium.naming.PlayerNameRepository;
import codes.castled.allium.storage.SqlDialect;

import java.sql.SQLException;
import java.util.concurrent.Executor;

/**
 * Player info utilities module (S18): {@code /list}, {@code /seen}, {@code /whois}, {@code /near},
 * and {@code /afk} built against real vanish and backed by bounded SQL storage.
 */
public final class PlayerInfoModule extends AbstractModule {

    private PlayerInfoRepository repository;
    private PlayerNameRepository nameRepository;
    private PlayerInfoService info;

    public PlayerInfoModule(PluginStart plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "info";
    }

    @Override
    public void onEnable() {
        SqlDialect dialect = plugin.getDatabaseManager().dialect();
        repository = new PlayerInfoRepository(
                plugin.getDatabaseManager().connectionSource(),
                dialect,
                System::currentTimeMillis
        );
        nameRepository = new PlayerNameRepository(
                plugin.getDatabaseManager().connectionSource(),
                dialect,
                System::currentTimeMillis
        );
        // Single serialized worker guarantees causal FIFO ordering between join, vanish, lookup, and quit.
        Executor storagePool = plugin.getScheduler().boundedPool("Allium-PlayerInfo", 1, 512);
        info = new PlayerInfoService(plugin, repository, nameRepository, storagePool, System::currentTimeMillis);
        try {
            info.enable();
        } catch (SQLException e) {
            throw new IllegalStateException("player info storage is unavailable: " + e.getMessage(), e);
        }

        plugin.getCommandManager().register(new ListCommand(plugin, info));
        plugin.getCommandManager().register(new SeenCommand(plugin, info));
        plugin.getCommandManager().register(new WhoisCommand(plugin, info));
        plugin.getCommandManager().register(new NearCommand(plugin, info));
        plugin.getCommandManager().register(new AfkCommand(plugin, info));
        plugin.logger().info("Player info utilities enabled: /list, /seen, /whois, /near, /afk.");
    }

    @Override
    public void onDisable() {
        if (info != null) {
            info.disable();
        }
    }

    public PlayerInfoService info() {
        return info;
    }

    public PlayerInfoRepository repository() {
        return repository;
    }
}
