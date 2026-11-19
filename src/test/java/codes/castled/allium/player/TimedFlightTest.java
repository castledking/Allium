package codes.castled.allium.player;

import codes.castled.allium.PluginStart;
import codes.castled.allium.flight.FlightTimeRepository;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TimedFlightTest {

    private PluginStart plugin;
    private MessageManager messages;
    private FlightTimeRepository repository;
    private FlightRestoration flights;
    private Player steve;
    private final UUID id = UUID.randomUUID();
    private boolean allowFlight, flying;
    private TimedFlight timed;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(PluginStart.class);
        messages = mock(MessageManager.class);
        repository = mock(FlightTimeRepository.class);
        flights = mock(FlightRestoration.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(scheduler.runOnServer(any(Runnable.class))).thenAnswer(inv -> {
            inv.<Runnable>getArgument(0).run();
            return TaskHandle.NONE;
        });
        when(scheduler.repeatOnServer(any(), anyLong(), anyLong())).thenReturn(TaskHandle.NONE);
        ThreadPoolExecutor io = mock(ThreadPoolExecutor.class);
        doAnswer(inv -> {
            inv.<Runnable>getArgument(0).run();
            return null;
        }).when(io).execute(any(Runnable.class));
        steve = mock(Player.class);
        when(steve.getUniqueId()).thenReturn(id);
        when(steve.getName()).thenReturn("Steve");
        when(steve.isOnline()).thenReturn(true);
        when(steve.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(steve.getAllowFlight()).thenAnswer(inv -> allowFlight);
        when(steve.isFlying()).thenAnswer(inv -> flying);
        doAnswer(inv -> allowFlight = inv.getArgument(0)).when(steve).setAllowFlight(org.mockito.ArgumentMatchers.anyBoolean());
        doAnswer(inv -> flying = inv.getArgument(0)).when(steve).setFlying(org.mockito.ArgumentMatchers.anyBoolean());
        when(server.getOnlinePlayers()).thenAnswer(inv -> List.of(steve));
        when(repository.seconds(id)).thenReturn(6L);
        timed = new TimedFlight(plugin, repository, io, flights);
        timed.enable();
    }

    @Test
    void timeRunsOnlyWhileFlyingWarnsAndEndsFlightAtZero() throws Exception {
        assertEquals(TimedFlight.Toggle.ON, timed.toggle(steve));
        assertTrue(allowFlight);
        assertFalse(flying, "like legacy tfly, the player takes off themselves");
        timed.tick();
        assertEquals(6, timed.seconds(id), "standing still costs nothing");

        flying = true;
        timed.tick();
        assertEquals(5, timed.seconds(id));
        verify(messages).send(steve, Messages.TFLY_WARNING, "{time}", "5s");
        for (int i = 0; i < 5; i++) timed.tick();
        assertEquals(0, timed.seconds(id));
        assertFalse(allowFlight, "flight ends at zero");
        assertFalse(timed.active(id));
        verify(messages).send(steve, Messages.TFLY_EXPIRED);
        verify(flights, org.mockito.Mockito.atLeastOnce()).changed(steve);
        verify(repository).add(id, -6L);
        assertEquals(TimedFlight.Toggle.NONE, timed.toggle(steve));
    }

    @Test
    void creativeFlightAndLostFlightDoNotSpendTime() throws Exception {
        timed.toggle(steve);
        flying = true;
        when(steve.getGameMode()).thenReturn(GameMode.CREATIVE);
        timed.tick();
        assertEquals(6, timed.seconds(id));
        when(steve.getGameMode()).thenReturn(GameMode.SURVIVAL);
        allowFlight = false;
        timed.tick();
        assertFalse(timed.active(id), "another plugin took flight away");
        assertEquals(6, timed.seconds(id));
        verify(repository, never()).add(eq(id), anyLong());
    }

    @Test
    void staffChangesIncludeUnsavedTimeAndTakingItAllEndsFlight() throws Exception {
        timed.toggle(steve);
        flying = true;
        timed.tick();
        timed.tick();
        when(repository.add(id, 600 - 2)).thenReturn(604L);
        AtomicReference<OptionalLong> result = new AtomicReference<>();
        timed.change(id, TimedFlight.Change.ADD, 600, result::set);
        assertEquals(OptionalLong.of(604), result.get());
        assertEquals(604, timed.seconds(id));

        when(repository.set(id, 0)).thenReturn(0L);
        timed.change(id, TimedFlight.Change.RESET, 0, result::set);
        assertEquals(0, timed.seconds(id));
        assertFalse(allowFlight, "resetting a flier's time ends their timed flight");
        verify(messages).send(steve, Messages.TFLY_EXPIRED);
    }

    @Test
    void aPlayerWithoutLoadedTimeIsToldToWait() throws Exception {
        Player alex = mock(Player.class);
        when(alex.getUniqueId()).thenReturn(UUID.randomUUID());
        assertEquals(TimedFlight.Toggle.LOADING, timed.toggle(alex));
        timed.toggle(steve);
        assertEquals(TimedFlight.Toggle.OFF, timed.toggle(steve));
        assertFalse(allowFlight);
    }
}
