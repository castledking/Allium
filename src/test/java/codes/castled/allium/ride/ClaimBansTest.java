package codes.castled.allium.ride;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
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
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaimBansTest {

    /** A claim system the test controls: who may claim-restrain, whether the target is inside, bans. */
    private static final class FakeClaims implements ClaimAccess {
        ClaimAccess.Check answer = ClaimAccess.Check.allowed("claim-7");
        boolean inside = true;
        boolean banWorks = true;
        final List<String> bans = new ArrayList<>();

        @Override public Check check(Player actor, Player target) { return answer; }
        @Override public boolean isInside(Player target, String claimId) { return inside; }
        @Override public boolean ban(String claimId, Player target, CommandSender actor) {
            if (banWorks) bans.add(claimId + ":" + target.getName() + " by " + actor.getName());
            return banWorks;
        }
    }

    private final Map<UUID, Entity> world = new HashMap<>();
    private final Map<Entity, Entity> vehicles = new HashMap<>();
    private final Deque<Runnable> later = new ArrayDeque<>();
    private final List<String> titles = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final FakeClaims claims = new FakeClaims();
    private MessageManager messages;
    private Rides rides;
    private RideActions actions;
    private ClaimBans bans;
    private Player owner;
    private Player intruder;

    @BeforeEach
    void setUp() {
        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        messages = mock(MessageManager.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(scheduler.runOnServer(any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(0).run();
            return TaskHandle.NONE;
        });
        when(scheduler.runOnServerLater(any(), anyLong())).thenAnswer(inv -> {
            later.add(inv.getArgument(0));
            return TaskHandle.NONE;
        });
        when(server.getPlayer(any(UUID.class))).thenAnswer(inv -> world.get(inv.<UUID>getArgument(0)) instanceof Player p ? p : null);
        when(server.getEntity(any(UUID.class))).thenAnswer(inv -> world.get(inv.<UUID>getArgument(0)));
        rides = new Rides(plugin);
        RideActions.Titles shown = (player, title, subtitle, in, stay, out, replacements) -> titles.add(player.getName() + ":" + title.name());
        actions = new RideActions(rides, new ProtectionService(List.of(), null, message -> { }), messages::send, shown, Entity::getName, claims);
        bans = new ClaimBans(rides, claims, scheduler, server::getPlayer, messages::send, shown, log::add);
        owner = player("Owner");
        intruder = player("Intruder");
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.isValid()).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(null, 0, 64, 0));
        when(player.getVehicle()).thenAnswer(inv -> vehicles.get(player));
        when(player.getPassengers()).thenAnswer(inv -> vehicles.entrySet().stream()
                .filter(e -> e.getValue() == player).map(Map.Entry::getKey).toList());
        when(player.addPassenger(any())).thenAnswer(inv -> {
            vehicles.put(inv.getArgument(0), player);
            return true;
        });
        when(player.leaveVehicle()).thenAnswer(inv -> vehicles.remove(player) != null);
        world.put(id, player);
        return player;
    }

    private static PowerToolAction action(String type, Map<String, Object> params) {
        return new PowerToolAction(type, null, false, 0L, PowerToolCooldownScope.PLAYER, null, 0L, null, params);
    }

    private PowerToolContext hooked() {
        return new PowerToolContext(owner, "allium:claim_handcuffs", null, mock(ItemStack.class), "main", PowerToolTrigger.ON_FISH,
                null, intruder, null);
    }

    /** The claim handcuffs' two actions, as tools/claim_handcuffs.yml runs them. */
    private boolean cuff() {
        PowerToolContext context = hooked();
        return actions.ride(context, action("ride", Map.of("restrain", true, "claim", true, "max-riders", 1)))
                && bans.start(context, action("claim_ban", Map.of("countdown-seconds", 3)));
    }

    private void runLater() {
        while (!later.isEmpty()) later.poll().run();
    }

    @Test
    void theCountdownEndsWithTheTargetLetGoAndBannedFromTheClaim() {
        assertTrue(cuff());
        assertSame(owner, vehicles.get(intruder));
        assertEquals("allium:claim_handcuffs", rides.toolOf(intruder.getUniqueId()));
        assertTrue(rides.restrains(owner.getUniqueId(), "allium:claim_handcuffs"));
        assertFalse(rides.restrains(owner.getUniqueId(), "allium:handcuffs"));
        verify(messages).send(owner, Messages.CLAIM_BAN_STARTED, "{seconds}", "3");
        verify(messages).send(intruder, Messages.CLAIM_BAN_STARTED_TARGET);
        assertTrue(bans.isPending(intruder.getUniqueId()));
        assertTrue(titles.contains("Owner:CLAIM_BAN_TITLE") && titles.contains("Intruder:CLAIM_BAN_TARGET_TITLE"));

        runLater();
        assertEquals(List.of("claim-7:Intruder by Owner"), claims.bans);
        assertNull(vehicles.get(intruder), "let go before the ban is written");
        assertFalse(rides.isCarried(intruder.getUniqueId()));
        assertFalse(bans.isPending(intruder.getUniqueId()));
        verify(messages).send(owner, Messages.CLAIM_BAN_DONE, "{target}", "Intruder");
        assertTrue(titles.contains("Intruder:CLAIM_BAN_BANNED_TITLE"));
        verify(messages, never()).send(owner, Messages.CLAIM_BAN_CANCELLED, "{target}", "Intruder");
        assertEquals(1, log.size());
    }

    @Test
    void lettingGoFirstCancelsTheBanAndPutsTheEffectsBack() {
        assertTrue(cuff());
        PowerToolContext drop = new PowerToolContext(owner, "allium:claim_handcuffs", null, mock(ItemStack.class), "main",
                PowerToolTrigger.DROP, null, null, null);
        assertTrue(actions.release(drop, action("release", Map.of())));
        assertFalse(bans.isPending(intruder.getUniqueId()));
        verify(messages).send(owner, Messages.CLAIM_BAN_CANCELLED, "{target}", "Intruder");
        verify(intruder).resetTitle();
        runLater();
        assertTrue(claims.bans.isEmpty(), "a cancelled countdown never bans");
    }

    @Test
    void leavingTheClaimCancelsAndReleasesAndAFailedBanSaysSo() {
        assertTrue(cuff());
        claims.inside = false;
        runLater();
        verify(messages).send(owner, Messages.CLAIM_BAN_LEFT, "{target}", "Intruder");
        assertFalse(rides.isCarried(intruder.getUniqueId()));
        assertTrue(claims.bans.isEmpty());

        claims.inside = true;
        claims.banWorks = false;
        assertTrue(cuff());
        runLater();
        verify(messages).send(owner, Messages.CLAIM_BAN_FAILED, "{target}", "Intruder");
        verify(messages).send(intruder, Messages.CLAIM_BAN_FAILED_TARGET);
        assertFalse(rides.isCarried(intruder.getUniqueId()));
    }

    @Test
    void theClaimCheckRefusesBeforeAnyoneIsRestrained() {
        Map<ClaimAccess.Refusal, Messages> expected = Map.of(
                ClaimAccess.Refusal.NOT_MANAGER, Messages.CLAIM_CUFFS_NOT_MANAGER,
                ClaimAccess.Refusal.TRUSTED, Messages.CLAIM_CUFFS_TRUSTED,
                ClaimAccess.Refusal.NOT_IN_CLAIM, Messages.CLAIM_CUFFS_NOT_IN_CLAIM,
                ClaimAccess.Refusal.ALREADY_BANNED, Messages.CLAIM_CUFFS_ALREADY_BANNED,
                ClaimAccess.Refusal.UNAVAILABLE, Messages.CLAIM_CUFFS_UNAVAILABLE);
        expected.forEach((refusal, message) -> {
            claims.answer = ClaimAccess.Check.denied(refusal);
            assertFalse(cuff(), refusal.name());
            verify(messages).send(owner, message, "{target}", "Intruder");
            assertFalse(rides.isCarried(intruder.getUniqueId()), refusal.name());
        });
        RideActions noClaims = new RideActions(rides, new ProtectionService(List.of(), null, m -> { }), messages::send,
                (p, t, s, i, st, o, r) -> { }, Entity::getName);
        claims.answer = ClaimAccess.Check.allowed("claim-7");
        assertFalse(noClaims.ride(hooked(), action("ride", Map.of("restrain", true, "claim", true))), "no GPExpansion: refused");
    }

    @Test
    void oneAtATimeAndOnlyWhenRestrainingNoOneElse() {
        assertTrue(cuff());
        Player second = player("Second");
        PowerToolContext another = new PowerToolContext(owner, "allium:claim_handcuffs", null, mock(ItemStack.class), "main",
                PowerToolTrigger.ON_FISH, null, second, null);
        assertFalse(actions.ride(another, action("ride", Map.of("restrain", true, "claim", true, "max-riders", 1))));
        verify(messages).send(owner, Messages.RIDE_FULL, "{max}", "1");
        assertFalse(bans.start(another, action("claim_ban", Map.of())), "no countdown without the restraint");
    }
}
