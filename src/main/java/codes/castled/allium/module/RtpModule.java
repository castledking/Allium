package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.teleport.RtpCommand;
import codes.castled.allium.command.teleport.SetTprCommand;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.rtp.RtpService;
import codes.castled.allium.teleport.TeleportService;

/** Random teleport: {@code /rtp} ({@code /tpr}), {@code /settpr} and {@code rtp.yml}. Needs the teleport module. */
public final class RtpModule extends AbstractModule {

    private RtpService rtp;

    public RtpModule(PluginStart plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "rtp";
    }

    @Override
    public void onEnable() {
        if (!(plugin.getModuleManager().getModule("teleport") instanceof TeleportModule teleport) || !teleport.isEnabled()) {
            throw new IllegalStateException("random teleport needs the teleport module");
        }
        rtp = new RtpService(plugin, this::teleports);
        try {
            rtp.enable();
        } catch (Exception invalid) {
            rtp = null;
            throw new IllegalStateException("rtp.yml could not be read", invalid);
        }
        plugin.getCommandManager().register(new RtpCommand(plugin, rtp));
        plugin.getCommandManager().register(new SetTprCommand(rtp,
                () -> plugin.getServer().getWorlds().stream().map(org.bukkit.World::getName).toList()));
        plugin.logger().info("Random teleport enabled: " + rtp.settings().locations().size() + " named location(s), default '"
                + rtp.settings().defaultLocation() + "', " + rtp.settings().findAttempts() + " tries per search, "
                + (rtp.settings().firstJoin() ? "first-join RTP on." : "first-join RTP off."));
    }

    @Override
    public void onDisable() {
        if (rtp != null) {
            rtp.disable();
            rtp = null;
        }
    }

    private TeleportService teleports() {
        return plugin.getModuleManager().getModule("teleport") instanceof TeleportModule teleport && teleport.isEnabled()
                ? teleport.teleports() : null;
    }

    public RtpService rtp() {
        return rtp;
    }

    /** Prepares and validates {@code rtp.yml} without mutating live settings. */
    public codes.castled.allium.config.ReloadCoordinator.Prepared prepareReload()
            throws java.io.IOException, org.bukkit.configuration.InvalidConfigurationException {
        return rtp == null ? codes.castled.allium.config.ReloadCoordinator.Prepared.of(() -> {}) : rtp.prepareReload();
    }

    /** Re-reads {@code rtp.yml}; false keeps the previous settings. */
    public boolean reloadSettings() {
        return rtp == null || rtp.reload();
    }
}
