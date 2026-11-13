package codes.castled.allium.naming;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandRenderer;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandFamilyPolicy;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.mail.MailService;
import codes.castled.allium.module.MailModule;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayerNameServiceTest {
    private final PluginStart plugin = mock(PluginStart.class);
    private final Server server = mock(Server.class);
    private final PlayerNameRepository storage = mock(PlayerNameRepository.class);
    private final Executor io = mock(Executor.class);
    private final PlayerNameService names = new PlayerNameService(plugin, storage, 100, io);

    PlayerNameServiceTest() {
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getPlayerNames()).thenReturn(names);
    }

    @Test
    void completionIsMemoryOnlyAndRechecksActualTargetsForEachSenderAndRequest() {
        Player viewer = mock(Player.class);
        Player visible = online("Alice"), hidden = online("HiddenStaff"), vanished = online("VanishedStaff");
        MetadataValue meta = mock(MetadataValue.class);
        AtomicBoolean vanishedNow = new AtomicBoolean(true);
        when(meta.asBoolean()).thenAnswer(call -> vanishedNow.get());
        when(vanished.getMetadata("vanished")).thenReturn(List.of(meta));
        doReturn(List.of(visible, hidden, vanished)).when(server).getOnlinePlayers();
        when(viewer.canSee(any(Player.class))).thenAnswer(call -> call.getArgument(0) != hidden);
        names.cache().put(visible.getUniqueId(), "Alice", 1);
        names.cache().put(hidden.getUniqueId(), "FormerStaff", 2); // Stale alias for an actual hidden target.
        names.cache().put(vanished.getUniqueId(), "VanishedStaff", 3);
        names.cache().put(UUID.randomUUID(), "OfflineAlice", 4);
        names.cache().put(UUID.randomUUID(), "HiddenStaff", 5); // Old owner must not leak a reused hidden name.

        assertEquals(List.of("Alice", "OfflineAlice"), names.complete(viewer, "", 50));
        assertEquals(List.of("OfflineAlice"), names.complete(viewer, "OFF", 50));
        vanishedNow.set(false);
        assertEquals(List.of("VanishedStaff"), names.complete(viewer, "v", 50));
        vanishedNow.set(true);
        when(viewer.hasPermission("allium.vanish.see")).thenReturn(true);
        assertEquals(List.of("VanishedStaff"), names.complete(viewer, "v", 50));
        assertEquals(List.of(), names.complete(viewer, "hidden", 50), "see-vanished never bypasses canSee");
        CommandSender console = mock(CommandSender.class);
        assertEquals(List.of("HiddenStaff"), names.complete(console, "hidden", 50));
        assertEquals(List.of(), names.complete(viewer, "former", 50), "old aliases are never live target names");
        verifyNoInteractions(storage, io);
        verify(server, never()).getOfflinePlayers();
    }

    @Test
    void completionCapsResultsAndFiltersHiddenEntriesBeforeApplyingTheLimit() {
        Player viewer = mock(Player.class);
        when(viewer.canSee(any(Player.class))).thenReturn(false);
        Player hidden = online("Hidden");
        doReturn(List.of(hidden)).when(server).getOnlinePlayers();
        for (int i = 0; i < 80; i++) {
            names.cache().put(new UUID(0, i), "Offline" + i, i);
        }
        names.cache().put(hidden.getUniqueId(), "Hidden", 90);
        assertEquals(50, names.complete(viewer, "", Integer.MAX_VALUE).size());
        assertEquals(List.of("Offline79"), names.complete(viewer, "", 1));
        assertEquals(List.of(), names.complete(viewer, "", -1));
        verifyNoInteractions(storage, io);
    }

    @Test
    void rendererPassesSenderAndPermissionsWithoutSuggestingNamesInMessagesOrNotes() {
        Player viewer = mock(Player.class);
        Player hidden = online("SecretStaff");
        doReturn(List.of(hidden)).when(server).getOnlinePlayers();
        names.cache().put(UUID.randomUUID(), "OfflineAlice", 1);
        names.cache().put(hidden.getUniqueId(), "SecretStaff", 2);
        when(viewer.hasPermission(anyString())).thenAnswer(call ->
                List.of("allium.mail", "allium.mail.send").contains(call.getArgument(0)));
        BukkitCommandRenderer renderer = renderer();
        Command command = mock(Command.class);
        assertEquals(List.of("OfflineAlice"), renderer.onTabComplete(viewer, command, "mail", new String[]{"send", ""}));
        assertEquals(List.of(), renderer.onTabComplete(viewer, command, "allium:mail", new String[]{"gift", ""}));
        assertEquals(List.of(), renderer.onTabComplete(viewer, command, "mail", new String[]{"send", "OfflineAlice", ""}));
        assertEquals(List.of(), renderer.onTabComplete(viewer, command, "mail", new String[]{"gift", "OfflineAlice", "8", ""}));
        when(viewer.hasPermission("allium.mail.gift")).thenReturn(true);
        assertEquals(List.of("1", "8", "16", "32", "64"), renderer.onTabComplete(viewer, command, "mail", new String[]{"gift", "OfflineAlice", ""}));
        assertEquals(List.of(), renderer.onTabComplete(viewer, command, "mail", new String[]{"gift", "OfflineAlice", "a note", ""}));
        when(viewer.hasPermission("allium.mail")).thenReturn(false);
        assertEquals(List.of(), renderer.onTabComplete(viewer, command, "mail", new String[]{"send", ""}));
        verifyNoInteractions(storage, io);
        verify(server, never()).getOfflinePlayers();
    }

    @Test
    void executionUsesLiveTargetBeforeStorageAndNeverTrustsCachedOwnership() throws Exception {
        List<Runnable> pending = new ArrayList<>();
        PlayerNameService directory = new PlayerNameService(plugin, storage, 10, pending::add);
        UUID staleOwner = UUID.randomUUID(), actualOwner = UUID.randomUUID();
        directory.cache().put(staleOwner, "Alice", 1);
        when(storage.byName("Alice")).thenReturn(Optional.of(new NameCache.Entry(actualOwner, "Alice", 2)));
        var first = directory.resolve("Alice");
        assertFalse(first.isDone(), "a cached name still needs authoritative execution-time resolution");
        pending.remove(0).run();
        assertEquals(actualOwner, first.join().orElseThrow().uuid());
        var second = directory.resolve("Alice");
        pending.remove(0).run();
        assertEquals(actualOwner, second.join().orElseThrow().uuid());
        verify(storage, times(2)).byName("Alice");
        when(storage.byName("Alice")).thenReturn(Optional.empty());
        var removed = directory.resolve("Alice");
        pending.remove(0).run();
        assertTrue(removed.join().isEmpty(), "a cache hit cannot bypass a removed or renamed shared owner");
        assertTrue(directory.cache().byName("Alice").isEmpty(), "an authoritative miss retires the obsolete suggestion");
        Player online = online("Alice");
        when(server.getPlayerExact("Alice")).thenReturn(online);
        assertEquals(online.getUniqueId(), directory.resolve("Alice").join().orElseThrow().uuid());
        verify(storage, times(3)).byName("Alice");
        assertTrue(pending.isEmpty());
    }

    @Test
    void rejectedExecutionCompletesResolutionExceptionally() {
        PlayerNameService directory = new PlayerNameService(plugin, storage, 10, task -> {
            throw new java.util.concurrent.RejectedExecutionException("full");
        });
        var result = directory.resolve("Alice");
        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(java.util.concurrent.RejectedExecutionException.class,
                assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
        verifyNoInteractions(storage);
    }

    @Test
    void rendererReadsOnlyTheRequestingPlayersLoadedMailboxSnapshot() {
        Player viewer = mock(Player.class);
        when(viewer.hasPermission("allium.mail")).thenReturn(true);
        ModuleManager modules = mock(ModuleManager.class);
        MailModule module = mock(MailModule.class);
        MailService mail = mock(MailService.class);
        when(plugin.getModuleManager()).thenReturn(modules);
        when(modules.getModule("mail")).thenReturn(module);
        when(module.isEnabled()).thenReturn(true);
        when(module.mail()).thenReturn(mail);
        when(mail.completeMailbox(viewer, true)).thenReturn(List.of("abc12345"));
        when(mail.completeMailbox(viewer, false)).thenReturn(List.of("def12345"));
        Command command = mock(Command.class);
        BukkitCommandRenderer renderer = renderer();
        assertEquals(List.of("abc12345", "all"), renderer.onTabComplete(viewer, command, "allium:mail", new String[]{"claim", ""}));
        assertEquals(List.of("def12345"), renderer.onTabComplete(viewer, command, "mail", new String[]{"delete", ""}));
        when(mail.completeMailbox(viewer, true)).thenReturn(List.of());
        assertEquals(List.of(), renderer.onTabComplete(viewer, command, "mail", new String[]{"claim", ""}));
        when(viewer.hasPermission("allium.mail")).thenReturn(false);
        clearInvocations(mail);
        assertEquals(List.of(), renderer.onTabComplete(viewer, command, "mail", new String[]{"delete", ""}));
        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission("allium.mail")).thenReturn(true);
        assertEquals(List.of(), renderer.onTabComplete(console, command, "mail", new String[]{"claim", ""}));
        verifyNoInteractions(mail, storage, io);
    }

    private BukkitCommandRenderer renderer() {
        SpecCommandHandler handler = mock(SpecCommandHandler.class);
        when(handler.spec()).thenReturn(CoreCommandSpecs.MAIL);
        return new BukkitCommandRenderer(plugin, new CommandFamilyPolicy(), handler);
    }

    private static Player online(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }
}
