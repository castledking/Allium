package codes.castled.allium.economy;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.EconomyModule;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaidCommandServiceTest {

    @TempDir Path temp;

    private final UUID account = UUID.randomUUID();
    private final ArrayDeque<Runnable> worker = new ArrayDeque<>();
    private final ArrayDeque<Runnable> main = new ArrayDeque<>();
    private final AtomicLong clock = new AtomicLong(10_000L);
    private final Set<String> grants = new HashSet<>();

    private EconomyEngine engine;
    private CommandCooldownRepository cooldowns;
    private PluginStart plugin;
    private MessageManager messages;
    private Player player;
    private PaidCommandService service;

    @BeforeEach
    void setUp() throws Exception {
        ConnectionSource ecoSource = () -> DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("eco.db"));
        engine = new EconomyEngine(ecoSource, EconomySettings.defaults(), (c, a, b) -> true);
        assertInstanceOf(EconomyResult.Committed.class, engine.execute(engine.request(
                UUID.randomUUID(), "test", "open", EconomyRequest.Kind.OPEN, "test",
                account, null, new Money(10_000))));

        ConnectionSource cdSource = () -> DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("cd.db"));
        cooldowns = new CommandCooldownRepository(cdSource, SqlDialect.SQLITE, clock::get);
        cooldowns.migrate();

        plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ModuleManager modules = mock(ModuleManager.class);
        EconomyModule ecoModule = mock(EconomyModule.class);
        EconomyRuntime runtime = mock(EconomyRuntime.class);
        messages = mock(MessageManager.class);
        AlliumLogger logger = mock(AlliumLogger.class);

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getModuleManager()).thenReturn(modules);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.logger()).thenReturn(logger);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.getOnlinePlayers()).thenReturn(List.of());
        when(modules.getModule("economy")).thenReturn(ecoModule);
        when(ecoModule.isEnabled()).thenReturn(true);
        when(ecoModule.runtime()).thenReturn(runtime);
        when(runtime.ready()).thenReturn(true);
        when(runtime.reservations()).thenReturn(engine.reservations());
        when(runtime.display()).thenReturn(EconomyDisplay.DEFAULT);
        when(scheduler.runOnServer(any(Runnable.class))).thenAnswer(call -> {
            main.add(call.getArgument(0));
            return TaskHandle.NONE;
        });

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(account);
        when(player.getName()).thenReturn("Alice");
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(anyString())).thenAnswer(call -> grants.contains(call.<String>getArgument(0)));
        when(server.getPlayer(account)).thenReturn(player);

        CommandActionPolicy policy = new CommandActionPolicy(Map.of(
                "repair", new CommandActionPolicy.Rule(new Money(250), 60_000L),
                "heal", new CommandActionPolicy.Rule(new Money(500), 30_000L)
        ));
        service = new PaidCommandService(plugin, new PaidActionService(plugin, worker::add, temp),
                cooldowns, worker::add, clock::get, policy);
        service.enable();
        service.onJoin(new PlayerJoinEvent(player, ""));
        drain();
    }

    @Test
    void chargesAndPersistsCooldownOnlyOnDeliveredOutcomeAndSurvivesReconnect() throws Exception {
        // Precheck refusal (e.g., undamaged item) never touches money or cooldowns
        AtomicInteger delivered = new AtomicInteger();
        service.execute(player, "repair", () -> false, () -> { delivered.incrementAndGet(); return true; });
        drain();
        assertEquals(0, delivered.get());
        assertBalance(10_000L, 0L);
        assertEquals(0L, service.remainingCooldownMillis(account, "repair"));

        // Post-reserve delivery refusal (e.g., player swapped item away) releases the hold with zero cooldown
        service.execute(player, "repair", () -> true, () -> false);
        drain();
        assertBalance(10_000L, 0L);
        assertEquals(0L, service.remainingCooldownMillis(account, "repair"));

        // Delivered action captures 250 minor units ($2.50) and starts a 60s persistent cooldown
        service.execute(player, "repair", () -> true, () -> { delivered.incrementAndGet(); return true; });
        drain();
        assertEquals(1, delivered.get());
        assertBalance(9_750L, 0L);
        verify(messages).send(player, Messages.COMMAND_POLICY_CHARGED, "{command}", "/repair", "{price}", "$2.5");
        assertEquals(60_000L, service.remainingCooldownMillis(account, "repair"));

        // Immediate retry is blocked by cooldown without charging
        service.execute(player, "repair", () -> true, () -> { delivered.incrementAndGet(); return true; });
        drain();
        assertEquals(1, delivered.get());
        assertBalance(9_750L, 0L);
        verify(messages).send(player, Messages.COMMAND_POLICY_COOLDOWN, "{command}", "/repair", "{time}", "1m");

        // Quit + reconnect reloads the persisted cooldown from SQLite so reconnecting cannot bypass it
        clock.addAndGet(15_000L);
        service.onQuit(new PlayerQuitEvent(player, ""));
        service.onJoin(new PlayerJoinEvent(player, ""));
        drain();
        assertEquals(45_000L, service.remainingCooldownMillis(account, "repair"));
        service.execute(player, "repair", () -> true, () -> { delivered.incrementAndGet(); return true; });
        assertEquals(1, delivered.get());

        // After cooldown expires, player can use /repair again
        clock.addAndGet(46_000L);
        service.execute(player, "repair", () -> true, () -> { delivered.incrementAndGet(); return true; });
        drain();
        assertEquals(2, delivered.get());
        assertBalance(9_500L, 0L);
    }

    @Test
    void insufficientFundsAndGranularBypassPermissionsAndConfigValidationWork() throws Exception {
        // Price exceeds balance -> INSUFFICIENT_FUNDS, delivery never runs, no cooldown
        service.setPolicy(new CommandActionPolicy(Map.of(
                "heal", new CommandActionPolicy.Rule(new Money(99_999), 30_000L)
        )));
        AtomicInteger delivered = new AtomicInteger();
        service.execute(player, "heal", () -> true, () -> { delivered.incrementAndGet(); return true; });
        drain();
        assertEquals(0, delivered.get());
        assertEquals(0L, service.remainingCooldownMillis(account, "heal"));
        verify(messages).send(player, Messages.COMMAND_POLICY_INSUFFICIENT_FUNDS, "{command}", "/heal", "{price}", "$999.99");

        // Cost bypass allows running /heal for free while still applying the cooldown
        grants.add("allium.command.bypass.cost.heal");
        service.execute(player, "heal", () -> true, () -> { delivered.incrementAndGet(); return true; });
        drain();
        assertEquals(1, delivered.get());
        assertBalance(10_000L, 0L);
        assertEquals(30_000L, service.remainingCooldownMillis(account, "heal"));

        // Cooldown bypass allows immediate reuse
        grants.add("allium.command.bypass.cooldown.heal");
        service.execute(player, "heal", () -> true, () -> { delivered.incrementAndGet(); return true; });
        drain();
        assertEquals(2, delivered.get());

        // Config parser validates command-policy.actions cleanly and rejects bad entries
        YamlConfiguration valid = new YamlConfiguration();
        valid.set("command-policy.actions.repair.cost", "12.50");
        valid.set("command-policy.actions.repair.cooldown-seconds", 45);
        CommandActionPolicy parsed = PaidCommandService.readPolicy(valid);
        assertEquals(1250L, parsed.ruleFor("repair").cost().minor());
        assertEquals(45_000L, parsed.ruleFor("repair").cooldownMillis());

        YamlConfiguration badAction = new YamlConfiguration();
        badAction.set("command-policy.actions.unknowncmd.cost", "5.00");
        assertThrows(IllegalArgumentException.class, () -> PaidCommandService.readPolicy(badAction));

        YamlConfiguration badCost = new YamlConfiguration();
        badCost.set("command-policy.actions.repair.cost", "-5.00");
        assertThrows(IllegalArgumentException.class, () -> PaidCommandService.readPolicy(badCost));
    }

    private void assertBalance(long book, long reserved) {
        var found = assertInstanceOf(EconomyResult.Found.class, engine.balance(account));
        assertEquals(book, found.book().minor());
        assertEquals(reserved, found.reserved().minor());
    }

    private void drain() {
        for (int i = 0; i < 40 && (!worker.isEmpty() || !main.isEmpty()); i++) {
            while (!worker.isEmpty()) worker.removeFirst().run();
            while (!main.isEmpty()) main.removeFirst().run();
        }
        assertTrue(worker.isEmpty(), "worker queue drained");
        assertTrue(main.isEmpty(), "main queue drained");
    }
}
