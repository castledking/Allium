package codes.castled.allium.economy;

import codes.castled.allium.mail.PlayerDataReceiptProof;
import codes.castled.allium.storage.ConnectionSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Random;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static codes.castled.allium.economy.EconomyRequest.Kind.*;
import static codes.castled.allium.economy.EconomyReservations.Kind.*;
import static codes.castled.allium.economy.EconomyResult.*;
import static org.junit.jupiter.api.Assertions.*;

class TaxAndChequeCoreTest {

    @TempDir Path temp;
    ConnectionSource source;
    EconomyEngine engine;
    EconomyReservations holds;
    ChequeRepository cheques;
    TaxHistoryRepository taxHistory;
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    UUID taxUuid = TaxSettings.defaults().accountUuid();

    @BeforeEach
    void setup() throws Exception {
        String url = "jdbc:sqlite:" + temp.resolve("economy.db");
        source = () -> DriverManager.getConnection(url);
        engine = new EconomyEngine(source, EconomySettings.defaults(), (c, a, b) -> true);
        holds = engine.reservations();
        cheques = new ChequeRepository(source);
        taxHistory = new TaxHistoryRepository(source);
        assertInstanceOf(Committed.class, engine.execute(engine.request(UUID.randomUUID(), "test", "open-a", OPEN, "SYSTEM", alice, null, engine.settings().starting())));
        assertInstanceOf(Committed.class, engine.execute(engine.request(UUID.randomUUID(), "test", "open-b", OPEN, "SYSTEM", bob, null, engine.settings().starting())));
        assertInstanceOf(Committed.class, engine.execute(engine.request(UUID.randomUUID(), "test", "open-tax", OPEN_SYSTEM, "SYSTEM", taxUuid, null, new Money(0))));
    }

    private long book(UUID id) {
        return ((Found) engine.balance(id)).book().minor();
    }

    @Test
    void shorthandAmountsAndRangesParseExactly() {
        assertEquals(10_000_000L, Money.parseCommand("100k", false).minor());
        assertEquals(100_000_000L, Money.parseCommand("1M", false).minor());
        assertEquals(150_000L, Money.parseCommand("1.5k", false).minor());
        assertEquals(123_400L, Money.parseCommand("1.234k", false).minor());
        assertEquals(100_000_000_000L, Money.parseCommand("1b", false).minor());
        assertEquals(100_000_000_000_000L, Money.parseCommand("1t", false).minor());
        assertEquals(Money.HARD, Money.parseCommand("10t", false).minor());
        assertEquals(-500_000L, Money.parseCommand("-5k", true).minor());

        for (String bad : new String[]{"", "k", "1e2", "1.234567k", "11t", "1kk", "$100", "1,000", "-10k", "500-1000"}) {
            assertThrows(RuntimeException.class, () -> Money.parseCommand(bad, false), bad);
        }

        Money.Range range = Money.parseCommandRange("500-1000");
        assertTrue(range.isRange());
        Random rng = new Random(42L);
        for (int i = 0; i < 50; i++) {
            Money sampled = range.sample(rng);
            assertTrue(sampled.minor() >= 50_000 && sampled.minor() <= 100_000);
            assertEquals(0, sampled.minor() % 100, "whole dollar bounds sample whole dollars");
        }
        Money.Range shorthandRange = Money.parseCommandRange("1k-2.5k");
        assertEquals(100_000L, shorthandRange.min().minor());
        assertEquals(250_000L, shorthandRange.max().minor());
        assertThrows(RuntimeException.class, () -> Money.parseCommandRange("1000-500"));
        assertThrows(RuntimeException.class, () -> Money.parseCommandRange("0-100"));
        assertThrows(RuntimeException.class, () -> Money.parseCommandRange("1-2-3"));
    }

