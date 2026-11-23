package codes.castled.allium.teleport;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanionTeleportsTest {
    private PluginStart plugin;
    private TaskScheduler scheduler;
    private MessageManager messages;
    private Player owner;
    private Wolf wolf;
    private CompanionTeleports service;
    private DestinationSafety safety;
    private Deque<Runnable> callbacks;
    private Location point;

    @BeforeEach
    void setup() {
        plugin = mock(PluginStart.class);
        var server = mock(Server.class);
        scheduler = mock(TaskScheduler.class);
        messages = mock(MessageManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getMessageManager()).thenReturn(messages);
        owner = mock(Player.class);
        when(owner.getUniqueId()).thenReturn(UUID.randomUUID());
        when(owner.hasPermission(anyString())).thenReturn(true);
        when(owner.isOnline()).thenReturn(true);
        var world = mock(World.class);
        point = new Location(world, 0, 80, 0);
        when(owner.getLocation()).thenReturn(point);
        when(owner.getWorld()).thenReturn(world);
        when(server.getPlayer(owner.getUniqueId())).thenReturn(owner);
        wolf = mock(Wolf.class);
        when(wolf.getUniqueId()).thenReturn(UUID.randomUUID());
        when(wolf.getOwner()).thenReturn(owner);
        when(wolf.isTamed()).thenReturn(true);
        when(wolf.isValid()).thenReturn(true);
        when(wolf.getType()).thenReturn(EntityType.WOLF);
        when(server.getEntity(wolf.getUniqueId())).thenReturn(wolf);
        when(wolf.teleport(any(Location.class))).thenReturn(true);
        safety = mock(DestinationSafety.class);
        when(safety.resolve(any(), any())).thenAnswer(call -> Optional.of(call.getArgument(0)));
        service = new CompanionTeleports(plugin, safety);
        callbacks = new ArrayDeque<>();
        when(scheduler.runOnServerLater(any(Runnable.class), anyLong())).thenAnswer(call -> {
            callbacks.add(call.getArgument(0));
            return TaskHandle.NONE;
        });
    }

    private void click(boolean cancelled, EquipmentSlot hand) {
        var event = new PlayerInteractEntityEvent(owner, wolf, hand);
        event.setCancelled(cancelled);
        service.click(event);
        while (!callbacks.isEmpty()) callbacks.remove().run();
    }

    @Test
    void selectionClaimsOnlyOnceAndRestoresGlow() {
        service.toggle(owner, owner, true);
        click(false, EquipmentSlot.HAND);
        assertTrue(service.move(owner, point));
        assertFalse(service.move(owner, point));
        verify(wolf, times(1)).teleport(point);
        verify(wolf).setGlowing(true);
        verify(wolf).setGlowing(false);
    }

    @Test
    void cancelledOrOffhandInteractionDoesNotSelect() {
        service.toggle(owner, owner, true);
        click(true, EquipmentSlot.HAND);
        click(false, EquipmentSlot.OFF_HAND);
        assertFalse(service.move(owner, point));
        verify(wolf, never()).setGlowing(true);
    }

    @Test
    void foreignPetAndPermissionRevocationRefuseMovement() {
        service.toggle(owner, owner, true);
        when(wolf.getOwner()).thenReturn(mock(AnimalTamer.class));
        click(false, EquipmentSlot.HAND);
        assertFalse(service.move(owner, point));
        when(wolf.getOwner()).thenReturn(owner);
        click(false, EquipmentSlot.HAND);
        when(owner.hasPermission("allium.tppet")).thenReturn(false);
        service.move(owner, point);
        verify(wolf, never()).teleport(any(Location.class));
    }

    @Test
    void toggleAndCloseRestorePreexistingGlow() {
        when(wolf.isGlowing()).thenReturn(true);
        service.toggle(owner, owner, true);
        click(false, EquipmentSlot.HAND);
        service.close();
        verify(wolf, never()).setGlowing(false);
        assertFalse(service.move(owner, point));
    }

    @Test
    void foliaRechecksTheLatestArrivalThenMovesOnTheEntityOwner() {
        var playerRegion = mock(ServerThread.class);
        var wolfRegion = mock(ServerThread.class);
        var tasks = new RegionalTasks(playerRegion, wolfRegion);
        var previous = new CompletableFuture<DestinationSafety.RegionalResult>();
        var latest = new CompletableFuture<DestinationSafety.RegionalResult>();
        var destination = point.clone().add(8192, 0, 8192);
        var moved = new CompletableFuture<Boolean>();
        when(safety.resolveRegional(eq(plugin), eq(point), any())).thenReturn(previous);
        when(safety.resolveRegional(eq(plugin), eq(destination), any())).thenReturn(latest);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, owner)).thenReturn(playerRegion);
            platform.when(() -> BukkitServerThread.entity(plugin, wolf)).thenReturn(wolfRegion);
            platform.when(() -> BukkitServerThread.teleportRegional(wolf, destination, TeleportCause.PLUGIN))
                    .thenAnswer(call -> {
                        assertEquals(wolfRegion, tasks.current.get());
                        return moved;
                    });

            tasks.current.set(playerRegion);
            service.toggle(owner, owner, true);
            click(false, EquipmentSlot.HAND);
            tasks.run(playerRegion);
            tasks.run(wolfRegion);
            tasks.run(playerRegion);
            verify(wolf, never()).setGlowing(true);
            tasks.run(wolfRegion);
            verify(wolf).setGlowing(true);

            assertTrue(service.move(owner, point));
            when(owner.getLocation()).thenReturn(destination);
            service.arrived(owner);
            previous.complete(new DestinationSafety.RegionalResult(Optional.of(point), false));
            assertTrue(tasks.queued.get(wolfRegion).isEmpty());
            latest.complete(new DestinationSafety.RegionalResult(Optional.of(destination), false));
            tasks.run(wolfRegion);
            verify(wolf).setGlowing(false);
            tasks.run(wolfRegion);
            assertFalse(service.move(owner, destination));
            verify(wolf, never()).teleport(any(Location.class));
            verify(messages, never()).send(owner, Messages.TELEPORT_SELECTION, "{message}",
                    "Teleported 1 selected companion(s); selection disabled.");

            moved.complete(true);
            tasks.run(playerRegion);
            verify(messages).send(owner, Messages.TELEPORT_SELECTION, "{message}",
                    "Teleported 1 selected companion(s); selection disabled.");
        }
    }

    private final class RegionalTasks {
        final AtomicReference<ServerThread> current = new AtomicReference<>();
        final Map<ServerThread, Deque<Runnable>> queued = new HashMap<>();

        RegionalTasks(ServerThread... regions) {
            for (var region : regions) {
                queued.put(region, new ArrayDeque<>());
                when(region.isServerThread()).thenAnswer(call -> current.get() == region);
            }
            when(scheduler.runOn(any(ServerThread.class), any(), any(), anyLong())).thenAnswer(call -> {
                queued.get(call.getArgument(0)).add(call.getArgument(1));
                return mock(TaskHandle.class);
            });
        }

        void run(ServerThread region) {
            ServerThread prior = current.getAndSet(region);
            try {
                queued.get(region).remove().run();
            } finally {
                current.set(prior);
            }
        }
    }
}
