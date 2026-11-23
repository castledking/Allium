package codes.castled.allium.economy;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.database.DatabaseManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.naming.PlayerNameService;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static codes.castled.allium.economy.EconomyResult.Found;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class EconomyTaxAndChequeRuntimeTest {

    @TempDir Path temp;
    PluginStart plugin;
    MessageManager messages;
    EconomyRuntime runtime;
    UUID aliceId = UUID.randomUUID();
    UUID bobId = UUID.randomUUID();
    Player alice;
    Player bob;
    ConsoleCommandSender console;
    ThreadPoolExecutor pool;

    public interface HandleServer extends Server { Object getHandle(); }
    public static final class Storage { final java.io.File dir; Storage(java.io.File dir) { this.dir = dir; } public java.io.File getPlayerDir() { return dir; } }
    public static final class Handle { final Storage storage; Handle(java.io.File dir) { this.storage = new Storage(dir); } }

    @BeforeEach
    void setup() throws Exception {
        Path playerDataDir = temp.resolve("world/playerdata");
        Files.createDirectories(playerDataDir);
        String dbUrl = "jdbc:sqlite:" + temp.resolve("allium.db");
        plugin = mock(PluginStart.class);
        messages = mock(MessageManager.class);
        when(messages.resolve(any(Messages.class), any(String[].class))).thenAnswer(c -> c.getArgument(0, Messages.class).getDefault());
        YamlConfiguration config = new YamlConfiguration();
        config.set("economy.starting-balance", "100.00");
        config.set("economy.tax.enabled", true);
        config.set("economy.tax.percent", 5);
        config.set("economy.tax.account-name", "Tax");
        config.set("economy.tax.apply-to.pay", true);
        config.set("economy.tax.apply-to.cheques", false);

        DatabaseManager db = mock(DatabaseManager.class);
        codes.castled.allium.storage.ConnectionSource connSrc = () -> DriverManager.getConnection(dbUrl);
        new codes.castled.allium.preference.PreferenceRepository(connSrc, codes.castled.allium.storage.SqlDialect.SQLITE, System::currentTimeMillis).migrate();
        new codes.castled.allium.messaging.IgnoreRepository(connSrc, codes.castled.allium.storage.SqlDialect.SQLITE, System::currentTimeMillis).migrate();
        when(db.connectionSource()).thenReturn(connSrc);
        when(db.dialect()).thenReturn(codes.castled.allium.storage.SqlDialect.SQLITE);
        when(db.getConnection()).thenAnswer(c -> DriverManager.getConnection(dbUrl));

        HandleServer server = mock(HandleServer.class);
        when(server.getHandle()).thenReturn(new Handle(playerDataDir.toFile()));
        World world = mock(World.class);
        when(world.getWorldFolder()).thenReturn(temp.resolve("world").toFile());
        when(server.getWorlds()).thenReturn(List.of(world));
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));

        alice = mock(Player.class);
        bob = mock(Player.class);
        console = mock(ConsoleCommandSender.class);
        when(alice.getUniqueId()).thenReturn(aliceId);
        when(alice.getName()).thenReturn("Alice");
        when(alice.isOnline()).thenReturn(true);
        when(alice.canSee(any(Player.class))).thenReturn(true);
        when(alice.hasPermission(anyString())).thenAnswer(c -> !c.getArgument(0, String.class).equals("allium.tax.exempt"));
        when(bob.getUniqueId()).thenReturn(bobId);
        when(bob.getName()).thenReturn("Bob");
        when(bob.isOnline()).thenReturn(true);
        when(bob.canSee(any(Player.class))).thenReturn(true);
        when(bob.hasPermission(anyString())).thenReturn(true);
        when(console.getName()).thenReturn("CONSOLE");
        when(console.hasPermission(anyString())).thenReturn(true);

        when(server.getPlayer(aliceId)).thenReturn(alice);
        when(server.getPlayer(bobId)).thenReturn(bob);
        when(server.getPlayerExact("Alice")).thenReturn(alice);
        when(server.getPlayerExact("Bob")).thenReturn(bob);
        when(server.getOnlinePlayers()).thenAnswer(c -> List.of(alice, bob));

        PlayerNameService names = mock(PlayerNameService.class);
        NameCache cache = new NameCache(64);
        cache.put(aliceId, "Alice", System.currentTimeMillis());
        cache.put(bobId, "Bob", System.currentTimeMillis());
        when(names.cache()).thenReturn(cache);
        when(names.resolve("Alice")).thenReturn(CompletableFuture.completedFuture(Optional.of(new NameCache.Entry(aliceId, "Alice", 1L))));
        when(names.resolve("Bob")).thenReturn(CompletableFuture.completedFuture(Optional.of(new NameCache.Entry(bobId, "Bob", 1L))));

        pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.runOnServer(any())).thenAnswer(c -> { ((Runnable) c.getArgument(0)).run(); return TaskHandle.NONE; });
        when(scheduler.boundedPool(anyString(), anyInt(), anyInt())).thenReturn(pool);

        when(plugin.getName()).thenReturn("Allium");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDatabaseManager()).thenReturn(db);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getPlayerNames()).thenReturn(names);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getModuleManager()).thenReturn(mock(ModuleManager.class));
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));

        runtime = new EconomyRuntime(plugin);
        runtime.enable();
        drainWorker();
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) runtime.close();
        if (pool != null) pool.shutdownNow();
    }

    private void drainWorker() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        runtime.submitWork(null, latch::countDown);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    private long balance(UUID id) {
        return ((Found) runtime.synchronousBalance(id)).available().minor();
    }

    @Test
    void shorthandEcoAndTaxedAndExemptPayAndBaltopNamesWorkEndToEnd() throws Exception {
        UUID taxId = runtime.taxSettings().accountUuid();
        assertEquals(0L, balance(taxId), "Tax account opens at 0.00");

        // /money give Alice 100k and /eco give Bob 1M
        runtime.dispatch(console, "eco", new String[]{"give", "Alice", "100k"});
        runtime.dispatch(console, "eco", new String[]{"give", "Bob", "1M"});
        drainWorker();
        assertEquals(10_010_000L, balance(aliceId));
        assertEquals(100_010_000L, balance(bobId));

        // /pay Bob 100k with 5% tax: Alice -100k, Bob +95k, Tax +5k
        runtime.dispatch(alice, "pay", new String[]{"Bob", "100k"});
        drainWorker();
        assertEquals(10_000L, balance(aliceId));
        assertEquals(109_510_000L, balance(bobId));
        assertEquals(500_000L, balance(taxId));

        // With allium.tax.exempt, /pay Bob 100 transfers full 100.00 with 0 tax
        when(alice.hasPermission("allium.tax.exempt")).thenReturn(true);
        runtime.dispatch(alice, "pay", new String[]{"Bob", "100"});
        drainWorker();
        assertEquals(0L, balance(aliceId));
        assertEquals(109_520_000L, balance(bobId));
        assertEquals(500_000L, balance(taxId));

        // /baltop outputs header, Server Total, and one line per account with names (Bob, Tax, Alice), never raw UUIDs
        runtime.dispatch(console, "baltop", new String[0]);
        drainWorker();
        verify(messages).send(eq(console), eq(Messages.ECO_TOP), eq("{page}"), eq("1"), eq("{pages}"), eq("1"));
        verify(messages).send(eq(console), eq(Messages.ECO_TOP_TOTAL), eq("{total}"), eq("$1,100,200"));
        verify(messages).send(eq(console), eq(Messages.ECO_TOP_ENTRY), eq("{rank}"), eq("1"), eq("{player}"), eq("Bob"), eq("{balance}"), eq("$1,095,200"));
        verify(messages).send(eq(console), eq(Messages.ECO_TOP_ENTRY), eq("{rank}"), eq("2"), eq("{player}"), eq("Tax"), eq("{balance}"), eq("$5,000"));
        verify(messages).send(eq(console), eq(Messages.ECO_TOP_ENTRY), eq("{rank}"), eq("3"), eq("{player}"), eq("Alice"), eq("{balance}"), eq("$0"));
        verify(messages, never()).send(eq(console), eq(Messages.ECO_TOP_ENTRY), any(), any(), eq("{player}"), eq(taxId.toString()), any(), any());
    }

    @Test
    void taxCommandImposeHistoryAndConfirmedRefundWork() throws Exception {
        UUID taxId = runtime.taxSettings().accountUuid();
        runtime.dispatch(console, "eco", new String[]{"set", "Alice", "1k"});
        drainWorker();
        assertEquals(100_000L, balance(aliceId));

        // Impose 10% tax on Alice ($100 -> Tax)
        runtime.dispatch(console, "tax", new String[]{"impose", "Alice", "10"});
        drainWorker();
        assertEquals(90_000L, balance(aliceId));
        assertEquals(10_000L, balance(taxId));

        // Refund without confirm only prompts; balances unchanged
        runtime.dispatch(console, "tax", new String[]{"refund", "1", "50"});
        drainWorker();
        assertEquals(90_000L, balance(aliceId));
        assertEquals(10_000L, balance(taxId));

        // Confirmed 50% refund ($50 back from Tax -> Alice)
        runtime.dispatch(console, "tax", new String[]{"refund", "1", "50", "confirm"});
        drainWorker();
        assertEquals(95_000L, balance(aliceId));
        assertEquals(5_000L, balance(taxId));

        // Second refund refused cleanly
        runtime.dispatch(console, "tax", new String[]{"refund", "1", "100", "confirm"});
        drainWorker();
        assertEquals(95_000L, balance(aliceId));
        assertEquals(5_000L, balance(taxId));
    }

    @Test
    void taxAccountBaltopPayableAndCheckbalanceControlsWork() throws Exception {
        UUID taxId = runtime.taxSettings().accountUuid();
        runtime.dispatch(console, "eco", new String[]{"set", "Alice", "500"});
        runtime.dispatch(console, "eco", new String[]{"set", "Bob", "200"});
        runtime.dispatch(console, "eco", new String[]{"set", "Tax", "900"});
        drainWorker();

        // By default account-payable is false: /pay Tax 10 is refused
        runtime.dispatch(alice, "pay", new String[]{"Tax", "10"});
        drainWorker();
        assertEquals(50_000L, balance(aliceId));
        assertEquals(90_000L, balance(taxId));
        verify(messages).send(eq(alice), eq(Messages.ECO_REFUSED));

        // /bal Tax and /tax balance require allium.tax.balance (even if player has allium.balance.others)
        when(alice.hasPermission("allium.balance")).thenReturn(true);
        when(alice.hasPermission("allium.balance.others")).thenReturn(true);
        when(alice.hasPermission("allium.tax")).thenReturn(true);
        when(alice.hasPermission("allium.tax.balance")).thenReturn(false);
        runtime.dispatch(alice, "balance", new String[]{"Tax"});
        runtime.dispatch(alice, "tax", new String[]{"balance"});
        runtime.dispatch(alice, "tax", new String[0]);
        drainWorker();
        verify(messages, times(2)).send(eq(alice), eq(Messages.NO_PERMISSION));
        verify(messages).send(eq(alice), eq(Messages.ECO_TAX_INFO_RATE), eq("{rate}"), eq("5%"), eq("{status}"), eq("enabled"), eq("{applies}"), eq("pay"));

        // With allium.tax.balance (even without allium.balance.others), /bal Tax and /tax balance both succeed
        clearInvocations(messages);
        when(alice.hasPermission("allium.balance.others")).thenReturn(false);
        when(alice.hasPermission("allium.tax.balance")).thenReturn(true);
        runtime.dispatch(alice, "balance", new String[]{"Tax"});
        runtime.dispatch(alice, "tax", new String[]{"balance"});
        drainWorker();
        verify(messages, times(2)).send(eq(alice), eq(Messages.BALANCE), eq("{amount}"), eq("$900"));

        // Flip account-in-baltop: false and account-payable: true and reload
        plugin.getConfig().set("economy.tax.account-in-baltop", false);
        plugin.getConfig().set("economy.tax.account-payable", true);
        assertTrue(runtime.reload());

        // Now /pay Tax 10 succeeds (tax-exempt payment into Tax account)
        runtime.dispatch(alice, "pay", new String[]{"Tax", "10"});
        drainWorker();
        assertEquals(49_000L, balance(aliceId));
        assertEquals(91_000L, balance(taxId));

        // /baltop excludes Tax from both Server Total and entries
        clearInvocations(messages);
        runtime.dispatch(console, "baltop", new String[0]);
        drainWorker();
        verify(messages).send(eq(console), eq(Messages.ECO_TOP_TOTAL), eq("{total}"), eq("$690"));
        verify(messages).send(eq(console), eq(Messages.ECO_TOP_ENTRY), eq("{rank}"), eq("1"), eq("{player}"), eq("Alice"), eq("{balance}"), eq("$490"));
        verify(messages).send(eq(console), eq(Messages.ECO_TOP_ENTRY), eq("{rank}"), eq("2"), eq("{player}"), eq("Bob"), eq("{balance}"), eq("$200"));
        verify(messages, never()).send(eq(console), eq(Messages.ECO_TOP_ENTRY), any(), any(), eq("{player}"), eq("Tax"), any(), any());

        // Placeholder snapshot also excludes Tax
        runtime.refreshPlaceholders(List.of(aliceId, bobId));
        drainWorker();
        assertEquals(2, runtime.placeholders().top().size());
        assertTrue(runtime.placeholders().top().stream().noneMatch(e -> e.id().equals(taxId)));
    }

    @Test
    void payConfirmationRequiresMatchingSecondPayWithinWindowAndTogglesCleanly() throws Exception {
        var prefs = mock(codes.castled.allium.module.PreferencesModule.class);
        var sessions = mock(codes.castled.allium.preference.PreferenceSessions.class);
        when(prefs.isEnabled()).thenReturn(true);
        when(prefs.sessions()).thenReturn(sessions);
        when(prefs.acceptance(any(), any())).thenReturn(codes.castled.allium.preference.PreferenceSessions.Acceptance.ACCEPTS);
        var payConfirmEnabled = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(prefs.effective(aliceId)).thenAnswer(c -> Optional.of(
                new codes.castled.allium.preference.EffectivePreferences(true, true, true, 1.0D, false, false, payConfirmEnabled.get(), false)));
        when(sessions.change(eq(aliceId), any())).thenAnswer(c -> {
            codes.castled.allium.preference.PreferenceChange change = c.getArgument(1);
            var entry = change.entries().get(codes.castled.allium.preference.PreferenceKey.PAY_CONFIRM);
            if (entry != null) {
                payConfirmEnabled.set(Boolean.TRUE.equals(entry.orElse(false)));
            }
            return CompletableFuture.completedFuture(codes.castled.allium.preference.StoredPreferences.empty(aliceId));
        });
        ModuleManager modules = plugin.getModuleManager();
        when(modules.getModule("preferences")).thenReturn(prefs);

        // Toggle pay confirmation ON
        runtime.dispatch(alice, "payconfirmtoggle", new String[]{"on"});
        drainWorker();
        assertTrue(payConfirmEnabled.get());
        verify(messages).send(eq(alice), eq(Messages.ECO_PAY_CONFIRM_ON));

        long aliceBefore = balance(aliceId);
        long bobBefore = balance(bobId);

        // First /pay Bob 20 prompts for confirmation and does NOT debit
        runtime.dispatch(alice, "pay", new String[]{"Bob", "20"});
        drainWorker();
        assertEquals(aliceBefore, balance(aliceId));
        assertEquals(bobBefore, balance(bobId));
        verify(messages).send(eq(alice), eq(Messages.ECO_PAY_CONFIRM),
                eq("{amount}"), eq("$20"), eq("{player}"), eq("Bob"), eq("{raw_amount}"), eq("20"));

        // Different amount re-prompts and still does NOT debit
        runtime.dispatch(alice, "pay", new String[]{"Bob", "25"});
        drainWorker();
        assertEquals(aliceBefore, balance(aliceId));
        assertEquals(bobBefore, balance(bobId));
        verify(messages).send(eq(alice), eq(Messages.ECO_PAY_CONFIRM),
                eq("{amount}"), eq("$25"), eq("{player}"), eq("Bob"), eq("{raw_amount}"), eq("25"));

        // Matching second /pay Bob 25 executes and debits
        runtime.dispatch(alice, "pay", new String[]{"Bob", "25"});
        drainWorker();
        assertEquals(aliceBefore - 2_500L, balance(aliceId));
        assertTrue(balance(bobId) > bobBefore);

        // Toggle pay confirmation OFF -> subsequent /pay executes immediately
        runtime.dispatch(alice, "payconfirmtoggle", new String[]{"off"});
        drainWorker();
        assertFalse(payConfirmEnabled.get());
        verify(messages).send(eq(alice), eq(Messages.ECO_PAY_CONFIRM_OFF));

        long aliceMid = balance(aliceId);
        runtime.dispatch(alice, "pay", new String[]{"Bob", "10"});
        drainWorker();
        assertEquals(aliceMid - 1_000L, balance(aliceId));
    }
}
