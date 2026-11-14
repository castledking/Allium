package codes.castled.allium.economy;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static codes.castled.allium.economy.EconomyRequest.Kind.*;
import static codes.castled.allium.economy.EconomyResult.*;

class EconomyEngineTest {
    @TempDir Path temp;
    String url;EconomyEngine engine;UUID a=UUID.randomUUID(),b=UUID.randomUUID();
    @BeforeEach void setup()throws Exception{
        url="jdbc:sqlite:"+temp.resolve("engine.db");engine=new EconomyEngine(()->DriverManager.getConnection(url),EconomySettings.defaults(),(c,x,y)->true);
    }
    EconomyRequest q(EconomyRequest.Kind kind,UUID source,UUID recipient,long amount){return engine.request(UUID.randomUUID(),"test",UUID.randomUUID().toString(),kind,"console",source,recipient,new Money(amount));}
    void open(UUID id){assertInstanceOf(Committed.class,engine.execute(q(OPEN,id,null,10_000)));}
    long balance(UUID id){return ((Found)engine.balance(id)).book().minor();}
    long count(String table)throws Exception{try(var c=DriverManager.getConnection(url);var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}
    @Test void readsNeverCreateAndOpenGrantsExactlyOnce()throws Exception{
        assertInstanceOf(Missing.class,engine.balance(a));assertEquals(0,count(EconomySchema.ACCOUNTS));open(a);open(a);
        assertEquals(10_000,balance(a));assertEquals(1,count(EconomySchema.ENTRIES));
    }
    @Test void payCommitsBalancedEntriesAndFrozenReceipt()throws Exception{
        open(a);open(b);var request=q(PAY,a,b,250);var first=(Committed)engine.execute(request);
        assertEquals(9750,balance(a));assertEquals(10250,balance(b));
        assertInstanceOf(Committed.class,engine.execute(q(DEPOSIT,b,null,100)));
        var replay=(Committed)engine.execute(request);assertTrue(replay.replay());assertEquals(first.source(),replay.source());assertEquals(first.recipient(),replay.recipient());
        try(var c=DriverManager.getConnection(url);var s=c.prepareStatement("SELECT SUM(book_delta) FROM "+EconomySchema.ENTRIES+" WHERE operation_id=?")){s.setString(1,request.id().toString());try(var r=s.executeQuery()){r.next();assertEquals(0,r.getLong(1));}}
    }
    @Test void insufficientFundsAndRecipientCapRollBackBothLegs()throws Exception{
        open(a);open(b);assertInstanceOf(Rejected.class,engine.execute(q(PAY,a,b,10001)));
        assertInstanceOf(Committed.class,engine.execute(q(ADMIN_SET,b,null,Money.HARD)));
        long entries=count(EconomySchema.ENTRIES);assertEquals("CAP",((Rejected)engine.execute(q(PAY,a,b,1))).reason());
        assertEquals(10000,balance(a));assertEquals(Money.HARD,balance(b));assertEquals(entries,count(EconomySchema.ENTRIES));
    }
    @Test void sameKeyDifferentPayloadConflictsWithoutEffect(){
        open(a);var first=q(DEPOSIT,a,null,10);assertInstanceOf(Committed.class,engine.execute(first));
        var conflicting=new EconomyRequest(UUID.randomUUID(),first.origin(),first.key(),DEPOSIT,"console",a,null,new Money(11),first.settings());
        assertEquals("IDEMPOTENCY_CONFLICT",((Rejected)engine.execute(conflicting)).reason());assertEquals(10010,balance(a));
    }
    @Test void reusedOperationIdAndDifferentExistingKeyCannotSelectArbitraryReceipt() {
        open(a);var first=q(DEPOSIT,a,null,1);var second=q(DEPOSIT,a,null,2);
        assertInstanceOf(Committed.class,engine.execute(first));assertInstanceOf(Committed.class,engine.execute(second));
        var collision=new EconomyRequest(first.id(),second.origin(),second.key(),second.kind(),second.actor(),second.source(),null,second.amount(),second.settings());
        assertEquals("IDEMPOTENCY_CONFLICT",((Rejected)engine.execute(collision)).reason());assertEquals(10003,balance(a));
    }

    @Test void authoritativePolicyIsReadOnTheMoneyConnection()throws Exception{
        open(a);open(b);
        try(var c=DriverManager.getConnection(url);var s=c.createStatement()){s.execute("CREATE TABLE policy(allowed INTEGER)");s.execute("INSERT INTO policy VALUES(0)");}
        var policyEngine=new EconomyEngine(()->DriverManager.getConnection(url),EconomySettings.defaults(),(c,x,y)->{try(var s=c.createStatement();var r=s.executeQuery("SELECT allowed FROM policy")){r.next();return r.getInt(1)==1;}});
        assertEquals("REFUSED",((Rejected)policyEngine.execute(q(PAY,a,b,1))).reason());
        try(var c=DriverManager.getConnection(url);var s=c.createStatement()){s.execute("UPDATE policy SET allowed=1");}
        assertInstanceOf(Committed.class,policyEngine.execute(q(PAY,a,b,1)));
    }
    @Test void concurrentDebitsCannotOverspendFiniteBalance()throws Exception{
        open(a);assertInstanceOf(Committed.class,engine.execute(q(ADMIN_SET,a,null,100)));
        var pool=Executors.newFixedThreadPool(8);try{
            List<Callable<Mutation>> tasks=new ArrayList<>();for(int i=0;i<40;i++)tasks.add(()->engine.execute(q(WITHDRAW,a,null,10)));
            long committed=0;for(var f:pool.invokeAll(tasks))if(f.get() instanceof Committed)committed++;
            assertEquals(10,committed);assertEquals(0,balance(a));
        }finally{pool.shutdownNow();}
    }
    @Test void separateConnectionOwnersCannotOverspendThroughTheSqlBoundary() throws Exception {
        open(a);assertInstanceOf(Committed.class,engine.execute(q(ADMIN_SET,a,null,100)));
        var other=new EconomyEngine(()->DriverManager.getConnection(url),EconomySettings.defaults(),(c,x,y)->true);
        var pool=Executors.newFixedThreadPool(4);try {
            List<Callable<Mutation>> work=new ArrayList<>();
            for(int i=0;i<40;i++){var owner=i%2==0?engine:other;work.add(()->owner.execute(q(WITHDRAW,a,null,10)));}
            long committed=0;for(var f:pool.invokeAll(work))if(f.get() instanceof Committed)committed++;
            assertEquals(10,committed);assertEquals(0,balance(a));
        } finally {pool.shutdownNow();}
    }

    @Test void opposingTransfersCompleteWithoutDeadlockOrLostMoney()throws Exception{
        open(a);open(b);var pool=Executors.newFixedThreadPool(4);try{
            List<Callable<Mutation>> tasks=new ArrayList<>();for(int i=0;i<50;i++){tasks.add(()->engine.execute(q(PAY,a,b,1)));tasks.add(()->engine.execute(q(PAY,b,a,1)));}
            for(var f:pool.invokeAll(tasks,10,TimeUnit.SECONDS))assertInstanceOf(Committed.class,f.get());
            assertEquals(10000,balance(a));assertEquals(10000,balance(b));
        }finally{pool.shutdownNow();}
    }
    @Test void lostCommitAcknowledgementRecoversBySameIdReinsertion()throws Exception{
        open(a);var unreliable=new EconomyEngine(()->DriverManager.getConnection(url),EconomySettings.defaults(),(c,x,y)->true,(c,id)->{try(var s=c.createStatement()){s.execute("COMMIT");}throw new SQLException("ack lost");});
        var request=q(DEPOSIT,a,null,7);assertInstanceOf(Unknown.class,unreliable.execute(request));
        assertEquals(10007,balance(a));var recovered=(Committed)engine.execute(request);assertTrue(recovered.replay());assertEquals(10007,balance(a));
    }
    @Test void failureBeforeCommitLeavesNoOperationAndRetryAppliesOnce()throws Exception{
        open(a);var unreliable=new EconomyEngine(()->DriverManager.getConnection(url),EconomySettings.defaults(),(c,x,y)->true,(c,id)->{throw new SQLException("commit never issued");});
        var request=q(DEPOSIT,a,null,7);assertInstanceOf(Unknown.class,unreliable.execute(request));assertEquals(10000,balance(a));
        assertInstanceOf(Committed.class,engine.execute(request));assertEquals(10007,balance(a));
    }
    @Test void unavailableReadDoesNotFabricateMissing()throws Exception{
        var broken=new EconomyEngine(()->DriverManager.getConnection(url),EconomySettings.defaults(),(c,x,y)->true);
        try(var c=DriverManager.getConnection(url);var s=c.createStatement()){s.execute("DROP TABLE "+EconomySchema.ACCOUNTS);}
        assertInstanceOf(Unavailable.class,broken.balance(a));
    }
    @Test void adminSetDebtIsAuditedButWithdrawalDoesNotLend()throws Exception{
        open(a);var settings=new EconomySettings(new Money(10000),new Money(-10000),new Money(Money.HARD),new Money(1),true,true);
        var debt=new EconomyEngine(()->DriverManager.getConnection(url),settings,(c,x,y)->true);
        var set=debt.request(UUID.randomUUID(),"admin","debt",ADMIN_SET,"console",a,null,new Money(-10));assertInstanceOf(Committed.class,debt.execute(set));
        var withdraw=debt.request(UUID.randomUUID(),"vault","withdraw",WITHDRAW,"vault",a,null,new Money(1));assertInstanceOf(Rejected.class,debt.execute(withdraw));assertEquals(-10,balance(a));
    }
}
