package codes.castled.allium.ride;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.tool.powertool.PowerToolAction;
import codes.castled.allium.tool.powertool.PowerToolContext;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RidesAndClaimBansFoliaTest {

    private final ArrayDeque<Runnable> globalTasks = new ArrayDeque<>();
    private final ArrayDeque<Runnable> carrierTasks = new ArrayDeque<>();
    private final ArrayDeque<Runnable> riderTasks = new ArrayDeque<>();

    private PluginStart plugin;
    private Server server;
    private TaskScheduler scheduler;
    private MessageManager messages;
    private ConsoleCommandSender console;
    private World world;
    private Player carrier;
    private Player rider;
    private ServerThread carrierOwner;
    private ServerThread riderOwner;
    private YamlConfiguration config;

    @BeforeEach
    void setUp() {
        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        scheduler = mock(TaskScheduler.class);
        messages = mock(MessageManager.class);
        console = mock(ConsoleCommandSender.class);
        config = new YamlConfiguration();

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(server.getConsoleSender()).thenReturn(console);
        when(scheduler.isServerThread()).thenReturn(false);
        when(scheduler.runOnServer(any(Runnable.class))).thenAnswer(c -> {
            globalTasks.add(c.getArgument(0));
            return mock(TaskHandle.class);
        });

        world = mock(World.class);
        UUID carrierId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        carrier = mock(Player.class);
        rider = mock(Player.class);
        when(carrier.getUniqueId()).thenReturn(carrierId);
        when(carrier.getName()).thenReturn("Officer");
        when(carrier.isOnline()).thenReturn(true);
        when(carrier.isValid()).thenReturn(true);
        when(carrier.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(carrier.addPassenger(any())).thenReturn(true);

        when(rider.getUniqueId()).thenReturn(riderId);
        when(rider.getName()).thenReturn("Suspect");
        when(rider.isOnline()).thenReturn(true);
        when(rider.isValid()).thenReturn(true);
        when(rider.getPassengers()).thenReturn(List.of());
        when(rider.getLocation()).thenReturn(new Location(world, 0, 64, 0));

        when(server.getPlayer(carrierId)).thenReturn(carrier);
        when(server.getPlayer(riderId)).thenReturn(rider);

        carrierOwner = mock(ServerThread.class);
        riderOwner = mock(ServerThread.class);
        when(carrierOwner.isServerThread()).thenReturn(true);
        when(riderOwner.isServerThread()).thenReturn(true);
        when(scheduler.runOn(eq(carrierOwner), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(c -> {
            carrierTasks.add(c.getArgument(1));
            return mock(TaskHandle.class);
        });
        when(scheduler.runOn(eq(riderOwner), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(c -> {
            riderTasks.add(c.getArgument(1));
            return mock(TaskHandle.class);
        });
    }

    @Test
    void ridesLetFromForeignRegionHopsEntityCleanupAndQuitCommandsRunOnGlobal() {
        config.set("restraints.commands-on-quit", List.of("jail {player} 5m"));
        Rides rides = new Rides(plugin);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, carrier)).thenReturn(carrierOwner);
            platform.when(() -> BukkitServerThread.entity(plugin, rider)).thenReturn(riderOwner);

            assertEquals(Rides.Result.RESTRAINED, rides.carry(carrier, rider, true, 5, "allium:handcuffs"));

            // Staff in another region invokes rides.let(riderId)
            when(riderOwner.isServerThread()).thenReturn(false);
            rides.let(rider.getUniqueId());
            verify(rider, never()).setCollidable(true);
            assertEquals(1, riderTasks.size(), "let() off-region must hop entity cleanup to rider's region");

            when(riderOwner.isServerThread()).thenReturn(true);
            riderTasks.remove().run();
            verify(rider).setCollidable(true);

            // Re-restrain and test restrained quit -> console command runs on global scheduler
            assertEquals(Rides.Result.RESTRAINED, rides.carry(carrier, rider, true, 5, "allium:handcuffs"));
            rides.onQuit(new PlayerQuitEvent(rider, ""));
            assertEquals(1, globalTasks.size(), "restraints.commands-on-quit must dispatch via global scheduler");
            verify(server, never()).dispatchCommand(any(), anyString());

            globalTasks.remove().run();
            verify(server).dispatchCommand(console, "jail Suspect 5m");
        }
    }

    @Test
    void unrestrainedDismountSchedulesRestackOnCarrierRegionNotGlobal() {
        Rides rides = new Rides(plugin);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, carrier)).thenReturn(carrierOwner);
            platform.when(() -> BukkitServerThread.entity(plugin, rider)).thenReturn(riderOwner);

            assertEquals(Rides.Result.CARRIED, rides.carry(carrier, rider, false, 5));
            Cancellable dismount = mock(Cancellable.class);
            rides.onDismount(rider, dismount);

            assertEquals(1, carrierTasks.size(), "restack must be scheduled on carrier's entity region");
            assertTrue(globalTasks.isEmpty());
            verify(scheduler, never()).runOnServer(any());
        }
    }

    @Test
    void claimBansSchedulesCountdownTicksOnCarrierEntityRegionOnFolia() {
        Rides rides = new Rides(plugin);
        ClaimAccess claims = mock(ClaimAccess.class);
        when(claims.check(carrier, rider)).thenReturn(ClaimAccess.Check.allowed("claim-7"));
        when(claims.isInside(rider, "claim-7")).thenReturn(true);
        when(claims.ban("claim-7", rider, carrier)).thenReturn(true);

        RideActions.Notifier notifier = mock(RideActions.Notifier.class);
        RideActions.Titles titles = mock(RideActions.Titles.class);
        ClaimBans bans = new ClaimBans(plugin, rides, claims, scheduler, server::getPlayer, notifier, titles, msg -> {});

        PowerToolContext ctx = new PowerToolContext(carrier, "allium:claim_handcuffs", null,
                mock(org.bukkit.inventory.ItemStack.class), "main",
                codes.castled.allium.tool.powertool.PowerToolTrigger.ON_FISH, null, rider, null);
        PowerToolAction action = new PowerToolAction("claim_ban", null, false, 0L,
                codes.castled.allium.tool.powertool.PowerToolCooldownScope.PLAYER, null, 0L, null,
                Map.of("countdown-seconds", 2));

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, carrier)).thenReturn(carrierOwner);
            platform.when(() -> BukkitServerThread.entity(plugin, rider)).thenReturn(riderOwner);

            assertEquals(Rides.Result.RESTRAINED, rides.carry(carrier, rider, true, 5, "allium:claim_handcuffs"));
            assertTrue(bans.start(ctx, action));
            assertTrue(bans.isPending(rider.getUniqueId()));

            // Countdown ticks 2 -> 1 -> 0 all execute on carrier's entity region
            assertEquals(1, carrierTasks.size());
            carrierTasks.remove().run(); // remaining = 1
            assertEquals(1, carrierTasks.size());
            carrierTasks.remove().run(); // remaining = 0 -> complete
            assertFalse(bans.isPending(rider.getUniqueId()));
            verify(claims).ban("claim-7", rider, carrier);
            verify(scheduler, never()).runOnServerLater(any(), anyLong());
        }
    }
}
