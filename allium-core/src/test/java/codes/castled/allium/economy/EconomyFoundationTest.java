package codes.castled.allium.economy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class EconomyFoundationTest {
    @TempDir Path temp;
    @Test void exactMinorUnitsAndTrailingZeros() {
        assertEquals(123,Money.parse("1.2300",false).minor());
        assertEquals("-0.01",new Money(-1).toString());
        assertEquals(Money.HARD,Money.parse("10000000000000.00",false).minor());
    }
    @Test void commandGrammarRefusesNonDecimalTokens() {
        for(String text:new String[]{"1e2","+1"," 1","1,000","$1","NaN","-1","1.001","9".repeat(65)})
            assertThrows(RuntimeException.class,()->Money.parse(text,false),text);
        assertEquals(-100,Money.parse("-1",true).minor());
    }
    @Test void checkedArithmeticAndStrictCorePrecision() {
        assertThrows(RuntimeException.class,()->new Money(Money.HARD).plus(new Money(1)));
        assertThrows(RuntimeException.class,()->Money.exact(new BigDecimal("0.30000000000000004")));
        assertThrows(RuntimeException.class,()->new Money(0).positive());
    }
    @Test void settingsRejectInconsistentBounds() {
        assertThrows(IllegalArgumentException.class,()->new EconomySettings(new Money(1),new Money(0),new Money(0),new Money(1),true,true));
        assertThrows(IllegalArgumentException.class,()->new EconomySettings(new Money(100),new Money(0),new Money(99),new Money(1),true,true));
        assertThrows(IllegalArgumentException.class,()->new EconomySettings(new Money(0),new Money(0),new Money(99),new Money(0),true,true));
    }
    @Test void fingerprintIncludesPaymentPolicy() {
        var d=EconomySettings.defaults();
        assertEquals(d.fingerprint(),EconomySettings.defaults().fingerprint());
        assertNotEquals(d.fingerprint(),new EconomySettings(d.starting(),d.minimum(),d.maximum(),d.minimumPay(),false,true).fingerprint());
    }
    @Test void unavailableAndMissingAreDistinctFromFound() {
        assertNotEquals(new EconomyResult.Missing(),new EconomyResult.Unavailable("storage"));
        var found=new EconomyResult.Found(new Money(100),new Money(20),1);
        assertEquals(80,found.available().minor());
    }
    @Test void sqliteMigrationIsIdempotentAndHasNoPlayerForeignKey() throws Exception {
        String url="jdbc:sqlite:"+temp.resolve("money.db");
        EconomySchema.migrate(()->DriverManager.getConnection(url)); EconomySchema.migrate(()->DriverManager.getConnection(url));
        try(var c=DriverManager.getConnection(url);var s=c.createStatement()) {
            assertFalse(s.executeQuery("PRAGMA foreign_key_list("+EconomySchema.ACCOUNTS+")").next());
            assertEquals(2,s.executeQuery("SELECT version FROM allium_schema WHERE component='economy'").getInt(1));
        }
    }
    @Test void sqliteRejectsRealMoneyStorage() throws Exception {
        String url="jdbc:sqlite:"+temp.resolve("real.db"); EconomySchema.migrate(()->DriverManager.getConnection(url));
        try(var c=DriverManager.getConnection(url);var s=c.createStatement()) {
            assertThrows(java.sql.SQLException.class,()->s.execute("INSERT INTO "+EconomySchema.ACCOUNTS+" VALUES('id',1.25,0,0,0,0)"));
        }
    }
    @Test void mismatchedExistingSchemaIsRefused() throws Exception {
        String url="jdbc:sqlite:"+temp.resolve("bad.db");
        try(var c=DriverManager.getConnection(url);var s=c.createStatement()){s.execute("CREATE TABLE "+EconomySchema.ACCOUNTS+"(account_uuid TEXT PRIMARY KEY,balance_minor REAL)");}
        assertThrows(java.sql.SQLException.class,()->EconomySchema.migrate(()->DriverManager.getConnection(url)));
    }
}