    @Test
    void taxSettingsCalculatesExactRoundingAndMinimums() {
        TaxSettings def = TaxSettings.defaults();
        assertEquals(500, def.calculateTax(new Money(10_000)).minor());
        assertEquals(6, def.calculateTax(new Money(123)).minor()); // 6.15 -> HALF_UP = 6
        TaxSettings ceil = new TaxSettings(true, new BigDecimal("5"), "Tax", true, false, RoundingMode.CEILING, new Money(0));
        assertEquals(7, ceil.calculateTax(new Money(123)).minor());
        TaxSettings floor = new TaxSettings(true, new BigDecimal("5"), "Tax", true, false, RoundingMode.FLOOR, new Money(50));
        assertEquals(50, floor.calculateTax(new Money(123)).minor());
        assertEquals(0, TaxSettings.disabled().calculateTax(new Money(10_000)).minor());
    }

    @Test
    void taxedPayMovesThreeLegsAtomicallyAndConservesMoney() throws Exception {
        assertEquals(0, book(taxUuid), "OPEN_SYSTEM starts at 0.00");
        var req = engine.request(UUID.randomUUID(), "PAY", "taxed-1", PAY, alice.toString(),
                alice, bob, new Money(10_000), taxUuid, new Money(500));
        var committed = (Committed) engine.execute(req);
        assertEquals(0, book(alice));
        assertEquals(19_500, book(bob));
        assertEquals(500, book(taxUuid));
        assertTrue(((Committed) engine.execute(req)).replay());
        try (var c = source.connection(); var s = c.prepareStatement(
                "SELECT SUM(book_delta), COUNT(*) FROM " + EconomySchema.ENTRIES + " WHERE operation_id=?")) {
            s.setString(1, committed.operationId().toString());
            try (var r = s.executeQuery()) {
                assertTrue(r.next());
                assertEquals(0, r.getLong(1));
                assertEquals(3, r.getLong(2));
            }
        }
    }

    @Test
    void chequeLifecycleSupportsTaxedCaptureAtomicRedeemAndCrashRecovery() throws Exception {
        // 1. Player-funded cheque with 5% tax: reserve $100, capture ($5 to Tax, $95 face), redeem once.
        UUID chequeId = UUID.randomUUID();
        var pending = new ChequeRecord(chequeId, new Money(9_500), new Money(500), alice, "Alice",
                false, "PENDING", null, null, null, System.currentTimeMillis(), null);
        assertInstanceOf(Committed.class, holds.reserve(CHEQUE, chequeId, alice, new Money(10_000),
                c -> cheques.insertPending(c, pending)));
        assertEquals("PENDING", cheques.find(chequeId).orElseThrow().state());

        UUID captureOp = EconomyReservations.operation(CHEQUE, chequeId, CAPTURE);
        assertInstanceOf(Committed.class, holds.capture(CHEQUE, chequeId, taxUuid, new Money(500),
                c -> cheques.markIssued(c, chequeId, captureOp)));
        assertEquals(0, book(alice));
        assertEquals(500, book(taxUuid));
        assertEquals("ISSUED", cheques.find(chequeId).orElseThrow().state());

        // Bob redeems once; second redemption (e.g. duped item) refuses without crediting Bob again.
        UUID redeemOp = UUID.randomUUID();
        var redeemReq = engine.request(redeemOp, "CHEQUE_REDEEM", chequeId.toString(), DEPOSIT, bob.toString(), bob, null, new Money(9_500));
        assertInstanceOf(Committed.class, engine.execute(redeemReq,
                c -> cheques.markRedeemed(c, chequeId, new Money(9_500), redeemOp, bob, System.currentTimeMillis())));
        assertEquals(19_500, book(bob));
        assertEquals("REDEEMED", cheques.find(chequeId).orElseThrow().state());

        UUID dupeOp = UUID.randomUUID();
        var dupeReq = engine.request(dupeOp, "CHEQUE_REDEEM", chequeId + ":dupe", DEPOSIT, bob.toString(), bob, null, new Money(9_500));
        var refused = (Rejected) engine.execute(dupeReq,
                c -> cheques.markRedeemed(c, chequeId, new Money(9_500), dupeOp, bob, System.currentTimeMillis()));
        assertEquals("WORKFLOW_REFUSED", refused.reason());
        assertEquals(19_500, book(bob));

        // 2. Crash recovery sweep: receipt present captures with tax; receipt absent releases & voids.
        Path playerData = temp.resolve("playerdata");
        Files.createDirectories(playerData);
        UUID crashCaptured = UUID.randomUUID();
        UUID crashReleased = UUID.randomUUID();
        assertInstanceOf(Committed.class, holds.reserve(CHEQUE, crashCaptured, bob, new Money(2_000),
                c -> cheques.insertPending(c, new ChequeRecord(crashCaptured, new Money(1_900), new Money(100), bob, "Bob",
                        false, "PENDING", null, null, null, System.currentTimeMillis(), null))));
        assertInstanceOf(Committed.class, holds.reserve(CHEQUE, crashReleased, bob, new Money(1_000),
                c -> cheques.insertPending(c, new ChequeRecord(crashReleased, new Money(1_000), new Money(0), bob, "Bob",
                        false, "PENDING", null, null, null, System.currentTimeMillis(), null))));
        writePlayerDat(playerData.resolve(bob + ".dat"), PlayerDataReceiptProof.CHEQUE_RECEIPTS, crashCaptured.toString());

        EconomyRecovery.sweep(source, holds, playerData, id -> false, taxUuid, msg -> fail("unexpected warning: " + msg));
        assertEquals("ISSUED", cheques.find(crashCaptured).orElseThrow().state());
        assertEquals("VOIDED", cheques.find(crashReleased).orElseThrow().state());
        assertEquals(17_500, book(bob));
        assertEquals(0, ((Found) engine.balance(bob)).reserved().minor());
        assertEquals(600, book(taxUuid));
    }

