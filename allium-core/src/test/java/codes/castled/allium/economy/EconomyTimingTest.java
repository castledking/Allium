package codes.castled.allium.economy;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static codes.castled.allium.economy.EconomyResult.*;
class EconomyTimingTest {
    @TempDir Path path;
    String url(){return "jdbc:sqlite:"+path.resolve("timing.db");}
    EconomyEngine engine()throws Exception{return new EconomyEngine(()->DriverManager.getConnection(url()),EconomySettings.defaults(),(c,a,b)->true);}
    EconomyRequest open(EconomyEngine e,UUID id){return e.request(UUID.randomUUID(),"test",UUID.randomUUID().toString(),EconomyRequest.Kind.OPEN,"test",id,null,new Money(10000));}
    @Test void timingIsOffByDefaultAndDoesNotChangeReadOutcomes()throws Exception{
        var e=engine();assertNull(EconomyTiming.CURRENT.get());assertInstanceOf(Missing.class,e.balance(UUID.randomUUID()));assertNull(EconomyTiming.CURRENT.get());
    }
    @Test void observerCannotChangeCommittedOutcomeAndScopeIsRemoved()throws Exception{
        var e=engine();e.observeTiming(s->{throw new IllegalStateException("broken diagnostics");});var id=UUID.randomUUID();
        assertInstanceOf(Committed.class,e.execute(open(e,id)));assertInstanceOf(Found.class,e.balance(id));assertNull(EconomyTiming.CURRENT.get());
    }
    @Test void phasesSeparateConnectionSqlAndCommitWithoutDoubleCounting()throws Exception{
        var e=new EconomyEngine(()->{pause();return DriverManager.getConnection(url());},EconomySettings.defaults(),(c,a,b)->true,(c,id)->{pause();try(var s=c.createStatement()){s.execute("COMMIT");}});
        List<EconomyTiming.Sample> samples=new ArrayList<>();e.observeTiming(samples::add);assertInstanceOf(Committed.class,e.execute(open(e,UUID.randomUUID())));
        var s=samples.get(0);assertTrue(s.connectionNanos()>=5_000_000);assertTrue(s.commitNanos()>=5_000_000);assertTrue(s.statementNanos()>0);
        assertEquals(s.totalNanos(),s.connectionNanos()+s.commitNanos()+s.statementNanos()+s.coordinatorNanos()+s.otherNanos());assertNull(EconomyTiming.CURRENT.get());
    }
    @Test void dedicatedReadOnlyWalReaderSeesOnlyCommittedStateAndRefreshesEachCall()throws Exception{
        var e=engine();UUID id=UUID.randomUUID();assertInstanceOf(Committed.class,e.execute(open(e,id)));
        try(var physical=DriverManager.getConnection("jdbc:sqlite:file:"+path.resolve("timing.db")+"?mode=ro")){
            codes.castled.allium.storage.ConnectionSource read=()->(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("close"))return null;
                try{return method.invoke(physical,args);}catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}
            });
            try(var c=DriverManager.getConnection(url());var s=c.createStatement()){
                s.execute("BEGIN IMMEDIATE");s.execute("UPDATE allium_economy_accounts SET balance_minor=20000");
                assertEquals(10000,((Found)e.balance(id,read)).book().minor());s.execute("COMMIT");assertEquals(20000,((Found)e.balance(id,read)).book().minor());
            }
            try(var c=read.connection();var s=c.createStatement()){assertThrows(SQLException.class,()->s.execute("UPDATE allium_economy_accounts SET balance_minor=300"));}
        }
    }
    static void pause()throws SQLException{try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException(e);}}
}
