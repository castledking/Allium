package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.BurnCommand;
import codes.castled.allium.command.essentials.ClearInventoryCommand;
import codes.castled.allium.command.essentials.EnchantCommand;
import codes.castled.allium.command.essentials.ExtCommand;
import codes.castled.allium.command.essentials.FeedCommand;
import codes.castled.allium.command.essentials.FlyCommand;
import codes.castled.allium.command.essentials.GamemodeCommand;
import codes.castled.allium.command.essentials.GcCommand;
import codes.castled.allium.command.essentials.GodCommand;
import codes.castled.allium.command.essentials.HatCommand;
import codes.castled.allium.command.essentials.HealCommand;
import codes.castled.allium.command.essentials.ItemDbCommand;
import codes.castled.allium.command.essentials.MoreCommand;
import codes.castled.allium.command.essentials.NvCommand;
import codes.castled.allium.command.essentials.PingCommand;
import codes.castled.allium.command.essentials.SkullCommand;
import codes.castled.allium.command.essentials.SmiteCommand;
import codes.castled.allium.command.essentials.SpeedCommand;
import codes.castled.allium.command.essentials.ScaleCommand;
import codes.castled.allium.command.essentials.TimeCommand;
import codes.castled.allium.command.essentials.TimePauseCoordinator;
import codes.castled.allium.command.essentials.UtilityCommand;
import codes.castled.allium.command.essentials.WeatherCommand;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.item.library.SkullProfiles;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;

public final class EssentialsModule extends AbstractModule {

    private codes.castled.allium.player.FlightRestoration flights;
    public codes.castled.allium.player.FlightRestoration flights(){return flights;}
    private TimePauseCoordinator timePauseCoordinator;
    private UtilityCommand.DisposalListener disposalListener;
    private codes.castled.allium.command.essentials.LoreClipboard loreClipboard;
    private codes.castled.allium.command.essentials.MotdOnJoin motdOnJoin;
    private codes.castled.allium.player.TimedFlight timedFlight;

    /** Timed flight (legacy tfly); null when its storage could not start. */
    public codes.castled.allium.player.TimedFlight timedFlight() {
        return timedFlight;
    }

    private codes.castled.allium.player.TimedFlight startTimedFlight() {
        if (plugin.getDatabaseManager() == null) {
            return null;
        }
        try {
            var repository = new codes.castled.allium.flight.FlightTimeRepository(plugin.getDatabaseManager().connectionSource(),
                    plugin.getDatabaseManager().dialect(), System::currentTimeMillis);
            repository.migrate();
            var service = new codes.castled.allium.player.TimedFlight(plugin, repository,
                    plugin.getScheduler().boundedPool("Allium-Flight", 1, 1024), flights);
            service.enable();
            return service;
        } catch (java.sql.SQLException | RuntimeException failed) {
            plugin.logger().warning("Timed flight is unavailable: its storage could not start (" + failed.getMessage() + ").");
            return null;
        }
    }
    private codes.castled.allium.broadcast.BroadcastService broadcasts;

    /** Re-reads the timed broadcast and timed flight settings ({@code /allium reload}). */
    public void reloadSettings() {
        if (broadcasts != null) {
            broadcasts.reload();
        }
        if (timedFlight != null) {
            timedFlight.reload();
        }
    }
    private SkullProfiles fallbackSkulls;

    public EssentialsModule(PluginStart plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "essentials";
    }

