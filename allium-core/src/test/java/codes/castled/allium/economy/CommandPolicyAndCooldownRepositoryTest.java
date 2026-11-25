package codes.castled.allium.economy;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CommandPolicyAndCooldownRepositoryTest {

    @Test
    void policyValidatesActionsBoundsAndBypassPermissions() {
        CommandActionPolicy policy = new CommandActionPolicy(Map.of(
                "repair", new CommandActionPolicy.Rule(new Money(250), 30_000L),
                "repair-all", new CommandActionPolicy.Rule(new Money(1_000), 120_000L),
                "heal", CommandActionPolicy.Rule.FREE
        ));
        assertEquals(250L, policy.ruleFor("REPAIR").cost().minor());
        assertEquals(30_000L, policy.ruleFor("repair").cooldownMillis());
        assertEquals(1_000L, policy.ruleFor("repair-all").cost().minor());
        assertTrue(policy.ruleFor("heal").isFree());
        assertTrue(policy.ruleFor("anvil").isFree());
        assertEquals("allium.command.bypass.cost.repair", CommandActionPolicy.bypassCostPermission("REPAIR"));
        assertEquals("allium.command.bypass.cooldown.repair-all", CommandActionPolicy.bypassCooldownPermission("repair-all"));

        assertThrows(IllegalArgumentException.class, () -> new CommandActionPolicy(
                Map.of("op", new CommandActionPolicy.Rule(new Money(100), 0L))));
        assertThrows(IllegalArgumentException.class, () -> new CommandActionPolicy.Rule(new Money(-1), 0L));
        assertThrows(IllegalArgumentException.class, () -> new CommandActionPolicy.Rule(new Money(0), -1L));
        assertThrows(IllegalArgumentException.class, () -> new CommandActionPolicy.Rule(
                new Money(0), CommandActionPolicy.MAX_COOLDOWN_MILLIS + 1L));
    }

    @Test
    void cooldownRepositoryPersistsAcrossReopenAndPurgesExpired(@TempDir Path dir) throws Exception {
        ConnectionSource source = () -> DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("cooldowns.db"));
        AtomicLong clock = new AtomicLong(1_000L);
        CommandCooldownRepository repo = new CommandCooldownRepository(source, SqlDialect.SQLITE, clock::get);
        assertEquals(1, repo.migrate());

        UUID alice = UUID.randomUUID();
        repo.save(alice, "repair", 61_000L);
        repo.save(alice, "heal", 11_000L);
        // Upsert extends deadline
        repo.save(alice, "repair", 91_000L);

        assertEquals(Map.of("repair", 91_000L, "heal", 11_000L), repo.loadActive(alice, 5_000L));
        assertEquals(Map.of("repair", 91_000L), repo.loadActive(alice, 15_000L));

        assertEquals(1, repo.purgeExpired(15_000L));
        CommandCooldownRepository reopened = new CommandCooldownRepository(source, SqlDialect.SQLITE, clock::get);
        assertEquals(Map.of("repair", 91_000L), reopened.loadActive(alice, 15_000L));
    }

    @Test
    void economyRecoveryReleasesStaleInterruptedCommandReservation(@TempDir Path dir) throws Exception {
        ConnectionSource source = () -> DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("eco.db"));
        EconomyEngine engine = new EconomyEngine(source, EconomySettings.defaults(), (c, a, b) -> true);
        UUID account = UUID.randomUUID();
        assertInstanceOf(EconomyResult.Committed.class, engine.execute(engine.request(
                UUID.randomUUID(), "test", "open", EconomyRequest.Kind.OPEN, "test",
                account, null, new Money(10_000))));

        UUID workflowId = UUID.randomUUID();
        assertInstanceOf(EconomyResult.Committed.class, engine.reservations().reserve(
                EconomyReservations.Kind.COMMAND, workflowId, account, new Money(450), c -> true));
        var held = assertInstanceOf(EconomyResult.Found.class, engine.balance(account));
        assertEquals(10_000L, held.book().minor());
        assertEquals(450L, held.reserved().minor());

        List<String> warnings = new ArrayList<>();
        EconomyRecovery.sweep(source, SqlDialect.SQLITE, engine.reservations(), dir, id -> false,
                TaxSettings.defaults().accountUuid(), warnings::add);

        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals("RELEASED", engine.reservations().find(EconomyReservations.Kind.COMMAND, workflowId).orElseThrow().state());
        var released = assertInstanceOf(EconomyResult.Found.class, engine.balance(account));
        assertEquals(10_000L, released.book().minor());
        assertEquals(0L, released.reserved().minor());
    }
}
