package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.player.TimedFlight;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlyCommandTimedTest {

    @Test
    void timesParseAsSecondsOrCompactDurations() {
        assertEquals(OptionalLong.of(90), FlyCommand.parseSeconds("90"));
        assertEquals(OptionalLong.of(1800), FlyCommand.parseSeconds("30m"));
        assertEquals(OptionalLong.of(5400), FlyCommand.parseSeconds("1h30m"));
        assertEquals(OptionalLong.of(86_400), FlyCommand.parseSeconds("1d"));
        assertTrue(FlyCommand.parseSeconds("soon").isEmpty());
        assertTrue(FlyCommand.parseSeconds("-5").isEmpty());
    }

    @Test
    void withoutAlliumFlyFlyIsTimedFlightAndStaffChangeAnyonesTime() {
        PluginStart plugin = mock(PluginStart.class);
        MessageManager messages = mock(MessageManager.class);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        TimedFlight timed = mock(TimedFlight.class);
        FlyCommand fly = new FlyCommand(plugin, () -> timed);

        Player steve = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(steve.getUniqueId()).thenReturn(id);
        when(steve.getName()).thenReturn("Steve");
        when(timed.toggle(steve)).thenReturn(TimedFlight.Toggle.ON);
        when(timed.seconds(id)).thenReturn(630L);
        fly.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.FLY, steve, "fly", new String[0]));
        verify(messages).send(steve, Messages.TFLY_ON, "{time}", "10m 30s");
        verify(steve, never()).setAllowFlight(true);
        when(timed.countsOnlyWhileFlying()).thenReturn(true);
        fly.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.FLY, steve, "fly", new String[0]));
        verify(messages).send(steve, Messages.TFLY_ON_FLYING, "{time}", "10m 30s");

        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");
        when(server.getPlayerExact("Steve")).thenReturn(steve);
        fly.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.FLY, console, "fly", new String[]{"Steve", "add", "1h"}));
        verify(messages).send(console, Messages.NO_PERMISSION, "{cmd}", "fly add");
        when(console.hasPermission("allium.tfly.add")).thenReturn(true);
        doAnswer(inv -> {
            inv.<Consumer<OptionalLong>>getArgument(3).accept(OptionalLong.of(4230));
            return null;
        }).when(timed).change(eq(id), eq(TimedFlight.Change.ADD), anyLong(), any());
        when(server.getPlayer(id)).thenReturn(steve);
        fly.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.FLY, console, "fly", new String[]{"Steve", "add", "1h"}));
        verify(timed).change(eq(id), eq(TimedFlight.Change.ADD), eq(3600L), any());
        verify(messages).send(console, Messages.TFLY_CHANGED, "{player}", "Steve", "{time}", "1h 10m 30s");
        verify(messages).send(steve, Messages.TFLY_NOTIFY, "{time}", "1h 10m 30s");
    }
}
