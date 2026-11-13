package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.mail.MailCommand;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.mail.MailLimits;
import codes.castled.allium.mail.MailRepository;
import codes.castled.allium.mail.MailService;
import codes.castled.allium.messaging.IgnoreRepository;
import codes.castled.allium.messaging.NotificationRouter;
import org.bukkit.configuration.ConfigurationSection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Mail (S12): {@code /mail} text mail and gifts, to online and offline players, on SQLite or
 * MySQL. See docs/mail.md.
 */
public final class MailModule extends AbstractModule {

    private MailService mail;

    public MailModule(PluginStart plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "mail";
    }

    @Override
    public void onEnable() {
        if (plugin.getPlayerNames() == null) {
            throw new IllegalStateException("mail needs the player name index, which is unavailable");
        }
        MailRepository repository = new MailRepository(plugin.getDatabaseManager().connectionSource(), plugin.getDatabaseManager().dialect(),
                System::currentTimeMillis);
        try {
            repository.migrate();
        } catch (SQLException e) {
            throw new IllegalStateException("mail storage is unavailable: " + e.getMessage(), e);
        }
        IgnoreRepository ignores = new IgnoreRepository(plugin.getDatabaseManager().connectionSource(), plugin.getDatabaseManager().dialect(),
                System::currentTimeMillis);
        try {
            ignores.migrate();
        } catch (SQLException e) {
            throw new IllegalStateException("mail ignore storage is unavailable", e);
        }
        String serverId = serverId();
        var notifications = plugin.getModuleManager().getModule("messaging") instanceof MessagingModule m && m.isEnabled()
                ? m.messaging().notifications() : new NotificationRouter(plugin);
        mail = new MailService(plugin, repository, ignores, plugin.getPlayerNames(), notifications,
                plugin.getScheduler().boundedPool("Allium-Mail", plugin.getDatabaseManager().dialect()
                        == codes.castled.allium.storage.SqlDialect.SQLITE ? 1 : 2, 512), serverId, this::limits);
        mail.enable();
        plugin.getCommandManager().register(new MailCommand(plugin, mail));
        MailLimits limits = limits();
        plugin.logger().info("Mail enabled on " + plugin.getDatabaseManager().getType() + " (server " + serverId + "): mailbox "
                + limits.mailboxSize() + ", " + limits.sendsPerWindow() + " sends per " + limits.windowMillis() / 1000L + "s, text expires "
                + (limits.textExpiryMillis() == 0 ? "never" : "after " + limits.textExpiryMillis() / 86_400_000L + " days")
                + ", gifts up to " + limits.giftMaxItems() + " items.");
    }

    @Override
    public void onDisable() {
        if (mail != null) {
            mail.disable();
        }
    }

    public MailService mail() {
        return mail;
    }

    /** Read at the start of each send, so a reload applies to the next send and never to one in progress. */
    private MailLimits limits() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("mail");
        if (c == null) {
            return MailLimits.DEFAULTS;
        }
        try {
            return new MailLimits(
                    c.getInt("mailbox-size", MailLimits.DEFAULTS.mailboxSize()),
                    c.getInt("rate-limit.sends", MailLimits.DEFAULTS.sendsPerWindow()),
                    c.getLong("rate-limit.per-seconds", MailLimits.DEFAULTS.windowMillis() / 1000L) * 1000L,
                    c.getInt("body-max-length", MailLimits.DEFAULTS.bodyMaxLength()),
                    c.getLong("text-expiry-days", 30L) * 86_400_000L,
                    c.getInt("gift.max-items", MailLimits.DEFAULTS.giftMaxItems()));
        } catch (IllegalArgumentException e) {
            plugin.logger().warning("Invalid mail setting (" + e.getMessage() + "); using the defaults.");
            return MailLimits.DEFAULTS;
        }
    }

    /** This server's ID for mail recovery, kept in plugins/Allium/.server-id. Servers sharing MySQL each have their own. */
    private String serverId() {
        Path file = plugin.getDataFolder().toPath().resolve(".server-id");
        try {
            if (Files.isRegularFile(file)) {
                String id = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (id.matches("[A-Za-z0-9_.:-]{1,64}")) {
                    return id;
                }
                throw new IOException("invalid existing server ID; restore it rather than changing mail recovery ownership");
            }
            String id = "server-" + UUID.randomUUID();
            Files.createDirectories(file.getParent());
            Files.writeString(file, id + "\n", StandardCharsets.UTF_8);
            return id;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read or create " + file + ": " + e.getMessage(), e);
        }
    }
}
