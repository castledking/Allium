package codes.castled.allium.broadcast;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BroadcastServiceTest {

    @TempDir
    Path dir;
    private PluginStart plugin;
    private TaskScheduler scheduler;
    private AlliumLogger logger;
    private final YamlConfiguration config = new YamlConfiguration();
    private final List<Player> online = new ArrayList<>();
    private final List<BooleanSupplier> repeating = new ArrayList<>();
    private TaskHandle handle;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(PluginStart.class);
        scheduler = mock(TaskScheduler.class);
        logger = mock(AlliumLogger.class);
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.getName()).thenReturn("Paper");
        when(server.getConsoleSender()).thenReturn(mock(ConsoleCommandSender.class));
        when(server.getOnlinePlayers()).thenAnswer(inv -> List.copyOf(online));
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.logger()).thenReturn(logger);
        MessageManager messages = mock(MessageManager.class);
        when(messages.resolve(eq(Messages.BROADCAST_FORMAT), any(String[].class))).thenAnswer(inv ->
                Messages.BROADCAST_FORMAT.getDefault().replace("{message}", inv.<String>getArgument(2)));
        when(plugin.getMessageManager()).thenReturn(messages);
        handle = mock(TaskHandle.class);
        when(scheduler.repeatOnServer(any(BooleanSupplier.class), anyLong(), anyLong())).thenAnswer(inv -> {
            repeating.add(inv.getArgument(0));
            return handle;
        });
        Files.writeString(dir.resolve("broadcasts.txt"), "## header\nnot sent\n#first\nHello {player}\n#second,two\nSecond one\n");
    }

    private Player player(String name, boolean receives) {
        Player player = mock(Player.class);
        Player.Spigot spigot = mock(Player.Spigot.class);
        when(player.spigot()).thenReturn(spigot);
        when(player.getName()).thenReturn(name);
        when(player.hasPermission(BroadcastService.PERMISSION_RECEIVE)).thenReturn(receives);
        online.add(player);
        return player;
    }

    private static String sent(Player player) {
        ArgumentCaptor<BaseComponent[]> lines = ArgumentCaptor.forClass(BaseComponent[].class);
        verify(player.spigot(), org.mockito.Mockito.atLeastOnce()).sendMessage(lines.capture());
        return BaseComponent.toPlainText(lines.getValue());
    }

    @Test
    void idleByDefaultAndTheTimerFollowsTheIntervalAndReload() {
        BroadcastService service = new BroadcastService(plugin);
        service.start();
        assertFalse(service.settings().enabled());
        verify(scheduler, never()).repeatOnServer(any(BooleanSupplier.class), anyLong(), anyLong());

        config.set("broadcasts.enabled", true);
        config.set("broadcasts.interval-seconds", 60);
        service.reload();
        verify(scheduler).repeatOnServer(any(BooleanSupplier.class), eq(60_000L), eq(60_000L));
        config.set("broadcasts.interval-seconds", 2);
        config.set("broadcasts.order", "sometimes");
        service.reload();
        verify(handle).cancel();
        verify(scheduler).repeatOnServer(any(BooleanSupplier.class), eq(10_000L), eq(10_000L));
        verify(logger).warning(contains("interval-seconds"));
        verify(logger).warning(contains("broadcasts.order"));
        assertEquals(List.of("first", "second"), service.broadcasts());
    }

    @Test
    void aTurnGoesToReceiversOnlyFillsPlaceholdersPerPlayerAndWaitsForEnoughPlayers() {
        config.set("broadcasts.enabled", true);
        config.set("broadcasts.minimum-players", 2);
        BroadcastService service = new BroadcastService(plugin);
        service.start();
        Player steve = player("Steve", true);
        repeating.get(0).getAsBoolean();
        verify(steve.spigot(), never()).sendMessage(any(BaseComponent[].class));

        Player alex = player("Alex", true);
        Player optedOut = player("Quiet", false);
        assertTrue(repeating.get(0).getAsBoolean(), "the timer keeps running");
        assertEquals("Hello Steve", sent(steve));
        assertEquals("Hello Alex", sent(alex));
        verify(optedOut.spigot(), never()).sendMessage(any(BaseComponent[].class));

        repeating.get(0).getAsBoolean();
        assertEquals("Second one", sent(steve));
        assertTrue(service.send("first"));
        assertFalse(service.send("missing"));
    }

    @Test
    void broadcastSendsTheFormatToEveryoneAndLogsWhoSentIt() {
        BroadcastService service = new BroadcastService(plugin);
        service.start();
        Player steve = player("Steve", false);
        ConsoleCommandSender staff = mock(ConsoleCommandSender.class);
        when(staff.getName()).thenReturn("CONSOLE");
        service.announce(staff, "Restart in &e5 minutes");
        assertEquals("[Broadcast] Restart in 5 minutes", sent(steve), "manual broadcasts ignore the timed opt-out");
        verify(logger, times(1)).info(contains("Broadcast by CONSOLE"));
        verify(logger, never()).warning(anyString());
    }
}
