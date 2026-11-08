package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.messaging.IgnoreCommands;
import codes.castled.allium.command.messaging.MsgCommand;
import codes.castled.allium.command.messaging.MsgToggleCommand;
import codes.castled.allium.command.messaging.ReplyCommand;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.messaging.IgnoreRepository;
import codes.castled.allium.messaging.IgnoreService;
import codes.castled.allium.messaging.MessagingService;

import java.sql.SQLException;

/**
 * Private messages (S07): {@code /msg}, {@code /reply}, {@code /ignore}, {@code /unignore} and
 * {@code /msgtoggle}. Acceptance comes from the preferences module and fails closed while a
 * player's preferences are not loaded; without the preferences module everyone accepts.
 */
public final class MessagingModule extends AbstractModule {

    private IgnoreService ignores;
    private MessagingService messaging;

    public MessagingModule(PluginStart plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "messaging";
    }

    @Override
    public void onEnable() {
        IgnoreRepository repository = new IgnoreRepository(plugin.getDatabaseManager().connectionSource(), plugin.getDatabaseManager().dialect(),
                System::currentTimeMillis);
        try {
            repository.migrate();
        } catch (SQLException e) {
            throw new IllegalStateException("ignore list storage is unavailable: " + e.getMessage(), e);
        }
        ignores = new IgnoreService(plugin, repository, plugin.getScheduler().boundedPool("Allium-Ignores", 1, 256));
        ignores.enable();
        messaging = new MessagingService(plugin, ignores);
        messaging.enable();
        plugin.getCommandManager().register(new MsgCommand(plugin, messaging));
        plugin.getCommandManager().register(new ReplyCommand(messaging));
        plugin.getCommandManager().register(new IgnoreCommands.Ignore(plugin, messaging, ignores));
        plugin.getCommandManager().register(new IgnoreCommands.Unignore(plugin, ignores));
        plugin.getCommandManager().register(new MsgToggleCommand(plugin, messaging));
        plugin.logger().info("Private messaging enabled: /msg, /reply, /ignore, /unignore, /msgtoggle"
                + (messagingPreferences() ? "." : "; the preferences module is off, so nobody can turn messages off."));
    }

    private boolean messagingPreferences() {
        return plugin.getModuleManager().getModule("preferences") instanceof PreferencesModule p && p.isEnabled();
    }

    @Override
    public void onDisable() {
        if (messaging != null) {
            messaging.disable();
        }
        if (ignores != null) {
            ignores.disable();
        }
    }

    public MessagingService messaging() {
        return messaging;
    }

    public IgnoreService ignores() {
        return ignores;
    }
}
