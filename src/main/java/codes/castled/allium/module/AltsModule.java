package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.alts.AltRepository;
import codes.castled.allium.alts.AltService;
import codes.castled.allium.command.alts.AltExemptCommand;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.naming.PlayerNameRepository;

import java.sql.SQLException;

/** Alt detection: IP history, the alt parent group, {@code /altexempt}, and alt lists in /seen and /whois. */
public final class AltsModule extends AbstractModule {

    private AltService alts;

    public AltsModule(PluginStart plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "alts";
    }

    @Override
    public void onEnable() {
        var db = plugin.getDatabaseManager();
        AltRepository repository = new AltRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        PlayerNameRepository names = new PlayerNameRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        alts = new AltService(plugin, repository, names, plugin.getScheduler().boundedPool("Allium-Alts", 1, 256));
        try {
            alts.enable();
        } catch (SQLException e) {
            alts = null;
            throw new IllegalStateException("alt storage is unavailable: " + e.getMessage(), e);
        }
        plugin.getCommandManager().register(new AltExemptCommand(plugin, alts));
        plugin.logger().info("Alt detection enabled (parent group: " + alts.settings().group() + ").");
    }

    @Override
    public void onDisable() {
        if (alts != null) {
            alts.disable();
            alts = null;
        }
    }

    /** The alt service, or null while the module is disabled. */
    public AltService alts() {
        return alts;
    }
}
