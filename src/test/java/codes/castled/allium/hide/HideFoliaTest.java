package codes.castled.allium.hide;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.BoundedPools;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HideFoliaTest {

    private record Harness(
            HideService service,
            PluginStart plugin,
            Server server,
            TaskScheduler scheduler,
            MessageManager messages,
            Player actor,
            Player target,
            ServerThread actorOwner,
            ServerThread targetOwner
    ) {}

    private static Harness harness(Path dir) throws Exception {
        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        PluginManager pm = mock(PluginManager.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        MessageManager messages = mock(MessageManager.class);

        when(plugin.getName()).thenReturn("Allium");
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pm);
        when(pm.getPermissions()).thenReturn(Set.of());
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.getMessageManager()).thenReturn(messages);
        when(scheduler.boundedPool(anyString(), anyInt(), anyInt()))
                .thenAnswer(inv -> BoundedPools.create("Hide-Test", 1, 8));
        when(scheduler.runOn(any(), any(), any(), anyLong())).thenReturn(mock(TaskHandle.class));

        Player actor = player("Admin", UUID.randomUUID(), server);
        Player target = player("Target", UUID.randomUUID(), server);
        when(server.getPlayerExact("Target")).thenReturn(target);
        when(server.getPlayer(actor.getUniqueId())).thenReturn(actor);
        when(server.getPlayer(target.getUniqueId())).thenReturn(target);
        doReturn(List.of(actor, target)).when(server).getOnlinePlayers();

        Command guarded = mock(Command.class);
        when(guarded.getName()).thenReturn("guarded");
        when(guarded.getPermission()).thenReturn("secret.node");

        YamlConfiguration config = new YamlConfiguration();
        config.set("settings.enabled", true);
        config.set("settings.use-allium-permission-resolver", true);
        config.set("groups.default.commands", List.of("guarded"));
        config.set("groups.default.tabcompletes", List.of("guarded"));
        Files.writeString(dir.resolve("hide.yml"), config.saveToString());

        HideService service = new HideService(plugin);
        HideHelpTest.set(service, "settings", HideSettings.read(config, Map.of()));
        HideHelpTest.set(service, "commands", Map.of("guarded", guarded));

        ServerThread actorOwner = mock(ServerThread.class);
        ServerThread targetOwner = mock(ServerThread.class);
        when(actorOwner.isServerThread()).thenReturn(true);
        when(targetOwner.isServerThread()).thenReturn(false);

        return new Harness(service, plugin, server, scheduler, messages, actor, target, actorOwner, targetOwner);
    }

    private static Player player(String name, UUID id, Server server) {
        Player p = mock(Player.class);
        when(p.getName()).thenReturn(name);
        when(p.getUniqueId()).thenReturn(id);
        when(p.isOnline()).thenReturn(true);
        when(p.getServer()).thenReturn(server);
        when(p.getEffectivePermissions()).thenReturn(Set.of());
        when(p.hasPermission(anyString())).thenReturn(false);
        return p;
    }

    @Test
    void tickRoutesRefreshAndUpdateCommandsToPlayerEntityRegion(@TempDir Path dir) throws Exception {
        Harness h = harness(dir);
        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.target())).thenReturn(h.targetOwner());

            // Join happens on target's region: caches viewer and queues command-tree update.
            when(h.targetOwner().isServerThread()).thenReturn(true);
            h.service().joined(new PlayerJoinEvent(h.target(), "joined"));
            assertNotNull(h.service().viewer(h.target().getUniqueId()));

            // Global tick must NOT call target.updateCommands() directly; it routes to targetOwner.
            when(h.targetOwner().isServerThread()).thenReturn(false);
            var updateTask = ArgumentCaptor.forClass(Runnable.class);
            h.service().tick();

            verify(h.target(), never()).updateCommands();
            verify(h.scheduler()).runOn(eq(h.targetOwner()), updateTask.capture(), any(), eq(0L));

            when(h.targetOwner().isServerThread()).thenReturn(true);
            updateTask.getValue().run();
            verify(h.target()).updateCommands();

            // Force a stale snapshot and verify two ticks coalesce into one in-flight regional refresh.
            var stale = new HideService.Viewer(h.service().settings(), List.of(), false, Map.of(), 0L);
            var viewersField = HideService.class.getDeclaredField("viewers");
            viewersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var viewers = (java.util.concurrent.ConcurrentHashMap<UUID, HideService.Viewer>) viewersField.get(h.service());
            viewers.put(h.target().getUniqueId(), stale);

            when(h.targetOwner().isServerThread()).thenReturn(false);
            var refreshTask = ArgumentCaptor.forClass(Runnable.class);
            h.service().tick();
            h.service().tick();
            verify(h.scheduler(), times(2)).runOn(eq(h.targetOwner()), refreshTask.capture(), any(), eq(0L));

            // If a replacement connection takes over, the stale task must not update the old player.
            Player replacement = player("Target", h.target().getUniqueId(), h.server());
            when(h.server().getPlayer(h.target().getUniqueId())).thenReturn(replacement);
            refreshTask.getValue().run();
            verify(h.target(), times(1)).updateCommands();
            verify(replacement, never()).updateCommands();
        } finally {
            h.service().close();
        }
    }

    @Test
    void adminResolveRunsOnTargetRegionAndRepliesOnActorRegion(@TempDir Path dir) throws Exception {
        Harness h = harness(dir);
        when(h.actor().hasPermission(HideService.ADMIN)).thenReturn(true);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.actor())).thenReturn(h.actorOwner());
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.target())).thenReturn(h.targetOwner());

            var targetTask = ArgumentCaptor.forClass(Runnable.class);
            h.service().admin(h.actor(), new String[]{"resolve", "Target", "/guarded"});

            verify(h.scheduler()).runOn(eq(h.targetOwner()), targetTask.capture(), any(), eq(0L));
            verify(h.messages(), never()).send(eq(h.actor()), eq(Messages.HIDE_PERMISSION), any(String[].class));

            when(h.actorOwner().isServerThread()).thenReturn(false);
            when(h.targetOwner().isServerThread()).thenReturn(true);
            var actorReply = ArgumentCaptor.forClass(Runnable.class);
            targetTask.getValue().run();

            verify(h.scheduler()).runOn(eq(h.actorOwner()), actorReply.capture(), any(), eq(0L));
            when(h.actorOwner().isServerThread()).thenReturn(true);
            actorReply.getValue().run();

            verify(h.messages()).send(eq(h.actor()), eq(Messages.HIDE_PERMISSION), any(String[].class));
        } finally {
            h.service().close();
        }
    }
}
