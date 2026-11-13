package codes.castled.allium.mail;

import codes.castled.allium.PluginStart;
import codes.castled.allium.item.MockItemStacks;
import codes.castled.allium.messaging.IgnoreRepository;
import codes.castled.allium.messaging.NotificationService;
import codes.castled.allium.naming.PlayerNameService;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MailRecoveryTest {
    @TempDir Path worldDir;
    private PluginStart plugin;
    private Server server;
    private Player current;
    private MailRepository repository;
    private UUID id;
    private Map<NamespacedKey,Object> pdc;
    @BeforeEach void setup() {
        plugin = mock(PluginStart.class, RETURNS_DEEP_STUBS);
        server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
        when(plugin.getName()).thenReturn("Allium");
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(scheduler.isServerThread()).thenReturn(true);
        when(scheduler.serverExecutor()).thenReturn(Runnable::run);
        when(scheduler.runOnServer(any())).thenAnswer(c -> { ((Runnable)c.getArgument(0)).run(); return TaskHandle.NONE; });
        id = UUID.randomUUID();
        current = mock(Player.class);
        when(current.getUniqueId()).thenReturn(id);
        when(current.getName()).thenReturn("Recipient");
        when(current.isOnline()).thenReturn(true);
        when(server.getPlayer(id)).thenAnswer(c -> current);
        World world = mock(World.class);
        when(world.getWorldFolder()).thenReturn(worldDir.toFile());
        when(server.getWorlds()).thenReturn(List.of(world));
        pdc = new HashMap<>();
        var container = MockItemStacks.container(pdc);
        when(current.getPersistentDataContainer()).thenReturn(container);
        repository = mock(MailRepository.class);
    }
    private MailService service(Executor io) {
        return new MailService(plugin,repository,mock(IgnoreRepository.class),mock(PlayerNameService.class),
                mock(NotificationService.class),io,"server-a",()->MailLimits.DEFAULTS,worldDir.resolve("playerdata"));
    }
    @Test void sameSessionRecoveryDoesNotTrustAnUnwrittenLiveReceipt() throws Exception {
        UUID mail=UUID.randomUUID(), token=UUID.randomUUID();
        pdc.put(new NamespacedKey(plugin,"mail_receipts"),MailReceipts.format(List.of(new MailReceipts.Claimed(mail,token,List.of(7)))));
        MailService mailService=service(Runnable::run);
        var joined=MailService.class.getDeclaredMethod("joined",Player.class);
        joined.setAccessible(true);
        joined.invoke(mailService,current);
        verifyNoInteractions(repository);
        verify(current).kickPlayer(contains("inventory needs mail recovery"));
        assertNotNull(pdc.get(new NamespacedKey(plugin,"mail_receipts")),"unconfirmed receipt is retained");
        verify(current,never()).saveData();
        var lockField = MailService.class.getDeclaredField("locks");
        lockField.setAccessible(true);
        assertTrue(((MailInventoryLock) lockField.get(mailService)).locked(current),
                "a cancelled kick must not expose unverified inventory");
        var quarantined = MailService.class.getDeclaredField("quarantined");
        quarantined.setAccessible(true);
        assertTrue(((Set<?>) quarantined.get(mailService)).contains(current),
                "same-session background recovery must not advance unverified receipts");
    }
    @Test void rejectedPostSaveVerificationKeepsInventoryLockedAndRequiresAReconnect() throws Exception {
        MailService service=service(task->{ throw new RejectedExecutionException("full"); });
        var lockField=MailService.class.getDeclaredField("locks"); lockField.setAccessible(true);
        MailInventoryLock locks=(MailInventoryLock)lockField.get(service); locks.lock(current);
        var submit=MailService.class.getDeclaredMethod("submitCritical",Player.class,UUID.class,Runnable.class,boolean.class);
        submit.setAccessible(true);
        submit.invoke(service,current,UUID.randomUUID(),(Runnable)()->fail("must not run"),true);
        assertTrue(locks.locked(current),"unverified delivered items cannot be exposed to normal interactions");
        verify(current).kickPlayer(contains("inventory needs mail recovery"));
        verifyNoInteractions(repository);
    }
    @Test void anOldPlayerObjectCannotReleaseANewerConnectionsLock() {
        MailInventoryLock locks=new MailInventoryLock();
        Player old=current;
        Player next=mock(Player.class);
        when(next.getUniqueId()).thenReturn(id);
        locks.lock(old); locks.lock(next);
        locks.unlock(old);
        assertTrue(locks.locked(next));
        assertFalse(locks.locked(old));
    }
}
