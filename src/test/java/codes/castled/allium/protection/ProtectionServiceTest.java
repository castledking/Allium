package codes.castled.allium.protection;

import codes.castled.allium.protection.griefprevention.GriefPreventionProtectionHook;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class ProtectionServiceTest {

    private final Player player = mock(Player.class);
    private final Zombie target = mock(Zombie.class);
    private final List<String> warnings = new ArrayList<>();

    @AfterEach
    void clearGriefPrevention() {
        GriefPrevention.instance = null;
    }

    @Test
    void hooksRunFirstThenTheCancellableCheckEventCanVeto() {
        PluginManager pm = mock(PluginManager.class);
        List<AlliumProtectionCheckEvent> fired = new ArrayList<>();
        doAnswer(inv -> {
            AlliumProtectionCheckEvent event = inv.getArgument(0);
            fired.add(event);
            event.setCancelled(event.getAction().equals("allium:denied"));
            return null;
        }).when(pm).callEvent(any(AlliumProtectionCheckEvent.class));
        ProtectionService service = new ProtectionService(List.of(), pm, warnings::add);
        Event cause = mock(Event.class);

        assertTrue(service.checkEntityInteraction(player, target, "allium:mob_disarmer", cause).allowed());
        ProtectionDecision denied = service.checkEntityInteraction(player, target, "allium:denied", cause);
        assertFalse(denied.allowed());
        assertEquals(ProtectionService.EVENT_SOURCE, denied.source());
        assertSame(player, fired.get(0).getPlayer());
        assertSame(target, fired.get(0).getTarget());
        assertSame(cause, fired.get(0).getCause());

        ProtectionHook deny = new ProtectionBootstrapHook(ProtectionDecision.deny("Claims", "no"));
        ProtectionService withHook = new ProtectionService(List.of(deny), pm, warnings::add);
        assertEquals("Claims", withHook.checkEntityInteraction(player, target, "allium:mob_disarmer", cause).source());
        assertEquals(2, fired.size(), "a hook denial short-circuits the event");
    }

    @Test
    void throwingHooksDenyWithOneDiagnosticAndUnloadableHooksFailClosed() {
        ProtectionHook broken = new ProtectionHook() {
            public String name() { return "Broken"; }
            public ProtectionDecision checkEntityInteraction(Player p, org.bukkit.entity.Entity t, Event c) {
                throw new IllegalStateException("boom");
            }
        };
        ProtectionService service = new ProtectionService(List.of(broken), null, warnings::add);
        assertFalse(service.checkEntityInteraction(player, target, "allium:mob_disarmer", null).allowed());
        assertFalse(service.checkEntityInteraction(player, target, "allium:mob_disarmer", null).allowed());
        assertEquals(1, warnings.size());

        Plugin gp = mock(Plugin.class);
        when(gp.getName()).thenReturn("GriefPrevention3D");
        when(gp.getDescription()).thenReturn(new PluginDescriptionFile("GriefPrevention3D", "18.4.5", "x.Main"));
        List<String> info = new ArrayList<>();
        ProtectionHook fallback = ProtectionHookBootstrap.load("missing.Hook", "GriefPrevention", gp, info::add, warnings::add);
        assertFalse(fallback.checkEntityInteraction(player, target, null).allowed());
        assertTrue(warnings.get(1).contains("could not load"));

        PluginManager pm = mock(PluginManager.class);
        when(pm.getPlugin("GriefPrevention3D")).thenReturn(gp);
        when(gp.isEnabled()).thenReturn(true);
        List<ProtectionHook> loaded = ProtectionHookBootstrap.loadAll(pm, info::add, warnings::add);
        assertEquals(1, loaded.size());
        assertInstanceOf(GriefPreventionProtectionHook.class, loaded.get(0));
        assertTrue(ProtectionHookBootstrap.loadAll(mock(PluginManager.class), info::add, warnings::add).isEmpty());
    }

    @Test
    void griefPreventionRequiresContainerTrustInsideClaimsOnly() {
        GriefPrevention gp = mock(GriefPrevention.class);
        DataStore dataStore = mock(DataStore.class);
        gp.dataStore = dataStore;
        GriefPrevention.instance = gp;
        Location inClaim = mock(Location.class);
        Location wild = mock(Location.class);
        Claim claim = mock(Claim.class);
        when(dataStore.getClaimAt(eq(inClaim), eq(false), isNull())).thenReturn(claim);
        when(dataStore.getClaimAt(eq(wild), eq(false), isNull())).thenReturn(null);
        Event cause = mock(Event.class);
        GriefPreventionProtectionHook hook = new GriefPreventionProtectionHook();

        when(target.getLocation()).thenReturn(wild);
        assertTrue(hook.checkEntityInteraction(player, target, cause).allowed());

        when(target.getLocation()).thenReturn(inClaim);
        when(claim.checkPermission(player, ClaimPermission.Inventory, cause)).thenReturn(null);
        assertTrue(hook.checkEntityInteraction(player, target, cause).allowed());

        Supplier<String> denial = () -> "You don't have Container trust";
        when(claim.checkPermission(player, ClaimPermission.Inventory, cause)).thenReturn(denial);
        ProtectionDecision decision = hook.checkEntityInteraction(player, target, cause);
        assertFalse(decision.allowed());
        assertEquals("GriefPrevention", decision.source());
        assertEquals("You don't have Container trust", decision.message());

        GriefPrevention.instance = null;
        assertThrows(IllegalStateException.class, () -> hook.checkEntityInteraction(player, target, cause));
        ProtectionService service = new ProtectionService(List.of(hook), null, warnings::add);
        assertFalse(service.checkEntityInteraction(player, target, "allium:mob_disarmer", cause).allowed());
    }

    private record ProtectionBootstrapHook(ProtectionDecision decision) implements ProtectionHook {
        public String name() { return decision.source(); }
        public ProtectionDecision checkEntityInteraction(Player p, org.bukkit.entity.Entity t, Event c) { return decision; }
    }
}
