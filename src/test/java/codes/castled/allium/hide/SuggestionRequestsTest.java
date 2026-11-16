package codes.castled.allium.hide;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class SuggestionRequestsTest {
 @Test void correlationIsolationAndConsumption(){var r=new SuggestionRequests();var a=UUID.randomUUID();var b=UUID.randomUUID();assertTrue(r.add(a,1,"/party invite ",0));assertTrue(r.add(b,1,"/mail ",0));assertEquals("/party invite ",r.take(a,1,1).input());assertNull(r.take(a,1,1));assertEquals("/mail ",r.take(b,1,1).input());}
 @Test void reusedPendingIdStaysPoisonedUntilExpiry(){var r=new SuggestionRequests();var a=UUID.randomUUID();assertTrue(r.add(a,1,"/party ",0));assertFalse(r.add(a,1,"/secret ",1));assertTrue(r.take(a,1,2).poisoned());assertTrue(r.take(a,1,3).poisoned());assertTrue(r.add(a,1,"/safe ",30002));}
 @Test void queueAndInputAreBoundedAndQuitClears(){var r=new SuggestionRequests();var a=UUID.randomUUID();assertFalse(r.add(a,0,"x".repeat(4097),0));for(int i=0;i<32;i++)assertTrue(r.add(a,i,"/x",0));assertFalse(r.add(a,32,"/x",0));r.forget(a);assertTrue(r.add(a,32,"/x",0));r.clear();assertNull(r.take(a,32,0));}
}
