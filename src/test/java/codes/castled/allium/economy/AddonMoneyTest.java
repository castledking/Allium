package codes.castled.allium.economy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static codes.castled.allium.economy.EconomyRequest.Kind.OPEN;
import static org.junit.jupiter.api.Assertions.*;

class AddonMoneyTest {
    @TempDir Path temp;
    EconomyEngine engine;
    final UUID player = UUID.randomUUID();
    final AtomicBoolean up = new AtomicBoolean(true);
    AddonMoney money;

    @BeforeEach
    void setup() throws Exception {
        String url = "jdbc:sqlite:" + temp.resolve("addon.db");
        engine = new EconomyEngine(() -> DriverManager.getConnection(url), EconomySettings.defaults(), (c, a, b) -> true);
        engine.execute(engine.request(UUID.randomUUID(), "OPEN", player.toString(), OPEN, "SYSTEM", player, null,
                engine.settings().starting()));
        money = new AddonMoney("Curios", () -> up.get()
                ? new AddonMoney.Ledger(engine, engine::balance, engine::execute, amount -> "$" + amount.toPlainString())
                : null);
    }

    private long cents() {
        return ((EconomyResult.Found) engine.balance(player)).available().minor();
    }

    @Test
    void withdrawsAndRefundsAgainstTheJournalledBalance() {
        assertTrue(money.available());
        assertTrue(money.has(player, 100));
        assertFalse(money.has(player, 100.01));
        assertTrue(money.withdraw(player, 40));
        assertEquals(6000, cents());
        assertFalse(money.withdraw(player, 60.01), "a withdraw beyond the balance takes nothing");
        assertEquals(6000, cents());
        assertTrue(money.deposit(player, 40));
        assertEquals(10000, cents());
        assertEquals("$1500.00", money.format(1500));
    }

    @Test
    void invalidAmountsAndAnOffEconomyChangeNothing() {
        for (double bad : new double[]{0, -1, 0.001, Double.NaN}) assertFalse(money.withdraw(player, bad));
        assertFalse(money.withdraw(UUID.randomUUID(), 1), "no account, nothing taken");
        up.set(false);
        assertFalse(money.available());
        assertFalse(money.has(player, 1));
        assertFalse(money.withdraw(player, 1));
        assertEquals(10000, cents());
        assertEquals("$1,500.00", money.format(1500));
    }
}
