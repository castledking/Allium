package codes.castled.allium.economy;

import codes.castled.allium.preference.*;
import codes.castled.allium.messaging.IgnoreRepository;
import codes.castled.allium.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class EconomyPolicyTest {
    @TempDir Path temp;
    @Test void acceptanceOverrideAndIgnoreDirectionAreAuthoritative()throws Exception {
        String url="jdbc:sqlite:"+temp.resolve("policy.db");ConnectionSource source=()->DriverManager.getConnection(url);
        var prefs=new PreferenceRepository(source,SqlDialect.SQLITE,System::currentTimeMillis);prefs.migrate();
        var ignores=new IgnoreRepository(source,SqlDialect.SQLITE,System::currentTimeMillis);ignores.migrate();
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();var defaults=EconomySettings.defaults();
        try(var c=source.connection()){assertTrue(EconomyPaymentPolicy.permits(c,a,b,defaults));}
        prefs.apply(b,PreferenceChange.set(PreferenceKey.ACCEPT_PAYMENTS,false));
        try(var c=source.connection()){assertFalse(EconomyPaymentPolicy.permits(c,a,b,defaults));}
        prefs.apply(b,PreferenceChange.set(PreferenceKey.ACCEPT_PAYMENTS,true));ignores.add(a,b);
        try(var c=source.connection()){assertTrue(EconomyPaymentPolicy.permits(c,a,b,defaults));}
        ignores.add(b,a);try(var c=source.connection()){assertFalse(EconomyPaymentPolicy.permits(c,a,b,defaults));}
    }
    @Test void bothPolicyRepositoriesJoinProcessCoordinator()throws Exception {
        String url="jdbc:sqlite:"+temp.resolve("order.db");ConnectionSource source=()->DriverManager.getConnection(url);
        var prefs=new PreferenceRepository(source,SqlDialect.SQLITE,System::currentTimeMillis);prefs.migrate();
        var ignores=new IgnoreRepository(source,SqlDialect.SQLITE,System::currentTimeMillis);ignores.migrate();
        var coordinator=new EconomyCoordinator();prefs.coordinateWritesWith(coordinator);ignores.coordinateWritesWith(coordinator);
        var pool=Executors.newFixedThreadPool(2);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var hold=pool.submit(()->coordinator.call(()->{entered.countDown();release.await();return null;}));assertTrue(entered.await(1,TimeUnit.SECONDS));
            UUID a=UUID.randomUUID(),b=UUID.randomUUID();
            var write=pool.submit(()->{prefs.apply(a,PreferenceChange.set(PreferenceKey.ACCEPT_PAYMENTS,false));return ignores.add(a,b);});
            Thread.sleep(30);assertFalse(write.isDone());release.countDown();hold.get();assertTrue(write.get());
            assertFalse(prefs.load(a).orElseThrow().acceptPayments());assertTrue(ignores.load(a).contains(b));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void reloadRejectsStrandedBalancesAndRetainsPriorSettings()throws Exception {
        String url="jdbc:sqlite:"+temp.resolve("reload.db");var engine=new EconomyEngine(()->DriverManager.getConnection(url),EconomySettings.defaults(),(c,a,b)->true);
        UUID a=UUID.randomUUID();var open=engine.request(UUID.randomUUID(),"test","open",EconomyRequest.Kind.OPEN,"SYSTEM",a,null,new Money(10000));engine.execute(open);
        var invalid=new EconomySettings(new Money(0),new Money(0),new Money(99),new Money(1),true,true);
        assertThrows(SQLException.class,()->engine.installSettings(invalid));assertEquals(EconomySettings.defaults(),engine.settings());
    }
}
