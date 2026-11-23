package codes.castled.allium.restart;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoRestartFoliaTest {

    private record Harness(
            AutoRestart restart,
            PluginStart plugin,
            Server server,
            TaskScheduler scheduler,
            MessageManager messages,
            Player alice,
            Player bob,
            ServerThread aliceOwner,
            ServerThread bobOwner
    ) {}

    private static Harness harness(Path dir) throws Exception {
        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        PluginManager pm = mock(PluginManager.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        MessageManager messages = mock(MessageManager.class);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);

        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(server.getPluginManager()).thenReturn(pm);
        when(server.getConsoleSender()).thenReturn(console);
        when(messages.resolve(Messages.RESTART_KICK)).thenReturn("Server restarting!");
        when(scheduler.repeatOnServer(any(), anyLong(), anyLong())).thenReturn(mock(TaskHandle.class));
        when(scheduler.runOnServerLater(any(), anyLong())).thenReturn(mock(TaskHandle.class));
        when(scheduler.runOn(any(), any(), any(), anyLong())).thenReturn(mock(TaskHandle.class));

        Player alice = player("Alice", UUID.randomUUID());
        Player bob = player("Bob", UUID.randomUUID());
        when(server.getPlayer(alice.getUniqueId())).thenReturn(alice);
        when(server.getPlayer(bob.getUniqueId())).thenReturn(bob);
        doReturn(List.of(alice, bob)).when(server).getOnlinePlayers();

        YamlConfiguration config = new YamlConfiguration();
        config.set("enabled", false);
        config.set("save", false);
        config.set("final-message", false);
        Files.writeString(dir.resolve("autorestart.yml"), config.saveToString());

        AutoRestart restart = new AutoRestart(plugin);
        assertTrue(restart.reload());

        ServerThread aliceOwner = mock(ServerThread.class);
        ServerThread bobOwner = mock(ServerThread.class);
        when(aliceOwner.isServerThread()).thenReturn(false);
        when(bobOwner.isServerThread()).thenReturn(false);

        return new Harness(restart, plugin, server, scheduler, messages, alice, bob, aliceOwner, bobOwner);
    }

    private static Player player(String name, UUID id) {
        Player p = mock(Player.class);
        when(p.getName()).thenReturn(name);
        when(p.getUniqueId()).thenReturn(id);
        when(p.isOnline()).thenReturn(true);
        when(p.hasPermission(anyString())).thenReturn(true);
        return p;
    }

    @Test
    void stopServerKicksEachPlayerOnTheirRegionBeforeShuttingDownOnGlobalThread(@TempDir Path dir) throws Exception {
        Harness h = harness(dir);
        TaskHandle fallbackHandle = mock(TaskHandle.class);
        when(h.scheduler().runOnServerLater(any(), eq(1_000L))).thenReturn(fallbackHandle);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.alice())).thenReturn(h.aliceOwner());
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.bob())).thenReturn(h.bobOwner());

            var tickCaptor = ArgumentCaptor.forClass(BooleanSupplier.class);
            h.restart().execute(new BukkitCommandContext(h.plugin(), CoreCommandSpecs.AUTORESTART,
                    h.server().getConsoleSender(), "ar", new String[]{"now"}));
            verify(h.scheduler()).repeatOnServer(tickCaptor.capture(), eq(50L), eq(250L));

            // Fire tick on global thread: players must NOT be kicked directly, nor shutdown called yet.
            when(h.scheduler().isServerThread()).thenReturn(true);
            var aliceKick = ArgumentCaptor.forClass(Runnable.class);
            var bobRetired = ArgumentCaptor.forClass(Runnable.class);
            assertFalse(tickCaptor.getValue().getAsBoolean());

            verify(h.alice(), never()).kickPlayer(anyString());
            verify(h.bob(), never()).kickPlayer(anyString());
            verify(h.server(), never()).shutdown();
            verify(h.scheduler()).runOn(eq(h.aliceOwner()), aliceKick.capture(), any(), eq(0L));
            verify(h.scheduler()).runOn(eq(h.bobOwner()), any(), bobRetired.capture(), eq(0L));

            // Alice's region kicks Alice; Bob retired before his kick ran.
            when(h.scheduler().isServerThread()).thenReturn(false);
            var shutdownOnGlobal = ArgumentCaptor.forClass(Runnable.class);
            aliceKick.getValue().run();
            verify(h.alice()).kickPlayer("Server restarting!");
            verify(h.server(), never()).shutdown();

            bobRetired.getValue().run();
            verify(h.bob(), never()).kickPlayer(anyString());
            verify(h.scheduler()).runOnServer(shutdownOnGlobal.capture());

            shutdownOnGlobal.getValue().run();
            verify(h.server(), times(1)).shutdown();
            verify(fallbackHandle).cancel();
        }
    }

    @Test
    void stalledRegionStillShutsDownOnceViaFallback(@TempDir Path dir) throws Exception {
        Harness h = harness(dir);
        var fallbackCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(h.scheduler().runOnServerLater(fallbackCaptor.capture(), eq(1_000L))).thenReturn(mock(TaskHandle.class));

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.alice())).thenReturn(h.aliceOwner());
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.bob())).thenReturn(h.bobOwner());

            var tickCaptor = ArgumentCaptor.forClass(BooleanSupplier.class);
            h.restart().execute(new BukkitCommandContext(h.plugin(), CoreCommandSpecs.AUTORESTART,
                    h.server().getConsoleSender(), "ar", new String[]{"now"}));
            verify(h.scheduler()).repeatOnServer(tickCaptor.capture(), eq(50L), eq(250L));
            tickCaptor.getValue().getAsBoolean();

            var aliceKick = ArgumentCaptor.forClass(Runnable.class);
            var bobKick = ArgumentCaptor.forClass(Runnable.class);
            verify(h.scheduler()).runOn(eq(h.aliceOwner()), aliceKick.capture(), any(), eq(0L));
            verify(h.scheduler()).runOn(eq(h.bobOwner()), bobKick.capture(), any(), eq(0L));

            // Fallback triggers shutdown even though regions didn't run yet.
            fallbackCaptor.getValue().run();
            verify(h.server(), times(1)).shutdown();

            // Late completions never invoke shutdown a second time.
            when(h.scheduler().isServerThread()).thenReturn(true);
            aliceKick.getValue().run();
            bobKick.getValue().run();
            verify(h.server(), times(1)).shutdown();
        }
    }

    @Test
    void voteFromDifferentRegionsSchedulesRestartOncePassed(@TempDir Path dir) throws Exception {
        Harness h = harness(dir);
        when(h.aliceOwner().isServerThread()).thenReturn(true);
        when(h.bobOwner().isServerThread()).thenReturn(true);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.alice())).thenReturn(h.aliceOwner());
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.bob())).thenReturn(h.bobOwner());

            var voteCmd = h.restart().voteCommand();
            voteCmd.execute(new BukkitCommandContext(h.plugin(), CoreCommandSpecs.VOTERESTART, h.alice(), "vr", new String[]{"start"}));
            voteCmd.execute(new BukkitCommandContext(h.plugin(), CoreCommandSpecs.VOTERESTART, h.alice(), "vr", new String[]{"yes"}));
            voteCmd.execute(new BukkitCommandContext(h.plugin(), CoreCommandSpecs.VOTERESTART, h.alice(), "vr", new String[]{"no"}));
            voteCmd.execute(new BukkitCommandContext(h.plugin(), CoreCommandSpecs.VOTERESTART, h.bob(), "vr", new String[]{"yes"}));

            verify(h.messages()).send(eq(h.alice()), eq(Messages.RESTART_VOTE_CAST), eq("{vote}"), eq("yes"));
            verify(h.messages()).send(eq(h.alice()), eq(Messages.RESTART_VOTE_DUPLICATE));
            verify(h.messages()).send(eq(h.bob()), eq(Messages.RESTART_VOTE_CAST), eq("{vote}"), eq("yes"));

            h.restart().finishVote();
            verify(h.messages()).broadcast(eq(Messages.RESTART_VOTE_PASSED), eq("{yes}"), eq("2"), eq("{no}"), eq("0"));
            assertEquals("true", h.restart().placeholder("restart_scheduled"));
        }
    }
}
