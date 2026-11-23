package codes.castled.allium.creative.manage;

import codes.castled.allium.PluginStart;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.actionlog.Action;
import net.luckperms.api.actionlog.ActionLogger;
import net.luckperms.api.cacheddata.CachedDataManager;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.group.GroupManager;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.node.Node;
import net.luckperms.api.util.Tristate;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreativeManageTest {

    @Test
    void flagsHaveUniqueKeysNodesAndOneIconAndPaneSlotEachClearOfTheControls() {
        assertEquals(CreativeFlag.values().length, new HashSet<>(CreativeFlag.keys()).size());
        assertSame(CreativeFlag.FILLED_CONTAINERS, CreativeFlag.byKey(" Filled-Containers "));
        assertNull(CreativeFlag.byKey("fly"));
        assertEquals("allium.gamemode.creative.place", CreativeFlag.PLACE.node());
        assertFalse(CreativeFlag.INVENTORY.coveredByBypass());
        assertFalse(CreativeFlag.BYPASS.coveredByBypass());
        assertTrue(CreativeFlag.PICKBLOCK.coveredByBypass());

        assertEquals(CreativeFlag.values().length, CreativeManageMenus.FLAG_SLOTS.length);
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < CreativeManageMenus.FLAG_SLOTS.length; i++) {
            int icon = CreativeManageMenus.FLAG_SLOTS[i];
            assertTrue(used.add(icon) && used.add(icon + 9), "slots overlap at " + icon);
            assertTrue(icon + 9 < 54);
            assertSame(CreativeFlag.values()[i], CreativeManageMenus.flagAt(icon));
            assertSame(CreativeFlag.values()[i], CreativeManageMenus.flagAt(icon + 9), "the state pane toggles too");
        }
        for (int control : List.of(45, 47, 51, 53)) {
            assertFalse(used.contains(control), "control slot " + control + " is free");
            assertNull(CreativeManageMenus.flagAt(control));
        }
    }

    @Test
    void luckPermsTellsSetHereFromInheritedBypassAndUnsetAndIgnoresContextualNodes() throws Exception {
        LuckPerms api = mock(LuckPerms.class);
        GroupManager groups = mock(GroupManager.class);
        when(api.getGroupManager()).thenReturn(groups);
        CachedPermissionData data = mock(CachedPermissionData.class);
        Group builder = group(groups, "builder", data);
        when(data.checkPermission(anyString())).thenReturn(Tristate.UNDEFINED);
        when(data.checkPermission(CreativeFlag.PLACE.node())).thenReturn(Tristate.TRUE);
        when(data.checkPermission(CreativeFlag.BREAK.node())).thenReturn(Tristate.TRUE);
        when(data.checkPermission(CreativeFlag.DROP.node())).thenReturn(Tristate.FALSE);
        when(data.checkPermission(CreativeFlag.BYPASS.node())).thenReturn(Tristate.TRUE);
        List<Node> nodes = List.of(node(CreativeFlag.PLACE.node(), true, true), node(CreativeFlag.DROP.node(), false, true),
                node(CreativeFlag.SPAWN.node(), true, false));
        when(builder.getNodes()).thenReturn(nodes);

        LuckPermsBackend backend = new LuckPermsBackend(api);
        assertEquals("builder", backend.group("Builder"));
        assertNull(backend.group("nobody"));
        Map<CreativeFlag, FlagValue> values = backend.read(CreativeHolder.group("Builder")).get();

        assertEquals(new FlagValue(true, true, true, "set here"), values.get(CreativeFlag.PLACE));
        assertEquals(new FlagValue(null, true, true, "inherited"), values.get(CreativeFlag.BREAK));
        assertEquals(new FlagValue(false, false, true, "set here"), values.get(CreativeFlag.DROP));
        assertEquals(new FlagValue(null, true, true, "bypass"), values.get(CreativeFlag.SPAWN), "a world-only node is not set here");
        assertEquals(new FlagValue(null, false, true, "not set"), values.get(CreativeFlag.INVENTORY), "bypass leaves inventory out");
        assertTrue(values.get(CreativeFlag.PLACE).givenHere());
        assertFalse(values.get(CreativeFlag.BREAK).givenHere());
        assertEquals("&aallowed &7(set here)", values.get(CreativeFlag.PLACE).describeEffective());

        assertInstanceOf(IllegalArgumentException.class,
                backend.read(CreativeHolder.group("nobody")).handle((v, e) -> e).get());
    }

    @Test
    void removingAGroupFlagClearsOnlyItsGlobalNodeAndLogsLikeLp() throws Exception {
        LuckPerms api = mock(LuckPerms.class);
        GroupManager groups = mock(GroupManager.class);
        when(api.getGroupManager()).thenReturn(groups);
        Group builder = group(groups, "builder", mock(CachedPermissionData.class));
        NodeMap data = mock(NodeMap.class);
        when(builder.data()).thenReturn(data);
        when(groups.modifyGroup(eq("builder"), any())).thenAnswer(call -> {
            Consumer<Group> change = call.getArgument(1);
            change.accept(builder);
            return CompletableFuture.completedFuture(null);
        });
        ActionLogger logger = mock(ActionLogger.class);
        Action.Builder action = mock(Action.Builder.class, RETURNS_SELF);
        when(api.getActionLogger()).thenReturn(logger);
        when(logger.actionBuilder()).thenReturn(action);
        when(action.build()).thenReturn(mock(Action.class));
        when(logger.submit(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(api.getMessagingService()).thenReturn(Optional.empty());

        new LuckPermsBackend(api).write(CreativeHolder.group("Builder"), CreativeFlag.PLACE, false,
                new PermissionBackend.Actor(null, "CONSOLE")).get();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Predicate<Node>> cleared = ArgumentCaptor.forClass(Predicate.class);
        verify(data).clear(cleared.capture());
        assertTrue(cleared.getValue().test(node(CreativeFlag.PLACE.node(), true, true)));
        assertFalse(cleared.getValue().test(node(CreativeFlag.PLACE.node(), true, false)), "a world-specific node is kept");
        assertFalse(cleared.getValue().test(node(CreativeFlag.BREAK.node(), true, true)));
        verify(data, never()).add(any(Node.class));
        verify(action).description("permission unset allium.gamemode.creative.place");
        verify(action).targetName("builder");
        verify(action, never()).target(any());
        verify(logger).submit(any());
    }

    @Test
    void withoutAPermissionsPluginPlayersReadAsBukkitSeesThemAndNothingIsWritable() {
        Fixture fixture = new Fixture();
        CreativeAccess access = new CreativeAccess(fixture.plugin);
        assertInstanceOf(CreativeAccess.ReadOnly.class, access.backend());
        assertTrue(access.backend().groups().isEmpty());

        UUID offlineId = UUID.randomUUID();
        OfflinePlayer offline = mock(OfflinePlayer.class);
        when(offline.isOp()).thenReturn(true);
        when(fixture.server.getOfflinePlayer(offlineId)).thenReturn(offline);
        Map<CreativeFlag, FlagValue> offlineValues = read(access, CreativeHolder.player(offlineId, "Away"));
        assertEquals(new FlagValue(null, true, false, "op default"), offlineValues.get(CreativeFlag.PLACE), "every node defaults to op");

        UUID onlineId = UUID.randomUUID();
        Player online = mock(Player.class);
        when(fixture.server.getPlayer(onlineId)).thenReturn(online);
        when(online.hasPermission(CreativeFlag.BREAK.node())).thenReturn(true);
        Map<CreativeFlag, FlagValue> onlineValues = read(access, CreativeHolder.player(onlineId, "Here"));
        assertEquals(new FlagValue(null, true, false, "online"), onlineValues.get(CreativeFlag.BREAK));
        assertEquals(new FlagValue(null, false, false, "online"), onlineValues.get(CreativeFlag.PLACE));
        assertTrue(onlineValues.get(CreativeFlag.BREAK).givenHere(), "without set nodes, what applies is shown");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        access.write(mock(ConsoleCommandSender.class), CreativeHolder.player(onlineId, "Here"), CreativeFlag.PLACE, true, failure::set);
        assertInstanceOf(UnsupportedOperationException.class, failure.get());
        AtomicReference<Throwable> groupFailure = new AtomicReference<>();
        access.read(mock(ConsoleCommandSender.class), CreativeHolder.group("builder"), (values, error) -> groupFailure.set(error));
        assertInstanceOf(IllegalArgumentException.class, groupFailure.get());
    }

    // -- Helpers -----------------------------------------------------------------------------------

    static final class Fixture {
        final PluginStart plugin = mock(PluginStart.class);
        final Server server = mock(Server.class);
        final PluginManager plugins = mock(PluginManager.class);
        final TaskScheduler scheduler = mock(TaskScheduler.class);

        Fixture() {
            when(plugin.getServer()).thenReturn(server);
            when(server.getPluginManager()).thenReturn(plugins);
            when(plugin.getScheduler()).thenReturn(scheduler);
            when(scheduler.runOnServer(any())).thenAnswer(call -> {
                ((Runnable) call.getArgument(0)).run();
                return TaskHandle.NONE;
            });
        }
    }

    private static Map<CreativeFlag, FlagValue> read(CreativeAccess access, CreativeHolder holder) {
        AtomicReference<Map<CreativeFlag, FlagValue>> out = new AtomicReference<>();
        access.read(mock(ConsoleCommandSender.class), holder, (values, error) -> {
            if (error != null) {
                throw new AssertionError(error);
            }
            out.set(values);
        });
        return out.get();
    }

    private static Group group(GroupManager groups, String name, CachedPermissionData data) {
        Group group = mock(Group.class);
        when(group.getName()).thenReturn(name);
        CachedDataManager cached = mock(CachedDataManager.class);
        when(group.getCachedData()).thenReturn(cached);
        when(cached.getPermissionData()).thenReturn(data);
        when(groups.getGroup(name)).thenReturn(group);
        when(groups.getLoadedGroups()).thenReturn(Set.of(group));
        return group;
    }

    private static Node node(String key, boolean value, boolean global) {
        Node node = mock(Node.class);
        when(node.getKey()).thenReturn(key);
        when(node.getValue()).thenReturn(value);
        ImmutableContextSet contexts = mock(ImmutableContextSet.class);
        when(contexts.isEmpty()).thenReturn(global);
        when(node.getContexts()).thenReturn(contexts);
        return node;
    }
}
