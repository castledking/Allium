package codes.castled.allium.mail;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.IgnoreRepository;
import codes.castled.allium.messaging.NotificationService;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.naming.PlayerNameService;
import codes.castled.allium.preference.PreferenceDefaults;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.preference.PreferenceRepository;
import codes.castled.allium.preference.PreferenceSessions;
import codes.castled.allium.preference.StoredPreferences;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MailFoliaTest {

    @TempDir Path tempDir;

    private final ArrayDeque<Runnable> workers = new ArrayDeque<>();
    private final ArrayDeque<Runnable> senderTasks = new ArrayDeque<>();
    private final ArrayDeque<Runnable> recipientTasks = new ArrayDeque<>();

    private PluginStart plugin;
    private Server server;
    private TaskScheduler scheduler;
    private MessageManager messages;
    private NotificationService notifications;
    private MailRepository repository;
    private IgnoreRepository ignores;
    private PlayerNameService names;
    private Player sender;
    private Player recipient;
    private UUID senderId;
    private UUID recipientId;
    private ServerThread senderOwner;
    private ServerThread recipientOwner;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        PluginManager pm = mock(PluginManager.class);
        scheduler = mock(TaskScheduler.class);
        messages = mock(MessageManager.class);
        notifications = mock(NotificationService.class);
        repository = mock(MailRepository.class);
        ignores = mock(IgnoreRepository.class);
        names = mock(PlayerNameService.class);
        ModuleManager modules = mock(ModuleManager.class);
        PreferencesModule prefs = mock(PreferencesModule.class);
        PreferenceRepository prefRepo = mock(PreferenceRepository.class);

        YamlConfiguration config = new YamlConfiguration();
        when(plugin.getName()).thenReturn("Allium");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getModuleManager()).thenReturn(modules);
        when(server.getPluginManager()).thenReturn(pm);

        when(modules.getModule("preferences")).thenReturn(prefs);
        when(prefs.isEnabled()).thenReturn(true);
        var prefDefaults = new PreferenceDefaults(true, true, true, 1.0);
        var effectivePrefs = StoredPreferences.empty(UUID.randomUUID()).effective(prefDefaults);
        when(prefs.defaults()).thenReturn(prefDefaults);
        when(prefs.effective(any())).thenReturn(Optional.of(effectivePrefs));
        when(prefs.acceptance(any(), eq(PreferenceKey.ACCEPT_MESSAGES))).thenReturn(PreferenceSessions.Acceptance.ACCEPTS);
        when(prefs.repository()).thenReturn(prefRepo);
        when(prefRepo.load(any())).thenReturn(Optional.of(StoredPreferences.empty(UUID.randomUUID())));
        when(ignores.load(any())).thenReturn(Set.of());

        senderId = UUID.randomUUID();
        recipientId = UUID.randomUUID();
        sender = mock(Player.class);
        recipient = mock(Player.class);
        PersistentDataContainer senderPdc = mock(PersistentDataContainer.class);
        when(sender.getUniqueId()).thenReturn(senderId);
        when(sender.getName()).thenReturn("Alice");
        when(sender.isOnline()).thenReturn(true);
        when(sender.hasPermission(anyString())).thenReturn(true);
        when(sender.getPersistentDataContainer()).thenReturn(senderPdc);
        when(senderPdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn(null);
        when(recipient.getUniqueId()).thenReturn(recipientId);
        when(recipient.getName()).thenReturn("Bob");
        when(recipient.isOnline()).thenReturn(true);
        when(server.getPlayer(senderId)).thenReturn(sender);
        when(server.getPlayer(recipientId)).thenReturn(recipient);

        senderOwner = mock(ServerThread.class);
        recipientOwner = mock(ServerThread.class);
        when(senderOwner.isServerThread()).thenReturn(true);
        when(recipientOwner.isServerThread()).thenReturn(false);

        when(scheduler.runOn(eq(senderOwner), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(c -> {
            senderTasks.add(c.getArgument(1));
            return mock(TaskHandle.class);
        });
        when(scheduler.runOn(eq(recipientOwner), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(c -> {
            recipientTasks.add(c.getArgument(1));
            return mock(TaskHandle.class);
        });
    }

    @Test
    void crossRegionSendTextRoutesSenderReplyAndRecipientNotificationToRespectiveRegions() throws Exception {
        when(names.resolve("Bob")).thenReturn(CompletableFuture.completedFuture(
                Optional.of(new NameCache.Entry(recipientId, "Bob", 1L))));
        when(repository.send(any(MailRecord.class), any(MailLimits.class), anyBoolean()))
                .thenReturn(MailRepository.SendResult.SAVED);

        MailService mail = new MailService(plugin, repository, ignores, names, notifications,
                workers::add, "srv-1", () -> MailLimits.DEFAULTS, tempDir);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, sender)).thenReturn(senderOwner);
            platform.when(() -> BukkitServerThread.entity(plugin, recipient)).thenReturn(recipientOwner);

            mail.sendText(sender, "Bob", "Hello from Region A!");
            // Drain sender & worker tasks until recipient notification is queued
            for (int i = 0; i < 10 && (!senderTasks.isEmpty() || !workers.isEmpty()); i++) {
                while (!senderTasks.isEmpty()) senderTasks.remove().run();
                while (!workers.isEmpty()) workers.remove().run();
            }

            verify(messages).send(eq(sender), eq(Messages.MAIL_SENT), eq("{player}"), eq("Bob"));
            verify(notifications, never()).send(eq(recipient), any());
            assertEquals(1, recipientTasks.size(), "notifyNew must hop onto recipient's region");

            when(senderOwner.isServerThread()).thenReturn(false);
            when(recipientOwner.isServerThread()).thenReturn(true);
            recipientTasks.remove().run();

            verify(notifications).send(eq(recipient), any(NotificationService.Notification.class));
            verify(scheduler, never()).runOnServer(any());
        }
    }

    @Test
    void joinRecoveryOnFoliaRunsOnPlayerRegionAndBypassesGlobalScheduler() throws Exception {
        when(repository.pendingFrom(senderId, "srv-1")).thenReturn(List.of());
        when(repository.claimingFor(senderId, "srv-1")).thenReturn(List.of());
        when(repository.unread(eq(senderId), anyLong())).thenReturn(2);

        MailService mail = new MailService(plugin, repository, ignores, names, notifications,
                workers::add, "srv-1", () -> MailLimits.DEFAULTS, tempDir);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, sender)).thenReturn(senderOwner);

            mail.onJoinEarly(new PlayerJoinEvent(sender, ""));
            mail.onJoin(new PlayerJoinEvent(sender, ""));
            assertTrue(mail.busy(sender));

            for (int i = 0; i < 10 && (!senderTasks.isEmpty() || !workers.isEmpty()); i++) {
                while (!workers.isEmpty()) workers.remove().run();
                while (!senderTasks.isEmpty()) senderTasks.remove().run();
            }

            assertFalse(mail.busy(sender));
            verify(notifications).send(eq(sender), any(NotificationService.Notification.class));
            verify(scheduler, never()).runOnServer(any());
        }
    }
}
