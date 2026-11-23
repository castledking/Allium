package codes.castled.allium.addon;

import codes.castled.allium.PluginStart;
import codes.castled.allium.api.AddonCommands;
import codes.castled.allium.api.AddonConnection;
import codes.castled.allium.api.AddonItems;
import codes.castled.allium.api.AddonText;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AddonSessionFoliaTest {

    @Test
    void addonConnectionDefaultsDelegateToSchedulerAndDirectTeleportOffFolia() {
        List<Runnable> queued = new ArrayList<>();
        AtomicBoolean onServer = new AtomicBoolean(false);
        TaskScheduler scheduler = new TaskScheduler() {
            @Override public boolean isServerThread() { return onServer.get(); }
            @Override public TaskHandle runOnServer(Runnable task) { queued.add(task); return TaskHandle.NONE; }
            @Override public TaskHandle runOnServerLater(Runnable task, long delayMillis) { queued.add(task); return TaskHandle.NONE; }
            @Override public TaskHandle repeatOnServer(BooleanSupplier step, long delayMillis, long periodMillis) {
                queued.add(step::getAsBoolean);
                return TaskHandle.NONE;
            }
            @Override public TaskHandle runAsyncLater(Runnable task, long delayMillis) { return TaskHandle.NONE; }
            @Override public ThreadPoolExecutor boundedPool(String name, int threads, int queueCapacity) {
                throw new UnsupportedOperationException();
            }
        };
        Plugin addon = mock(Plugin.class);
        AddonConnection connection = new AddonConnection() {
            @Override public Plugin plugin() { return addon; }
            @Override public AddonItems items() { return null; }
            @Override public AddonCommands commands() { return null; }
            @Override public TaskScheduler scheduler() { return scheduler; }
            @Override public AddonText text() { return null; }
        };

        World world = mock(World.class);
        Entity entity = mock(Entity.class);
        Location dest = new Location(world, 1, 2, 3);
        when(entity.teleport(dest, TeleportCause.PLUGIN)).thenReturn(true);

        assertFalse(connection.isFolia());
        assertFalse(connection.ownsEntity(entity));
        assertFalse(connection.ownsRegion(world, 0, 0));

        AtomicInteger ran = new AtomicInteger();
        connection.runForEntity(entity, ran::incrementAndGet, () -> {}, 0L);
        connection.runForRegion(world, 1, 2, ran::incrementAndGet, 50L);
        connection.onRegion(world, 1, 2, ran::incrementAndGet);
        connection.onOwner(mock(CommandSender.class), ran::incrementAndGet);
        connection.repeatForEntity(entity, () -> { ran.incrementAndGet(); return false; }, 50L, 50L);
        assertEquals(5, queued.size());
        queued.forEach(Runnable::run);
        assertEquals(5, ran.get());

        onServer.set(true);
        assertTrue(connection.ownsEntity(entity));
        assertTrue(connection.ownsRegion(world, 0, 0));
        connection.onRegion(world, 0, 0, ran::incrementAndGet);
        assertEquals(6, ran.get());

        assertTrue(connection.teleport(entity, dest, TeleportCause.PLUGIN).join());
        verify(entity).teleport(dest, TeleportCause.PLUGIN);
    }

    @Test
    void addonSessionRoutesScheduledWorkAndTextThroughBukkitServerThreadOffFolia() {
        PluginStart allium = mock(PluginStart.class);
        Plugin addon = mock(Plugin.class);
        Server server = mock(Server.class);
        BukkitScheduler bukkitScheduler = mock(BukkitScheduler.class);
        BukkitTask bukkitTask = mock(BukkitTask.class);
        when(addon.getName()).thenReturn("SampleAddon");
        when(addon.getLogger()).thenReturn(Logger.getLogger("SampleAddon"));
        when(addon.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(bukkitScheduler);

        List<Runnable> scheduled = new ArrayList<>();
        when(bukkitScheduler.runTaskLater(eq(addon), any(Runnable.class), anyLong())).thenAnswer(inv -> {
            scheduled.add(inv.getArgument(1));
            return bukkitTask;
        });

        AddonSession session = new AddonSession(allium, addon);
        assertFalse(session.isFolia());

        Player player = mock(Player.class);
        World world = mock(World.class);
        AtomicInteger executed = new AtomicInteger();
        session.runForEntity(player, executed::incrementAndGet, () -> {}, 0L);
        session.runForRegion(world, 2, 3, executed::incrementAndGet, 100L);
        session.runForSender(player, executed::incrementAndGet, () -> {}, 50L);
        assertEquals(3, scheduled.size());
        scheduled.forEach(Runnable::run);
        assertEquals(3, executed.get());

        session.text().send(player, "&aHello {who}", Map.of("who", "World"));
        verify(player).sendMessage(contains("Hello World"));

        Location target = new Location(world, 4, 64, 8);
        when(player.teleport(target, TeleportCause.PLUGIN)).thenReturn(true);
        assertTrue(session.teleport(player, target, TeleportCause.PLUGIN).join());
        verify(player).teleport(target, TeleportCause.PLUGIN);
    }
}
