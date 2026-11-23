package codes.castled.allium.chat;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.module.ChatModule;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import codes.castled.allium.text.TextFormatter;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JoinQuitIdentityTest {

    @Test
    void savedNicknameAndGradientAppearInJoinAndQuitTemplatesAcrossReconnect(@TempDir Path dir) throws Exception {
        ConnectionSource db = () -> DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("chat.db"));
        ChatRepository repo = new ChatRepository(db, SqlDialect.SQLITE);
        repo.migrate();

        UUID aliceId = UUID.randomUUID();
        repo.nickname(aliceId, "&bStarlight");

        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                enable-chat-formatting: true
                join-and-quit-messages:
                  broadcast-scope: server
                  join:
                    enabled: true
                    format: '&e%name% joined'
                  quit:
                    enabled: true
                    format: '&e%name% left'
                """);

        PluginStart plugin = mockPlugin(config, List.of());
        ChatDecorations.Value gradientDecor = new ChatDecorations.Value("&6[VIP] ", "", "vip", List.of(0xFF0000, 0x0000FF));
        ChatService chat = new ChatService(plugin, repo, Runnable::run, ChatRelay.NONE, p -> gradientDecor);

        ChatModule chatModule = mock(ChatModule.class);
        when(chatModule.isEnabled()).thenReturn(true);
        when(chatModule.chat()).thenReturn(chat);
        ModuleManager modules = mock(ModuleManager.class);
        when(modules.getModule("chat")).thenReturn(chatModule);
        when(plugin.getModuleManager()).thenReturn(modules);

        // Verify event priority ordering: ChatService.joined (LOWEST) < JoinQuitMessages.onJoin (HIGH),
        // and JoinQuitMessages.onQuit (HIGH) < ChatService.quit (MONITOR).
        assertEquals(EventPriority.LOWEST,
                ChatService.class.getMethod("joined", PlayerJoinEvent.class).getAnnotation(EventHandler.class).priority());
        assertEquals(EventPriority.MONITOR,
                ChatService.class.getMethod("quit", PlayerQuitEvent.class).getAnnotation(EventHandler.class).priority());
        assertEquals(EventPriority.HIGH,
                JoinQuitMessages.class.getMethod("onJoin", PlayerJoinEvent.class).getAnnotation(EventHandler.class).priority());
        assertEquals(EventPriority.HIGH,
                JoinQuitMessages.class.getMethod("onQuit", PlayerQuitEvent.class).getAnnotation(EventHandler.class).priority());

        // CommandPanels-style LuckPerms metadata templates via permission nodes.
        JoinQuitMessages joinQuit = new JoinQuitMessages(plugin, (p, key) -> switch (key) {
            case "allium.join_message.vip" -> "&a+ %allium_gradientdisplayname% &7(%allium_nickname%) arrived";
            case "allium.quit_message.vip" -> "&c- %allium_gradientdisplayname% &7(%allium_nickname%) departed";
            default -> null;
        });

        // Connect #1 -> Quit #1 -> Reconnect #2
        for (int attempt = 1; attempt <= 2; attempt++) {
            Player alice = mockPlayer(aliceId, "Alice");
            var preLogin = new AsyncPlayerPreLoginEvent("Alice", InetAddress.getLoopbackAddress(), aliceId);
            chat.preLogin(preLogin);

            var joinEvent = new PlayerJoinEvent(alice, "Alice joined the game");
            chat.joined(joinEvent);
            assertTrue(chat.ready(aliceId), "identity should be ready on join from pre-login preload (attempt " + attempt + ")");
            joinQuit.onJoin(joinEvent);

            String joinMsg = joinEvent.getJoinMessage();
            assertNotNull(joinMsg);
            String plainJoin = TextFormatter.parse(joinMsg).toPlainText();
            assertTrue(plainJoin.contains("Starlight"), "join message must show saved nickname: " + plainJoin);
            assertTrue(plainJoin.contains("arrived"), "join message must use LuckPerms meta template: " + plainJoin);
            assertFalse(plainJoin.contains("Alice"), "raw username must not replace saved nickname: " + plainJoin);
            assertTrue(joinMsg.contains("§x"), "join message must include gradient hex codes: " + joinMsg);

            // Quit: HIGH runs before MONITOR drops the session.
            var quitEvent = new PlayerQuitEvent(alice, "Alice left the game");
            joinQuit.onQuit(quitEvent);
            chat.quit(quitEvent);

            String quitMsg = quitEvent.getQuitMessage();
            assertNotNull(quitMsg);
            String plainQuit = TextFormatter.parse(quitMsg).toPlainText();
            assertTrue(plainQuit.contains("Starlight"), "quit message must show saved nickname: " + plainQuit);
            assertTrue(plainQuit.contains("departed"), "quit message must use LuckPerms meta template: " + plainQuit);
            assertTrue(quitMsg.contains("§x"), "quit message must preserve gradient hex codes before quit cleanup: " + quitMsg);
            assertFalse(chat.ready(aliceId), "session must be cleaned up after MONITOR quit handler");
        }
    }

    @Test
    void deferredJoinMessageWaitsBoundedlyWhenPreLoginDidNotPopulateCache(@TempDir Path dir) throws Exception {
        ConnectionSource db = () -> DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("chat-deferred.db"));
        ChatRepository repo = new ChatRepository(db, SqlDialect.SQLITE);
        repo.migrate();

        UUID aliceId = UUID.randomUUID();
        repo.nickname(aliceId, "&dMoonbeam");

        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                enable-chat-formatting: true
                join-and-quit-messages:
                  broadcast-scope: server
                  join:
                    enabled: true
                    format: '+ %allium_gradientdisplayname% joined'
                """);

        Player viewer = mock(Player.class);
        when(viewer.isOnline()).thenReturn(true);
        when(viewer.canSee(any(Player.class))).thenReturn(true);
        List<String> sentToViewer = new ArrayList<>();
        Player.Spigot spigot = mock(Player.Spigot.class);
        when(viewer.spigot()).thenReturn(spigot);
        doAnswer(inv -> {
            sentToViewer.add(inv.getArgument(0).toString());
            return null;
        }).when(viewer).sendMessage(anyString());
        doAnswer(inv -> {
            net.md_5.bungee.api.chat.BaseComponent[] comps = inv.getArgument(0);
            StringBuilder sb = new StringBuilder();
            for (var c : comps) sb.append(c.toPlainText());
            sentToViewer.add(sb.toString());
            return null;
        }).when(spigot).sendMessage(any(net.md_5.bungee.api.chat.BaseComponent[].class));

        PluginStart plugin = mockPlugin(config, List.of(viewer));
        Queue<Runnable> ioQueue = new ArrayDeque<>();
        ChatDecorations.Value gradientDecor = new ChatDecorations.Value("", "", "default", List.of(0x00FF00, 0x0000FF));
        ChatService chat = new ChatService(plugin, repo, ioQueue::add, ChatRelay.NONE, p -> gradientDecor);

        ChatModule chatModule = mock(ChatModule.class);
        when(chatModule.isEnabled()).thenReturn(true);
        when(chatModule.chat()).thenReturn(chat);
        ModuleManager modules = mock(ModuleManager.class);
        when(modules.getModule("chat")).thenReturn(chatModule);
        when(plugin.getModuleManager()).thenReturn(modules);

        JoinQuitMessages joinQuit = new JoinQuitMessages(plugin, (p, k) -> null);

        // Skip preLogin so join starts async DB load.
        Player alice = mockPlayer(aliceId, "Alice");
        var joinEvent = new PlayerJoinEvent(alice, "Alice joined");
        chat.joined(joinEvent);
        assertFalse(chat.ready(aliceId));

        joinQuit.onJoin(joinEvent);
        assertNull(joinEvent.getJoinMessage(), "raw join message should be deferred until identity resolves");
        assertTrue(sentToViewer.isEmpty());

        // Complete the async DB load: join broadcast fires with saved nickname.
        while (!ioQueue.isEmpty()) ioQueue.poll().run();
        assertTrue(chat.ready(aliceId));
        assertFalse(sentToViewer.isEmpty());
        String deferredPlain = TextFormatter.parse(sentToViewer.get(0)).toPlainText();
        assertTrue(deferredPlain.contains("Moonbeam"), "deferred join broadcast must include loaded nickname: " + deferredPlain);
        assertTrue(sentToViewer.get(0).contains("§x"), "deferred join broadcast must include gradient codes: " + sentToViewer.get(0));
    }

    private static Player mockPlayer(UUID id, String name) {
        Player player = mock(Player.class);
        PermissionAttachmentInfo joinPerm = mock(PermissionAttachmentInfo.class);
        when(joinPerm.getPermission()).thenReturn("allium.join_message.vip");
        when(joinPerm.getValue()).thenReturn(true);
        PermissionAttachmentInfo quitPerm = mock(PermissionAttachmentInfo.class);
        when(quitPerm.getPermission()).thenReturn("allium.quit_message.vip");
        when(quitPerm.getValue()).thenReturn(true);

        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.getDisplayName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("allium.nick.color")).thenReturn(true);
        when(player.hasPermission("allium.nick.hex")).thenReturn(true);
        when(player.hasPermission("allium.nick.gradient")).thenReturn(true);
        when(player.hasPermission("allium.gradientname")).thenReturn(true);
        when(player.getEffectivePermissions()).thenReturn(Set.of(joinPerm, quitPerm));
        return player;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static PluginStart mockPlugin(YamlConfiguration config, List<Player> online) {
        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        PluginManager pm = mock(PluginManager.class);
        AlliumLogger logger = mock(AlliumLogger.class);
        TaskScheduler scheduler = new TaskScheduler() {
            @Override public boolean isServerThread() { return true; }
            @Override public TaskHandle runOnServer(Runnable task) { task.run(); return TaskHandle.NONE; }
            @Override public TaskHandle runOnServerLater(Runnable task, long delayMillis) { return TaskHandle.NONE; }
            @Override public TaskHandle repeatOnServer(BooleanSupplier step, long delayMillis, long periodMillis) { return TaskHandle.NONE; }
            @Override public TaskHandle runAsyncLater(Runnable task, long delayMillis) { return TaskHandle.NONE; }
            @Override public ThreadPoolExecutor boundedPool(String name, int threads, int queueCapacity) {
                throw new UnsupportedOperationException();
            }
        };
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pm);
        when(server.getOnlinePlayers()).thenReturn((Collection) online);
        when(plugin.logger()).thenReturn(logger);
        when(plugin.getScheduler()).thenReturn(scheduler);
        return plugin;
    }
}
