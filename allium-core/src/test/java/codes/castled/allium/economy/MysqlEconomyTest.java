package codes.castled.allium.economy;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import codes.castled.allium.importer.ImportLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static codes.castled.allium.economy.EconomyRequest.Kind.*;
import static codes.castled.allium.economy.EconomyResult.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "ALLIUM_TEST_MYSQL_URL", matches = ".+")
class MysqlEconomyTest {
    private final ConnectionSource source = () -> DriverManager.getConnection(System.getenv("ALLIUM_TEST_MYSQL_URL"));

    @Test void persistsAndRecoversLedgerAndWorkflowReceipts(@TempDir Path directory) throws Exception {
        var engine = new EconomyEngine(source, SqlDialect.MYSQL, EconomySettings.defaults(), (c, from, to) -> true);
        engine.bindRecoveryOwner("economy-test");
        var restarted = new EconomyEngine(source, SqlDialect.MYSQL, EconomySettings.defaults(), (c, from, to) -> true);
        restarted.bindRecoveryOwner("economy-test");
        assertThrows(SQLException.class, () -> restarted.bindRecoveryOwner("different-playerdata"));

        UUID payer = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        var imports = new ImportLedger(source, SqlDialect.MYSQL, System::currentTimeMillis);
        imports.migrate();
        assertInstanceOf(Committed.class, engine.execute(request(engine, OPEN_IMPORTED, payer, null, 9_000), c -> {
            imports.record(c, "essentials", "balance", payer.toString(), "0".repeat(64), "APPLIED", "test");
            return true;
        }));
        assertEquals("0".repeat(64), imports.hashes("essentials").get("balance:" + payer));
        assertInstanceOf(Committed.class, engine.execute(request(engine, OPEN, recipient, null, 10_000)));
        var payment = request(engine, PAY, payer, recipient, 500);
        assertFalse(assertInstanceOf(Committed.class, engine.execute(payment)).replay());
        assertTrue(assertInstanceOf(Committed.class, restarted.execute(payment)).replay());
        assertEquals(8_500, book(restarted, payer));
        assertEquals(10_500, book(restarted, recipient));

        var holds = engine.reservations();
        UUID sign = UUID.randomUUID();
        UUID teleport = UUID.randomUUID();
        assertInstanceOf(Committed.class, holds.reserve(EconomyReservations.Kind.SIGN, sign, payer, new Money(250), c -> true));
        assertInstanceOf(Committed.class, holds.reserve(EconomyReservations.Kind.RTP, teleport, payer, new Money(100), c -> true));
        assertTrue(EconomyReservations.paid(source, SqlDialect.MYSQL, EconomyReservations.Kind.RTP, teleport));
        assertInstanceOf(Committed.class, holds.capture(EconomyReservations.Kind.SIGN, sign, c -> true));
        EconomyRecovery.sweep(source, SqlDialect.MYSQL, restarted.reservations(), directory, id -> false,
                engine.settings().tax().accountUuid(), message -> fail(message));
        assertEquals(8_250, book(restarted, payer));
        assertEquals(0, assertInstanceOf(Found.class, restarted.balance(payer)).reserved().minor());
        assertEquals("RELEASED", holds.find(EconomyReservations.Kind.RTP, teleport).orElseThrow().state());

        var rolledBack = request(engine, ADMIN_SET, payer, null, 8_000);
        assertEquals("WORKFLOW_REFUSED", assertInstanceOf(Rejected.class, engine.execute(rolledBack, c -> false)).reason());
        assertEquals(8_250, book(restarted, payer));

        var cheques = new ChequeRepository(source);
        UUID chequeId = UUID.randomUUID();
        Money face = new Money(200);
        var note = new ChequeRecord(chequeId, face, new Money(0), payer, "Payer", false, "PENDING",
                null, null, null, System.currentTimeMillis(), null);
        assertInstanceOf(Committed.class, holds.reserve(EconomyReservations.Kind.CHEQUE, chequeId, payer, face,
                c -> cheques.insertPending(c, note)));
        UUID capture = EconomyReservations.operation(EconomyReservations.Kind.CHEQUE, chequeId, CAPTURE);
        assertInstanceOf(Committed.class, holds.capture(EconomyReservations.Kind.CHEQUE, chequeId,
                c -> cheques.markIssued(c, chequeId, capture)));
        var redeem = request(restarted, DEPOSIT, recipient, null, 200);
        assertInstanceOf(Committed.class, restarted.execute(redeem,
                c -> cheques.markRedeemed(c, chequeId, face, redeem.id(), recipient, System.currentTimeMillis())));
        assertEquals(8_050, book(restarted, payer));
        assertEquals(10_700, book(restarted, recipient));
        assertEquals("REDEEMED", cheques.find(chequeId).orElseThrow().state());
    }

    @Test void concurrentWritersCannotOverdrawTheSameAccount() throws Exception {
        var first = new EconomyEngine(source, SqlDialect.MYSQL, EconomySettings.defaults(), (c, from, to) -> true);
        var second = new EconomyEngine(source, SqlDialect.MYSQL, EconomySettings.defaults(), (c, from, to) -> true);
        UUID payer = UUID.randomUUID();
        assertInstanceOf(Committed.class, first.execute(request(first, OPEN_IMPORTED, payer, null, 500)));

        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> first.execute(request(first, WITHDRAW, payer, null, 400)));
            var two = executor.submit(() -> second.execute(request(second, WITHDRAW, payer, null, 400)));
            var results = java.util.List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(Committed.class::isInstance).count());
            assertEquals(1, results.stream().filter(r -> r instanceof Rejected rejected
                    && rejected.reason().equals("INSUFFICIENT_FUNDS")).count());
            assertEquals(100, book(first, payer));
        } finally {
            executor.shutdownNow();
        }
    }

    private static EconomyRequest request(EconomyEngine engine, EconomyRequest.Kind kind, UUID from, UUID to, long minor) {
        UUID operation = UUID.randomUUID();
        return engine.request(operation, "test", operation.toString(), kind, "test", from, to, new Money(minor));
    }

    private static long book(EconomyEngine engine, UUID account) {
        return assertInstanceOf(Found.class, engine.balance(account)).book().minor();
    }
}
