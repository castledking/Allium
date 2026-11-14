package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.moderation.MuteCommand;
import codes.castled.allium.command.moderation.MutesCommand;
import codes.castled.allium.command.moderation.UnmuteCommand;
import codes.castled.allium.command.moderation.VanishCommand;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.moderation.ModerationRepository;
import codes.castled.allium.moderation.ModerationService;
import codes.castled.allium.storage.SqlDialect;

import java.sql.SQLException;
import java.util.concurrent.Executor;

/**
 * Moderation module (S17): {@code /vanish}, {@code /mute}, {@code /unmute}, and {@code /mutes}.
 * Replaces the stub {@code VanishSource} and {@code MuteSource} hooks with SQL-persisted vanish
 * and mute implementations on the bounded storage pattern.
 */
public final class ModerationModule extends AbstractModule {

    private ModerationRepository repository;
    private ModerationService moderation;

    public ModerationModule(PluginStart plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "moderation";
    }

    @Override
    public void onEnable() {
        SqlDialect dialect = plugin.getDatabaseManager().dialect();
        repository = new ModerationRepository(
                plugin.getDatabaseManager().connectionSource(),
                dialect,
                System::currentTimeMillis
        );
        try {
            repository.migrate();
        } catch (SQLException e) {
            throw new IllegalStateException("moderation storage is unavailable: " + e.getMessage(), e);
        }

        int workers = dialect == SqlDialect.MYSQL ? 2 : 1;
        Executor storagePool = plugin.getScheduler().boundedPool("Allium-Moderation", workers, 512);
        moderation = new ModerationService(plugin, repository, storagePool, System::currentTimeMillis);
        try {
            moderation.enable();
        } catch (SQLException e) {
            throw new IllegalStateException("moderation state could not be loaded: " + e.getMessage(), e);
        }

        if (plugin.getModuleManager().getModule("messaging") instanceof MessagingModule messagingModule
                && messagingModule.messaging() != null) {
            messagingModule.messaging().setVanishSource(moderation);
            messagingModule.messaging().setMuteSource(moderation);
        }

        plugin.getCommandManager().register(new VanishCommand(plugin, moderation));
        plugin.getCommandManager().register(new MuteCommand(plugin, moderation));
        plugin.getCommandManager().register(new UnmuteCommand(plugin, moderation));
        plugin.getCommandManager().register(new MutesCommand(plugin, moderation));
        plugin.logger().info("Moderation enabled: /vanish, /mute, /unmute, /mutes.");
    }

    @Override
    public void onDisable() {
        if (plugin.getModuleManager().getModule("messaging") instanceof MessagingModule messagingModule
                && messagingModule.messaging() != null) {
            messagingModule.messaging().setVanishSource(null);
            messagingModule.messaging().setMuteSource(null);
        }
        if (moderation != null) {
            moderation.disable();
        }
    }

    public ModerationService moderation() {
        return moderation;
    }

    public ModerationRepository repository() {
        return repository;
    }
}
