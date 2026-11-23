package codes.castled.allium.economy;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.mail.PlayerDataFiles;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChequeFoliaTest {

    @TempDir Path tempDir;

    private final ArrayDeque<Runnable> workers = new ArrayDeque<>();
    private final ArrayDeque<Runnable> adminTasks = new ArrayDeque<>();
    private final ArrayDeque<Runnable> targetTasks = new ArrayDeque<>();
    private final ArrayDeque<Runnable> targetRetires = new ArrayDeque<>();

    private PluginStart plugin;
    private Server server;
    private TaskScheduler scheduler;
    private MessageManager messages;
    private EconomyRuntime runtime;
    private ChequeRepository repository;
    private Player admin;
    private Player target;
    private ServerThread adminOwner;
    private ServerThread targetOwner;

    @BeforeEach
    void setUp() {
        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        scheduler = mock(TaskScheduler.class);
        messages = mock(MessageManager.class);
        runtime = mock(EconomyRuntime.class);
        repository = mock(ChequeRepository.class);

        when(plugin.getName()).thenReturn("Allium");
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getModuleManager()).thenReturn(mock(ModuleManager.class));
        when(runtime.ready()).thenReturn(true);
        when(runtime.display()).thenReturn(EconomyDisplay.read(new org.bukkit.configuration.file.YamlConfiguration()));

        UUID adminId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        admin = mock(Player.class);
        target = mock(Player.class);
        PlayerInventory targetInv = mock(PlayerInventory.class);
        when(admin.getUniqueId()).thenReturn(adminId);
        when(admin.getName()).thenReturn("Admin");
        when(admin.isOnline()).thenReturn(true);
        when(admin.hasPermission(anyString())).thenReturn(true);
        when(admin.canSee(target)).thenReturn(true);

        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn("Bob");
        when(target.isOnline()).thenReturn(true);
        when(target.getInventory()).thenReturn(targetInv);
        when(targetInv.firstEmpty()).thenReturn(0);
        when(targetInv.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());

        when(server.getPlayer(adminId)).thenReturn(admin);
        when(server.getPlayer(targetId)).thenReturn(target);
        when(server.getPlayer("Bob")).thenReturn(target);

        adminOwner = mock(ServerThread.class);
        targetOwner = mock(ServerThread.class);
        when(adminOwner.isServerThread()).thenReturn(true);
        when(targetOwner.isServerThread()).thenReturn(false);

        when(scheduler.runOn(eq(adminOwner), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(c -> {
            adminTasks.add(c.getArgument(1));
            return mock(TaskHandle.class);
        });
        when(scheduler.runOn(eq(targetOwner), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(c -> {
            targetTasks.add(c.getArgument(1));
            targetRetires.add(c.getArgument(2));
            return mock(TaskHandle.class);
        });
    }

    @Test
    void crossRegionAdminChequeRoutesInventoryToTargetRegionAndFeedbackToAdminRegion() throws Exception {
        when(repository.insertAdminIssued(any())).thenReturn(true);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS);
             var files = mockStatic(PlayerDataFiles.class)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, admin)).thenReturn(adminOwner);
            platform.when(() -> BukkitServerThread.entity(plugin, target)).thenReturn(targetOwner);
            files.when(() -> PlayerDataFiles.directory(server)).thenReturn(tempDir);

            ChequeService cheques = spy(new ChequeService(plugin, runtime, repository, workers::add));
            doReturn(mock(ItemStack.class)).when(cheques).createChequeItem(any(), any(), anyString(), any());

            // Admin in region A issues cheque to Bob in region B
            cheques.dispatch(admin, new String[]{"250", "Bob"});
            assertEquals(1, targetTasks.size(), "admin cheque must inspect target inventory on target's region");
            assertTrue(workers.isEmpty());

            // Target region validates inventory and queues SQL insert
            when(adminOwner.isServerThread()).thenReturn(false);
            when(targetOwner.isServerThread()).thenReturn(true);
            targetTasks.remove().run();
            assertEquals(1, workers.size());
            workers.remove().run();
            assertEquals(1, targetTasks.size());

            // Target region delivers cheque and notifies Bob; Admin feedback hops to admin's region
            targetTasks.remove().run();
            verify(messages).send(eq(target), eq(Messages.ECO_CHEQUE_RECEIVED), eq("{amount}"), anyString(), eq("{signer}"), eq("Admin"));
            verify(messages, never()).send(eq(admin), eq(Messages.ECO_CHEQUE_GIVEN), any(String[].class));
            assertEquals(1, adminTasks.size());

            when(adminOwner.isServerThread()).thenReturn(true);
            when(targetOwner.isServerThread()).thenReturn(false);
            adminTasks.remove().run();
            verify(messages).send(eq(admin), eq(Messages.ECO_CHEQUE_GIVEN), eq("{amount}"), anyString(), eq("{player}"), eq("Bob"));
            assertFalse(cheques.busy(target));
            verify(scheduler, never()).runOnServer(any());
        }
    }

    @Test
    void crossRegionAdminChequeVoidsNoteWhenTargetRetiresBeforeDelivery() throws Exception {
        when(repository.insertAdminIssued(any())).thenReturn(true);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS);
             var files = mockStatic(PlayerDataFiles.class)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, admin)).thenReturn(adminOwner);
            platform.when(() -> BukkitServerThread.entity(plugin, target)).thenReturn(targetOwner);
            files.when(() -> PlayerDataFiles.directory(server)).thenReturn(tempDir);

            ChequeService cheques = spy(new ChequeService(plugin, runtime, repository, workers::add));
            doReturn(mock(ItemStack.class)).when(cheques).createChequeItem(any(), any(), anyString(), any());
            cheques.dispatch(admin, new String[]{"100", "Bob"});

            when(adminOwner.isServerThread()).thenReturn(false);
            when(targetOwner.isServerThread()).thenReturn(true);
            targetTasks.remove().run();
            workers.remove().run();

            // Target retires before delivery runs
            targetTasks.clear();
            targetRetires.removeLast().run();
            assertFalse(cheques.busy(target));
            assertEquals(1, workers.size(), "retired target must void issued admin cheque");
            workers.remove().run();
            verify(repository).markVoided(any(UUID.class));

            when(adminOwner.isServerThread()).thenReturn(true);
            adminTasks.remove().run();
            verify(messages).send(eq(admin), eq(Messages.PLAYER_NOT_FOUND), eq("{player}"), eq("Bob"));
            verify(scheduler, never()).runOnServer(any());
        }
    }
}
