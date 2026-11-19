package codes.castled.allium.economy;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.OfflinePlayer;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static codes.castled.allium.economy.EconomyResult.*;
import static codes.castled.allium.economy.EconomyRequest.Kind.*;

class VaultEconomyProviderTest {
    @TempDir Path temp;
    String url;EconomyEngine engine;UUID id=UUID.randomUUID();OfflinePlayer player;VaultEconomyProvider provider;
    @BeforeEach void setup()throws Exception{
        url="jdbc:sqlite:"+temp.resolve("vault.db");engine=new EconomyEngine(()->DriverManager.getConnection(url),EconomySettings.defaults(),(c,a,b)->true);
        player=mock(OfflinePlayer.class);when(player.getUniqueId()).thenReturn(id);
        provider=provider(engine);assertTrue(provider.createPlayerAccount(player));
    }
    VaultEconomyProvider provider(EconomyEngine e){return new VaultEconomyProvider(e,name->name.equals("Alice")?id:null,e::ready,e::balance,e::execute);}
    @Test void doubleResidueIsAcceptedButInvalidOrZeroMutationsHaveNoEffect(){
        assertTrue(provider.depositPlayer(player,.1+.2).transactionSuccess());assertEquals(100.3,provider.getBalance(player));
        for(double bad:new double[]{0,-0.0,-1,.001,1.001,Double.NaN,Double.POSITIVE_INFINITY})assertFalse(provider.withdrawPlayer(player,bad).transactionSuccess());
        assertEquals(100.3,provider.getBalance(player));
    }
    @Test void missingReadsNeverOpenAndStorageFailureThrowsOnPrimitiveReads(){
        assertEquals(0,provider.getBalance("Nobody"));assertFalse(provider.hasAccount("Nobody"));
        var failed=new VaultEconomyProvider(engine,name->id,()->true,account->new Unavailable("disk unavailable"),engine::execute);
        assertThrows(EconomyUnavailableException.class,()->failed.getBalance(player));
        assertThrows(EconomyUnavailableException.class,()->failed.hasAccount("Alice"));
        assertThrows(EconomyUnavailableException.class,()->failed.has(player,0));
    }
    @Test void commitUnknownIsExplicitAndOriginalIdRecoversExactlyOnce()throws Exception {
        var broken=new EconomyEngine(()->DriverManager.getConnection(url),EconomySettings.defaults(),(c,a,b)->true,(c,op)->{try(var s=c.createStatement()){s.execute("COMMIT");}throw new SQLException("ack lost");},VaultMoney::validate);
        var response=provider(broken).depositPlayer(player,.01);assertFalse(response.transactionSuccess());assertTrue(response.errorMessage.startsWith("OUTCOME_UNKNOWN:"));
        UUID operation=UUID.fromString(response.errorMessage.substring("OUTCOME_UNKNOWN:".length()));
        var request=engine.request(operation,"VAULT",operation.toString(),DEPOSIT,"VAULT",id,null,new Money(1));
        assertTrue(((Committed)engine.execute(request)).replay());assertEquals(100.01,provider.getBalance(player));
    }
    @Test void capsRejectWithFrozenFailureBalanceAndNoPartialCredit(){
        engine.execute(engine.request(UUID.randomUUID(),"admin","cap",ADMIN_SET,"console",id,null,new Money(Money.HARD)));
        var response=provider.depositPlayer(player,.01);assertFalse(response.transactionSuccess());assertEquals("CAP",response.errorMessage);assertEquals(10_000_000_000_000.0,response.balance);
        assertEquals(response.balance,provider.getBalance(player));
    }
    @Test void stringWorldOverloadsUseLocalUuidResolverOnly(){
        assertTrue(provider.hasAccount("Alice","world"));assertTrue(provider.depositPlayer("Alice","world",.01).transactionSuccess());
        assertEquals(100.01,provider.getBalance(player,"another-world"));assertFalse(provider.createPlayerAccount("unknown"));
        verify(player,atLeastOnce()).getUniqueId();verifyNoMoreInteractions(player);
    }
    @Test void recoveryRefusesIncompleteCommittedJournalBeforeReadiness()throws Exception {
        try(var c=DriverManager.getConnection(url);var s=c.createStatement()){s.execute("UPDATE allium_economy_operations SET result_code='PENDING'");}
        assertThrows(SQLException.class,()->new EconomyEngine(()->DriverManager.getConnection(url),EconomySettings.defaults(),(c,a,b)->true));
    }
    @Test void egressFailureIsValidatedBeforeCommit()throws Exception {
        var rejecting=new EconomyEngine(()->DriverManager.getConnection(url),EconomySettings.defaults(),(c,a,b)->true,
                (c,op)->{try(var s=c.createStatement()){s.execute("COMMIT");}},receipt->{throw new IllegalArgumentException("egress mismatch");});
        assertFalse(provider(rejecting).depositPlayer(player,1).transactionSuccess());assertEquals(100,provider.getBalance(player));
    }
    @Test void taxAccountOpensAtZeroViaVaultAndReceivesDepositsByName(){
        assertFalse(provider.hasAccount("Tax"));
        assertTrue(provider.createPlayerAccount("Tax"));
        assertTrue(provider.hasAccount("Tax"));
        assertEquals(0.0,provider.getBalance("Tax"));
        assertTrue(provider.depositPlayer("Tax",12.50).transactionSuccess());
        assertEquals(12.50,provider.getBalance("Tax"));
        UUID taxUuid=TaxSettings.defaults().accountUuid();
        assertEquals(1250,((Found)engine.balance(taxUuid)).available().minor());
    }
}
