package codes.castled.allium.ride;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.protection.ProtectionDecision;
import codes.castled.allium.protection.ProtectionHook;
import codes.castled.allium.protection.ProtectionService;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.tool.powertool.PowerToolAction;
import codes.castled.allium.tool.powertool.PowerToolContext;
import codes.castled.allium.tool.powertool.PowerToolCooldownScope;
import codes.castled.allium.tool.powertool.PowerToolTrigger;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RidesTest {

    private final YamlConfiguration config = new YamlConfiguration();
    private final Map<UUID, Entity> world = new HashMap<>();
    /** Who rides what, as the server would track it. */
    private final Map<Entity, Entity> vehicles = new HashMap<>();
    private PluginStart plugin;
    private Server server;
    private MessageManager messages;
    private Rides rides;
    private ConsoleCommandSender console;

    @BeforeEach
    void setUp() {
        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        messages = mock(MessageManager.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(scheduler.runOnServer(any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(0).run();
            return TaskHandle.NONE;
        });
        when(scheduler.runOnServerLater(any(), anyLong())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(0).run();
            return TaskHandle.NONE;
        });
        console = mock(ConsoleCommandSender.class);
        when(server.getConsoleSender()).thenReturn(console);
        when(server.getPlayer(any(UUID.class))).thenAnswer(inv -> world.get(inv.<UUID>getArgument(0)) instanceof Player p ? p : null);
        when(server.getEntity(any(UUID.class))).thenAnswer(inv -> world.get(inv.<UUID>getArgument(0)));
        rides = new Rides(plugin);
    }

    private <T extends Entity> T entity(Class<T> type, String name) {
        T entity = mock(type);
        UUID id = UUID.randomUUID();
        when(entity.getUniqueId()).thenReturn(id);
        when(entity.getName()).thenReturn(name);
        when(entity.isValid()).thenReturn(true);
        when(entity.getVehicle()).thenAnswer(inv -> vehicles.get(entity));
        when(entity.getPassengers()).thenAnswer(inv -> vehicles.entrySet().stream()
                .filter(e -> e.getValue() == entity).map(Map.Entry::getKey).toList());
        when(entity.addPassenger(any())).thenAnswer(inv -> {
            vehicles.put(inv.getArgument(0), entity);
            return true;
        });
        when(entity.leaveVehicle()).thenAnswer(inv -> vehicles.remove(entity) != null);
        when(entity.teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class))).thenReturn(true);
        world.put(id, entity);
        return entity;
    }

    private Player player(String name) {
        Player player = entity(Player.class, name);
        when(player.isOnline()).thenReturn(true);
        when(player.getServer()).thenReturn(server);
        return player;
    }

    @Test
    void ridersStackBottomFirstAndTheStackRefusesTheCarrierRepeatsAndOverflow() {
        Player staff = player("Staff");
        Player alex = player("Alex");
        Player sam = player("Sam");
        Player kim = player("Kim");
        assertEquals(Rides.Result.RESTRAINED, rides.carry(staff, alex, true, 2));
        assertEquals(Rides.Result.RESTRAINED, rides.carry(staff, sam, true, 2));
        assertSame(staff, vehicles.get(alex));
        assertSame(alex, vehicles.get(sam), "the second rides the first");
        assertEquals(List.of(alex.getUniqueId(), sam.getUniqueId()), rides.riders(staff.getUniqueId()));
        verify(alex).setCollidable(false);

        assertEquals(Rides.Result.FULL, rides.carry(staff, kim, true, 2));
        assertEquals(Rides.Result.BUSY, rides.carry(staff, alex, true, 5), "already carried");
        assertEquals(Rides.Result.BUSY, rides.carry(kim, staff, true, 5), "carrying someone");
        assertEquals(Rides.Result.BUSY, rides.carry(alex, kim, true, 5), "a rider carries no one");
        assertEquals(Rides.Result.SELF, rides.carry(staff, staff, true, 5));
        Pig pig = entity(Pig.class, "Pig");
        assertEquals(Rides.Result.CARRIED, rides.carry(kim, pig, true, 5), "restrain applies to players only");
        assertFalse(rides.isRestrained(pig.getUniqueId()));
    }

    @Test
    void lettingTheBottomRiderGoMovesTheOnesAboveDownAndReleaseAllEmptiesTheStack() {
        Player staff = player("Staff");
        Player alex = player("Alex");
        Player sam = player("Sam");
        rides.carry(staff, alex, true, 5);
        rides.carry(staff, sam, true, 5);
        assertSame(alex, rides.let(alex.getUniqueId()));
        assertNull(vehicles.get(alex));
        assertSame(staff, vehicles.get(sam), "Sam moved down onto the carrier");
        verify(alex).setCollidable(true);
        assertEquals(List.of(sam.getUniqueId()), rides.riders(staff.getUniqueId()));

        rides.carry(staff, alex, true, 5);
        assertEquals(List.of(alex), rides.release(staff, false), "last: the top one");
        assertEquals(List.of(sam), rides.release(staff, true));
        assertTrue(rides.riders(staff.getUniqueId()).isEmpty());
        assertTrue(rides.release(staff, true).isEmpty());
    }

    @Test
    void restrainedRidersCannotGetDownOrRunCommandsButCarriedOnesMayGetDown() {
        Player staff = player("Staff");
        Player alex = player("Alex");
        Player sam = player("Sam");
        rides.carry(staff, alex, true, 5);
        rides.carry(sam, player("Kim"), false, 5);

        org.bukkit.event.Cancellable dismount = mock(org.bukkit.event.Cancellable.class);
        rides.onDismount(alex, dismount);
        rides.onDismount(alex, dismount);
        verify(dismount, times(2)).setCancelled(true);
        verify(messages, times(1)).send(alex, Messages.RIDE_NO_DISMOUNT);

        Player kim = (Player) rides.riders(sam.getUniqueId()).stream().map(world::get).findFirst().orElseThrow();
        org.bukkit.event.Cancellable getDown = mock(org.bukkit.event.Cancellable.class);
        rides.onDismount(kim, getDown);
        verify(getDown, never()).setCancelled(true);
        assertFalse(rides.isCarried(kim.getUniqueId()), "a carried player who gets down is no longer carried");

        config.set("restraints.allowed-commands", List.of("msg"));
        PlayerCommandPreprocessEvent spawn = new PlayerCommandPreprocessEvent(alex, "/spawn");
        rides.onCommand(spawn);
        assertTrue(spawn.isCancelled());
        verify(messages).send(alex, Messages.RIDE_NO_COMMANDS);
        for (String allowed : List.of("/msg Staff let me go", "/allium:msg Staff hi")) {
            PlayerCommandPreprocessEvent msg = new PlayerCommandPreprocessEvent(alex, allowed);
            rides.onCommand(msg);
            assertFalse(msg.isCancelled(), allowed);
        }
        PlayerCommandPreprocessEvent free = new PlayerCommandPreprocessEvent(staff, "/spawn");
        rides.onCommand(free);
        assertFalse(free.isCancelled(), "the carrier is not restrained");
    }

    @Test
    void restrainedPlayersTakeNoDamageAndDealNone() {
        Player staff = player("Staff");
        Player alex = player("Alex");
        rides.carry(staff, alex, true, 5);
        EntityDamageEvent hurt = mock(EntityDamageEvent.class);
        when(hurt.getEntity()).thenReturn(alex);
        rides.onDamage(hurt);
        verify(hurt).setCancelled(true);
        EntityDamageByEntityEvent attack = mock(EntityDamageByEntityEvent.class);
        when(attack.getEntity()).thenReturn(staff);
        when(attack.getDamager()).thenReturn(alex);
        rides.onDamage(attack);
        verify(attack).setCancelled(true);
        EntityDamageByEntityEvent fair = mock(EntityDamageByEntityEvent.class);
        when(fair.getEntity()).thenReturn(alex);
        when(fair.getDamager()).thenReturn(staff);
        rides.onDamage(fair);
        verify(fair).setCancelled(true);
    }

    @Test
    void loggingOutWhileRestrainedRunsTheQuitCommandsAndACarrierLeavingFreesEveryone() {
        config.set("restraints.commands-on-quit", List.of("ban {player} Logged out while restrained", " "));
        Player staff = player("Staff");
        Player alex = player("Alex");
        Player sam = player("Sam");
        rides.carry(staff, alex, true, 5);
        rides.carry(staff, sam, true, 5);
        rides.onQuit(new PlayerQuitEvent(alex, "bye"));
        verify(server).dispatchCommand(console, "ban Alex Logged out while restrained");
        verify(messages).send(staff, Messages.RIDE_LOGGED_OUT, "{target}", "Alex");
        assertSame(staff, vehicles.get(sam), "Sam moved down");

        rides.onQuit(new PlayerQuitEvent(staff, "bye"));
        assertFalse(rides.isCarried(sam.getUniqueId()));
        assertNull(vehicles.get(sam));
        verify(messages).send(sam, Messages.RIDE_RELEASED_TARGET, "{player}", "Staff");
        verify(server, times(1)).dispatchCommand(any(), anyString());
    }

    @Test
    void alliumTeleportsTakeTheRidersAlongAndOtherTeleportsLetARiderGo() {
        Player staff = player("Staff");
        Player alex = player("Alex");
        Player sam = player("Sam");
        rides.carry(staff, alex, true, 5);
        rides.carry(staff, sam, true, 5);
        Location spawn = new Location(null, 0, 64, 0);
        assertTrue(rides.teleport(staff, spawn, PlayerTeleportEvent.TeleportCause.PLUGIN));
        verify(staff).teleport(spawn, PlayerTeleportEvent.TeleportCause.PLUGIN);
        verify(alex).teleport(spawn, PlayerTeleportEvent.TeleportCause.PLUGIN);
        verify(sam).teleport(spawn, PlayerTeleportEvent.TeleportCause.PLUGIN);
        assertSame(staff, vehicles.get(alex), "seated again");
        assertSame(alex, vehicles.get(sam));

        vehicles.remove(alex); // the teleport itself gets them down
        rides.onTeleport(new PlayerTeleportEvent(alex, spawn, spawn));
        assertFalse(rides.isCarried(alex.getUniqueId()));
        assertSame(staff, vehicles.get(sam));

        Player loner = player("Loner");
        assertTrue(rides.teleport(loner, spawn, PlayerTeleportEvent.TeleportCause.PLUGIN), "no riders: a plain teleport");
    }

    // -- Actions ----------------------------------------------------------------------------------

    private final List<String> titles = new ArrayList<>();

    private RideActions actions(boolean protectionAllows) {
        ProtectionHook hook = mock(ProtectionHook.class);
        when(hook.name()).thenReturn("Claims");
        when(hook.checkEntityInteraction(any(), any(), any())).thenReturn(protectionAllows
                ? ProtectionDecision.allow() : ProtectionDecision.deny("Claims", null));
        ProtectionService protection = new ProtectionService(List.of(hook), null, message -> { });
        return new RideActions(rides, protection, messages::send,
                (player, title, subtitle, in, stay, out, replacements) -> titles.add(player.getName() + ":" + title.name()), Entity::getName);
    }

    private static PowerToolAction action(String type, Map<String, Object> params) {
        return new PowerToolAction(type, null, false, 0L, PowerToolCooldownScope.PLAYER, null, 0L, null, params);
    }

    private static PowerToolContext context(Player player, Entity target, PowerToolTrigger trigger, org.bukkit.event.Event cause) {
        return new PowerToolContext(player, "allium:handcuffs", null, mock(ItemStack.class), "main", trigger, null, target, cause);
    }

    @Test
    void theRideActionRestrainsPlayersOnlyAndRespectsTheExemptNode() {
        RideActions actions = actions(true);
        Player staff = player("Staff");
        Player alex = player("Alex");
        Player admin = player("Admin");
        when(admin.hasPermission("allium.handcuffs.resist")).thenReturn(true);
        Pig pig = entity(Pig.class, "Pig");
        when(pig.getType()).thenReturn(EntityType.PIG);
        Map<String, Object> cuffs = Map.of("restrain", true, "exempt-permission", "allium.handcuffs.resist");

        assertTrue(actions.ride(context(staff, alex, PowerToolTrigger.ON_FISH, null), action("ride", cuffs)));
        verify(messages).send(staff, Messages.RIDE_RESTRAINED, "{target}", "Alex");
        verify(messages).send(alex, Messages.RIDE_RESTRAINED_TARGET, "{player}", "Staff");
        assertEquals(List.of("Alex:RIDE_RESTRAINED_TITLE"), titles);
        assertTrue(rides.isRestrained(alex.getUniqueId()));

        assertFalse(actions.ride(context(staff, admin, PowerToolTrigger.ON_FISH, null), action("ride", cuffs)));
        verify(messages).send(staff, Messages.RIDE_EXEMPT, "{target}", "Admin");
        assertFalse(actions.ride(context(staff, pig, PowerToolTrigger.ON_FISH, null), action("ride", cuffs)));
        verify(messages).send(staff, Messages.RIDE_PLAYERS_ONLY, "{target}", "Pig");
        assertFalse(actions.ride(context(staff, null, PowerToolTrigger.ON_FISH, null), action("ride", cuffs)), "nothing hooked");

        assertFalse(actions.ride(context(staff, pig, PowerToolTrigger.ON_INTERACT_ENTITY, null),
                action("ride", Map.of("entity-types", List.of("cow")))), "not an allowed type");
        assertTrue(actions.ride(context(staff, pig, PowerToolTrigger.ON_INTERACT_ENTITY, null),
                action("ride", Map.of("entity-types", "pig, cow"))));
        verify(messages).send(staff, Messages.RIDE_CARRYING, "{target}", "Pig");
        assertSame(alex, vehicles.get(pig), "on top of the stack");
    }

    @Test
    void mobsArePickedUpAndRiddenOnlyWhereProtectionAllows() {
        RideActions actions = actions(false);
        Player staff = player("Staff");
        Pig pig = entity(Pig.class, "Pig");
        when(pig.getType()).thenReturn(EntityType.PIG);
        assertFalse(actions.ride(context(staff, pig, PowerToolTrigger.ON_INTERACT_ENTITY, null), action("ride", Map.of())));
        assertFalse(actions.mount(context(staff, pig, PowerToolTrigger.ON_INTERACT_ENTITY, null), action("mount", Map.of())));
        verify(messages, times(2)).send(staff, Messages.RIDE_PROTECTED, "{target}", "Pig");
        assertNull(vehicles.get(pig));
        assertNull(vehicles.get(staff));

        RideActions open = actions(true);
        assertTrue(open.mount(context(staff, pig, PowerToolTrigger.ON_INTERACT_ENTITY, null), action("mount", Map.of())));
        assertSame(pig, vehicles.get(staff), "the player rides the pig");
    }

    @Test
    void releaseLetsEveryoneGoAndKeepsTheDroppedHandcuffsOnlyWhenItDid() {
        RideActions actions = actions(true);
        Player staff = player("Staff");
        Player alex = player("Alex");
        PlayerDropItemEvent idle = mock(PlayerDropItemEvent.class);
        assertFalse(actions.release(context(staff, null, PowerToolTrigger.DROP, idle), action("release", Map.of())));
        verify(idle, never()).setCancelled(true);

        rides.carry(staff, alex, true, 5);
        PlayerDropItemEvent drop = mock(PlayerDropItemEvent.class);
        assertTrue(actions.release(context(staff, null, PowerToolTrigger.DROP, drop), action("release", Map.of("which", "all"))));
        verify(drop).setCancelled(true);
        verify(messages).send(staff, Messages.RIDE_RELEASED, "{target}", "Alex");
        verify(messages).send(alex, Messages.RIDE_RELEASED_TARGET, "{player}", "Staff");
        assertFalse(rides.isRestrained(alex.getUniqueId()));
    }
}
