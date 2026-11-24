package codes.castled.allium.economy;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.mail.PlayerDataReceiptProof;
import codes.castled.allium.module.EconomyModule;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaidActionServiceTest {

    @TempDir Path temp;

    private final UUID account = UUID.randomUUID();
    private final ArrayDeque<Runnable> worker = new ArrayDeque<>();
    private final ArrayDeque<Runnable> main = new ArrayDeque<>();
    private final Map<NamespacedKey, String> pdcMap = new HashMap<>();

    private EconomyEngine engine;
    private PluginStart plugin;
    private Server server;
    private TaskScheduler scheduler;
    private Player player;
    private PaidActionService service;

    @BeforeEach
    void setUp() throws Exception {
        engine = new EconomyEngine(() -> DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("eco.db")),
                EconomySettings.defaults(), (c, a, b) -> true);
        assertInstanceOf(EconomyResult.Committed.class, engine.execute(engine.request(
                UUID.randomUUID(), "test", "open", EconomyRequest.Kind.OPEN, "test",
                account, null, new Money(10_000))));

        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        scheduler = mock(TaskScheduler.class);
        ModuleManager modules = mock(ModuleManager.class);
        EconomyModule ecoModule = mock(EconomyModule.class);
        EconomyRuntime runtime = mock(EconomyRuntime.class);
        AlliumLogger logger = mock(AlliumLogger.class);

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getModuleManager()).thenReturn(modules);
        when(plugin.logger()).thenReturn(logger);
        when(modules.getModule("economy")).thenReturn(ecoModule);
        when(ecoModule.isEnabled()).thenReturn(true);
        when(ecoModule.runtime()).thenReturn(runtime);
        when(runtime.ready()).thenReturn(true);
        when(runtime.reservations()).thenReturn(engine.reservations());

        when(scheduler.runOnServer(any(Runnable.class))).thenAnswer(call -> {
            main.add(call.getArgument(0));
            return TaskHandle.NONE;
        });

        player = mock(Player.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(player.getUniqueId()).thenReturn(account);
        when(player.getName()).thenReturn("Alice");
        when(player.isOnline()).thenReturn(true);
        when(player.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING)))
                .thenAnswer(call -> pdcMap.get(call.getArgument(0)));
        doAnswer(call -> {
            pdcMap.put(call.getArgument(0), call.getArgument(2));
            return null;
        }).when(pdc).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), anyString());
        doAnswer(call -> {
            pdcMap.remove(call.getArgument(0));
            return null;
        }).when(pdc).remove(any(NamespacedKey.class));
        when(server.getPlayer(account)).thenReturn(player);

        service = new PaidActionService(plugin, worker::add, temp);
    }

    @Test
    void successfulDelayedActionCapturesOnceAndIgnoresDuplicateCallbacks() {
        UUID workflow = UUID.randomUUID();
        AtomicReference<Consumer<PaidActionOutcome>> completeRef = new AtomicReference<>();
        AtomicReference<PaidActionResult> resultRef = new AtomicReference<>();

        service.execute(new PaidActionService.LiveRequest(
                EconomyReservations.Kind.SIGN, workflow, player, new Money(350),
                EconomyReservations.fingerprint("SIGN", workflow.toString()),
                "Test action", () -> true,
                (valid, complete) -> {
                    assertTrue(valid.getAsBoolean());
                    completeRef.set(complete);
                },
                resultRef::set, true
        ));

        worker.remove().run(); // reserve
        main.remove().run();   // deliver
        assertTrue(service.busy(player));
        assertBalance(10_000, 350);

        completeRef.get().accept(PaidActionOutcome.delivered("done"));
        completeRef.get().accept(PaidActionOutcome.refused("late duplicate ignored"));
        assertEquals(1, worker.size());
        worker.remove().run(); // capture
        main.remove().run();   // finish callback

        assertFalse(service.busy(player));
        assertBalance(9_650, 0);
        assertNotNull(resultRef.get());
        assertEquals(PaidActionResult.Status.CAPTURED, resultRef.get().status());
        assertEquals(EconomyReservations.operation(EconomyReservations.Kind.SIGN, workflow, EconomyRequest.Kind.CAPTURE),
                resultRef.get().captureOperationId());
    }

    @Test
    void refusedOutcomesAndInsufficientFundsReleaseOrPreventDebit() {
        // 1. Insufficient funds -> never invokes delivery
        UUID tooExpensive = UUID.randomUUID();
        AtomicReference<PaidActionResult> fundsRes = new AtomicReference<>();
        service.execute(new PaidActionService.LiveRequest(
                EconomyReservations.Kind.SIGN, tooExpensive, player, new Money(99_999),
                EconomyReservations.fingerprint("SIGN", tooExpensive.toString()),
                "Test action", () -> true,
                (valid, complete) -> fail("delivery must not run on insufficient funds"),
                fundsRes::set, true
        ));
        worker.remove().run();
        main.remove().run();
        assertEquals(PaidActionResult.Status.INSUFFICIENT_FUNDS, fundsRes.get().status());
        assertFalse(service.busy(player));
        assertBalance(10_000, 0);

        // 2. Delivery explicitly returns Refused -> hold released
        UUID refusedFlow = UUID.randomUUID();
        AtomicReference<PaidActionResult> refusedRes = new AtomicReference<>();
        service.execute(new PaidActionService.LiveRequest(
                EconomyReservations.Kind.RTP, refusedFlow, player, new Money(500),
                EconomyReservations.fingerprint("RTP", refusedFlow.toString()),
                "Test RTP", () -> true,
                (valid, complete) -> complete.accept(PaidActionOutcome.refused("no safe spot")),
                refusedRes::set, true
        ));
        worker.remove().run();
        main.remove().run();
        worker.remove().run();
        main.remove().run();
        assertEquals(PaidActionResult.Status.RELEASED, refusedRes.get().status());
        assertInstanceOf(PaidActionOutcome.Refused.class, refusedRes.get().deliveryOutcome());
        assertBalance(10_000, 0);
    }

    @Test
    void interruptedByExceptionDisconnectCloseOrFoliaRetirementReleasesHold() {
        // 1. Exception thrown in delivery -> converted to Interrupted and released
        UUID throwingFlow = UUID.randomUUID();
        AtomicReference<PaidActionResult> throwRes = new AtomicReference<>();
        service.execute(new PaidActionService.LiveRequest(
                EconomyReservations.Kind.SIGN, throwingFlow, player, new Money(250),
                EconomyReservations.fingerprint("SIGN", throwingFlow.toString()),
                "Test boom", () -> true,
                (valid, complete) -> { throw new IllegalStateException("boom"); },
                throwRes::set, true
        ));
        worker.remove().run();
        main.remove().run();
        worker.remove().run();
        main.remove().run();
        assertEquals(PaidActionResult.Status.RELEASED, throwRes.get().status());
        assertInstanceOf(PaidActionOutcome.Interrupted.class, throwRes.get().deliveryOutcome());
        assertBalance(10_000, 0);

        // 2. Folia entity retirement before delivery -> releases hold exactly once
        ServerThread region = mock(ServerThread.class);
        ArrayDeque<Runnable> retire = new ArrayDeque<>();
        when(scheduler.runOn(eq(region), any(Runnable.class), any(Runnable.class), eq(0L))).thenAnswer(call -> {
            retire.add(call.getArgument(2));
            return mock(TaskHandle.class);
        });
        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, player)).thenReturn(region);
            UUID retiredFlow = UUID.randomUUID();
            service.execute(new PaidActionService.LiveRequest(
                    EconomyReservations.Kind.SIGN, retiredFlow, player, new Money(400),
                    EconomyReservations.fingerprint("SIGN", retiredFlow.toString()),
                    "Test Folia", () -> true,
                    (valid, complete) -> fail("must not deliver after Folia retirement"),
                    r -> {}, true
            ));
            worker.remove().run();
            assertBalance(10_000, 400);
            retire.remove().run();
            assertEquals(1, worker.size());
            worker.remove().run();
            retire.remove().run();
            assertBalance(10_000, 0);
            assertFalse(service.busy(player));
        }
    }

    @Test
    void receiptRecoveryCapturesProvenReceiptReleasesAbsentAndPrunesLivePdc() throws Exception {
        UUID savedId = UUID.randomUUID();
        UUID absentId = UUID.randomUUID();
        assertInstanceOf(EconomyResult.Committed.class, engine.reservations().reserve(
                EconomyReservations.Kind.REPAIR, savedId, account, new Money(300), c -> true));
        assertInstanceOf(EconomyResult.Committed.class, engine.reservations().reserve(
                EconomyReservations.Kind.REPAIR, absentId, account, new Money(200), c -> true));
        assertBalance(10_000, 500);

        // Write a valid compressed NBT player.dat containing ONLY savedId in allium:repair_receipts
        writePlayerDat(temp.resolve(account + ".dat"), PlayerDataReceiptProof.REPAIR_RECEIPTS, savedId.toString());
        NamespacedKey pdcKey = new NamespacedKey("allium", "repair_receipts");
        pdcMap.put(pdcKey, savedId + "," + absentId);

        var spec = new PaidActionService.ReceiptSpec(PlayerDataReceiptProof.REPAIR_RECEIPTS, pdcKey, null, null);
        AtomicReference<PaidActionService.RecoveryResult> recovery = new AtomicReference<>();
        service.recoverReceipts(player, EconomyReservations.Kind.REPAIR, spec, "Paid repair", recovery::set);
        worker.remove().run();
        main.remove().run();

        assertNotNull(recovery.get());
        assertEquals(1, recovery.get().captured());
        assertEquals(1, recovery.get().released());
        assertBalance(9_700, 0);
        assertNull(pdcMap.get(pdcKey), "both settled IDs should be pruned from live PDC");
        assertFalse(service.busy(player));
    }

    private void assertBalance(long book, long reserved) {
        var found = assertInstanceOf(EconomyResult.Found.class, engine.balance(account));
        assertEquals(book, found.book().minor());
        assertEquals(reserved, found.reserved().minor());
    }

    /** Writes a minimal valid gzip-compressed NBT root compound containing BukkitValues -> key=value. */
    private static void writePlayerDat(Path file, String key, String value) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            out.writeByte(10); // TAG_Compound root
            out.writeUTF("");
            out.writeByte(3); // TAG_Int DataVersion
            out.writeUTF("DataVersion");
            out.writeInt(3955);
            out.writeByte(10); // TAG_Compound BukkitValues
            out.writeUTF("BukkitValues");
            out.writeByte(8); // TAG_String receipt entry
            out.writeUTF(key);
            out.writeUTF(value);
            out.writeByte(0); // end BukkitValues
            out.writeByte(0); // end root
        }
    }
}