    @Test
    void taxImposeAndRefundAreAtomicAndCannotDoubleRefund() throws Exception {
        UUID imposeOp = UUID.randomUUID();
        var imposeReq = engine.request(imposeOp, "TAX_IMPOSE", imposeOp.toString(), TRANSFER, "CONSOLE",
                alice, taxUuid, new Money(2_500));
        assertInstanceOf(Committed.class, engine.execute(imposeReq,
                c -> taxHistory.insertImpose(c, imposeOp, alice, "Alice", new Money(2_500), 2500, new Money(10_000), "Console", System.currentTimeMillis())));
        assertEquals(7_500, book(alice));
        assertEquals(2_500, book(taxUuid));

        TaxHistoryRecord row = taxHistory.findLatestUnrefunded(alice).orElseThrow();
        assertEquals(2_500, row.amount().minor());
        assertFalse(row.refunded());

        UUID refundOp = UUID.randomUUID();
        var refundReq = engine.request(refundOp, "TAX_REFUND", "refund:" + row.id(), TRANSFER, "CONSOLE",
                taxUuid, alice, new Money(2_500));
        assertInstanceOf(Committed.class, engine.execute(refundReq,
                c -> taxHistory.markRefunded(c, row.id(), new Money(2_500), 10_000, "Console", refundOp, System.currentTimeMillis())));
        assertEquals(10_000, book(alice));
        assertEquals(0, book(taxUuid));
        assertTrue(taxHistory.findById(row.id()).orElseThrow().refunded());

        // Second refund attempt on same ID refuses cleanly without touching balances.
        UUID refundOp2 = UUID.randomUUID();
        var refundReq2 = engine.request(refundOp2, "TAX_REFUND", "refund-2:" + row.id(), TRANSFER, "CONSOLE",
                taxUuid, alice, new Money(100));
        assertInstanceOf(Rejected.class, engine.execute(refundReq2,
                c -> taxHistory.markRefunded(c, row.id(), new Money(100), 10_000, "Console", refundOp2, System.currentTimeMillis())));
        assertEquals(10_000, book(alice));
    }

    private static void writePlayerDat(Path path, String key, String value) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(raw); DataOutputStream out = new DataOutputStream(gz)) {
            out.writeByte(10); // root TAG_Compound
            out.writeUTF("");
            out.writeByte(10); // BukkitValues TAG_Compound
            out.writeUTF("BukkitValues");
            out.writeByte(8);  // TAG_String
            out.writeUTF(key);
            out.writeUTF(value);
            out.writeByte(0);  // end BukkitValues
            out.writeByte(0);  // end root
        }
        Files.write(path, raw.toByteArray());
    }
}