    @Override
    public void onEnable() {
        flights=new codes.castled.allium.player.FlightRestoration(plugin);flights.enable();
        timedFlight=startTimedFlight();
        plugin.getCommandManager().register(new codes.castled.allium.command.essentials.ToggleSlowFallCommand(flights));
        timePauseCoordinator = new TimePauseCoordinator(plugin);
        disposalListener = new UtilityCommand.DisposalListener();
        if (plugin.getServer() != null && plugin.getServer().getPluginManager() != null) {
            plugin.getServer().getPluginManager().registerEvents(disposalListener, plugin);
        }

        for (var spec : CoreCommandSpecs.UTILITY_SPECS) {
            plugin.getCommandManager().register(new UtilityCommand(plugin, spec));
        }
        plugin.getCommandManager().register(new HealCommand(plugin));
        plugin.getCommandManager().register(new FeedCommand(plugin));
        plugin.getCommandManager().register(new FlyCommand(plugin, () -> timedFlight));
        plugin.getCommandManager().register(new GodCommand(plugin));
        plugin.getCommandManager().register(new GamemodeCommand(plugin));

        plugin.getCommandManager().register(new SpeedCommand(plugin));
        plugin.getCommandManager().register(new ScaleCommand(plugin));
        plugin.getCommandManager().register(new PingCommand(plugin));
        plugin.getCommandManager().register(new ExtCommand(plugin));
        plugin.getCommandManager().register(new BurnCommand(plugin));
        plugin.getCommandManager().register(new SmiteCommand(plugin));
        plugin.getCommandManager().register(new NvCommand(plugin));
        var clearInventory = new ClearInventoryCommand(plugin);
        plugin.getCommandManager().register(clearInventory);
        plugin.getCommandManager().register(clearInventory.confirmationToggle());
        plugin.getCommandManager().register(new MoreCommand(plugin));
        plugin.getCommandManager().register(new HatCommand(plugin));
        plugin.getCommandManager().register(new SkullCommand(plugin, this::resolveSkullProfiles));
        plugin.getCommandManager().register(new ItemDbCommand(plugin));
        plugin.getCommandManager().register(new GcCommand(plugin, timePauseCoordinator));
        plugin.getCommandManager().register(new TimeCommand(plugin, CoreCommandSpecs.TIME, timePauseCoordinator));
        plugin.getCommandManager().register(new TimeCommand(plugin, CoreCommandSpecs.DAY, timePauseCoordinator));
        plugin.getCommandManager().register(new TimeCommand(plugin, CoreCommandSpecs.NIGHT, timePauseCoordinator));
        plugin.getCommandManager().register(new WeatherCommand(plugin));
        plugin.getCommandManager().register(new EnchantCommand(plugin));
        plugin.getCommandManager().register(new codes.castled.allium.command.essentials.ItemTextCommand(plugin, CoreCommandSpecs.RENAME));
        loreClipboard = new codes.castled.allium.command.essentials.LoreClipboard();
        if (plugin.getServer() != null && plugin.getServer().getPluginManager() != null) {
            plugin.getServer().getPluginManager().registerEvents(loreClipboard, plugin);
        }
        plugin.getCommandManager().register(new codes.castled.allium.command.essentials.ItemTextCommand(plugin, CoreCommandSpecs.LORE, loreClipboard));
        plugin.getCommandManager().register(new codes.castled.allium.command.essentials.XpCommand(plugin));
        var motd = codes.castled.allium.command.essentials.TextFileCommand.motd(plugin);
        for (var textFile : java.util.List.of(codes.castled.allium.command.essentials.TextFileCommand.customText(plugin), motd,
                codes.castled.allium.command.essentials.TextFileCommand.rules(plugin))) {
            textFile.installDefault();
            plugin.getCommandManager().register(textFile);
        }
        broadcasts = new codes.castled.allium.broadcast.BroadcastService(plugin);
        broadcasts.start();
        plugin.getCommandManager().register(new codes.castled.allium.command.essentials.BroadcastCommand(plugin, CoreCommandSpecs.BROADCAST, broadcasts));
        plugin.getCommandManager().register(new codes.castled.allium.command.essentials.BroadcastCommand(plugin, CoreCommandSpecs.BROADCASTS, broadcasts));
        motdOnJoin = new codes.castled.allium.command.essentials.MotdOnJoin(plugin, motd);
        if (plugin.getServer() != null && plugin.getServer().getPluginManager() != null) {
            plugin.getServer().getPluginManager().registerEvents(motdOnJoin, plugin);
        }
    }

    private SkullProfiles resolveSkullProfiles() {
        if (plugin.getModuleManager() != null
                && plugin.getModuleManager().getModule("items") instanceof ItemsModule items
                && items.isEnabled()
                && items.skulls() != null) {
            return items.skulls();
        }
        if (fallbackSkulls == null) {
            fallbackSkulls = new SkullProfiles(
                    Bukkit::getPlayerExact,
                    Bukkit::createPlayerProfile,
                    name -> Bukkit.createPlayerProfile(name).update(),
                    3000L,
                    3_600_000L,
                    256,
                    System::currentTimeMillis,
                    30,
                    profile -> profile.getTextures() != null && profile.getTextures().getSkin() != null,
                    plugin.getScheduler().boundedPool("Allium-EssentialsSkull", SkullProfiles.POOL_THREADS, SkullProfiles.POOL_QUEUE)
            );
        }
        return fallbackSkulls;
    }

    public TimePauseCoordinator timePauseCoordinator() {
        return timePauseCoordinator;
    }

    @Override
    public void onDisable() {
        // Timed flight hands landing fliers to the flight restoration, so it stops first.
        if(timedFlight!=null){timedFlight.close();timedFlight=null;}
        if(flights!=null){flights.close();flights=null;}
        if (disposalListener != null) {
            HandlerList.unregisterAll(disposalListener);
            disposalListener = null;
        }
        if (loreClipboard != null) {
            HandlerList.unregisterAll(loreClipboard);
            loreClipboard = null;
        }
        if (motdOnJoin != null) {
            HandlerList.unregisterAll(motdOnJoin);
            motdOnJoin = null;
        }
        if (broadcasts != null) {
            broadcasts.stop();
            broadcasts = null;
        }
        if (timePauseCoordinator != null) {
            timePauseCoordinator.close();
            timePauseCoordinator = null;
        }
        if (fallbackSkulls != null) {
            fallbackSkulls.close();
            fallbackSkulls = null;
        }
    }
}
