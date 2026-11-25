package codes.castled.allium.sign;

import codes.castled.allium.economy.Money;
import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class TradeSignTest {

    @TempDir
    Path tempDir;

    @Test
    void specParsesMoneyItemAndBarterFormatsAndRejectsInvalidOnes() {
        Function<Money, String> fmt = m -> "$" + (m.minor() % 100 == 0
                ? Long.toString(m.minor() / 100)
                : (m.minor() / 100) + "." + String.format("%02d", m.minor() % 100));

        var moneyForItems = TradeSignSpec.parse("$50", "16 coal:64");
        assertEquals(TradeSignSpec.Kind.MONEY, moneyForItems.pay().kind());
        assertEquals(5_000L, moneyForItems.pay().amount());
        assertEquals(TradeSignSpec.Kind.ITEM, moneyForItems.give().kind());
        assertEquals("coal", moneyForItems.give().itemSpec());
        assertEquals(16L, moneyForItems.give().amount());
        assertEquals(64L, moneyForItems.initialStock());
        assertEquals("$50", TradeSignSpec.formatOffer(moneyForItems.pay(), fmt));
        assertEquals("16 coal:64", TradeSignSpec.formatStockLine(moneyForItems.give(), 64L, fmt));

        var itemsForMoney = TradeSignSpec.parse("16 coal", "$25:$250");
        assertEquals(TradeSignSpec.Kind.ITEM, itemsForMoney.pay().kind());
        assertEquals(16L, itemsForMoney.pay().amount());
        assertEquals(TradeSignSpec.Kind.MONEY, itemsForMoney.give().kind());
        assertEquals(2_500L, itemsForMoney.give().amount());
        assertEquals(25_000L, itemsForMoney.initialStock());

        var barterDefaultStock = TradeSignSpec.parse("1 diamond", "16 iron_ingot");
        assertEquals(TradeSignSpec.Kind.ITEM, barterDefaultStock.pay().kind());
        assertEquals(TradeSignSpec.Kind.ITEM, barterDefaultStock.give().kind());
        assertEquals(16L, barterDefaultStock.initialStock());

        UUID owner = UUID.randomUUID();
        String encoded = TradeSignSpec.encodeArgument(owner, moneyForItems.pay(), moneyForItems.give());
        var decoded = TradeSignSpec.decodeArgument(encoded);
        assertEquals(owner, decoded.ownerUuid());
        assertEquals(moneyForItems.pay(), decoded.pay());
        assertEquals(moneyForItems.give(), decoded.give());

        assertThrows(IllegalArgumentException.class, () -> TradeSignSpec.parse("$10", "$20:$100"));
        assertThrows(IllegalArgumentException.class, () -> TradeSignSpec.parse("$10", "16 coal:8"));
        assertThrows(IllegalArgumentException.class, () -> TradeSignSpec.parse("0 coal", "$10:$10"));
        assertThrows(IllegalArgumentException.class, () -> TradeSignSpec.parse("$0", "16 coal:16"));
    }

    @Test
    void repositoryEnforcesAtomicNonNegativeStockAndReclaim() throws Exception {
        ConnectionSource cs = () -> DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("trades.db"));
        TradeSignRepository repo = new TradeSignRepository(cs, SqlDialect.SQLITE, System::currentTimeMillis);
        assertEquals(1, repo.migrate());
        assertEquals(1, repo.migrate());

        UUID signId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        repo.create(new TradeSignRepository.TradeSignRecord(
                signId, ownerId, "Alice", "world", 10, 64, -5, "FRONT",
                TradeSignSpec.Kind.MONEY, "", 5_000L,
                TradeSignSpec.Kind.ITEM, "coal", 16L,
                32L, 0L, 1L, 1_000L
        ));

        // 2 purchases drain 32 -> 16 -> 0 while accumulating $100 earned
        var first = repo.applyDelta(signId, -16L, 5_000L).orElseThrow();
        assertEquals(16L, first.stockAmount());
        assertEquals(5_000L, first.earnedAmount());

        var second = repo.applyDelta(signId, -16L, 5_000L).orElseThrow();
        assertEquals(0L, second.stockAmount());
        assertEquals(10_000L, second.earnedAmount());

        // 3rd purchase must fail atomically without underflow
        assertTrue(repo.applyDelta(signId, -16L, 5_000L).isEmpty());
        assertEquals(0L, repo.load(signId).orElseThrow().stockAmount());

        // Partial collect of earnings
        var collected = repo.collectEarned(signId, 4_000L).orElseThrow();
        assertEquals(4_000L, collected.collected());
        assertEquals(6_000L, collected.after().earnedAmount());

        // Restock 16 and then deleteAndReclaim returns both remaining stock and remaining earnings
        repo.applyDelta(signId, 16L, 0L).orElseThrow();
        var reclaimed = repo.deleteAndReclaim(signId).orElseThrow();
        assertEquals(16L, reclaimed.stockAmount());
        assertEquals(6_000L, reclaimed.earnedAmount());
        assertTrue(repo.load(signId).isEmpty());
    }
}
