package codes.castled.allium.hide;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class SuggestionRequestsTest {
 @Test void correlationIsolationAndConsumption(){var r=new SuggestionRequests();var a=UUID.randomUUID();var b=UUID.randomUUID();assertTrue(r.add(a,1,"/party invite ",0));assertTrue(r.add(b,1,"/mail ",0));assertEquals("/party invite ",r.take(a,1,1).input());assertNull(r.take(a,1,1));assertEquals("/mail ",r.take(b,1,1).input());}
 @Test void reusedPendingIdStaysPoisonedUntilExpiry(){var r=new SuggestionRequests();var a=UUID.randomUUID();assertTrue(r.add(a,1,"/party ",0));assertFalse(r.add(a,1,"/secret ",1));assertTrue(r.take(a,1,2).poisoned());assertTrue(r.take(a,1,3).poisoned());assertTrue(r.add(a,1,"/safe ",SuggestionRequests.TTL+2));}
 @Test void inputIsBoundedAndQuitClears(){var r=new SuggestionRequests();var a=UUID.randomUUID();assertFalse(r.add(a,0,"x".repeat(4097),0));assertTrue(r.add(a,32,"/x",0));r.forget(a);assertNull(r.take(a,32,0));assertTrue(r.add(a,32,"/x",0));r.clear();assertNull(r.take(a,32,0));}
 @Test void unansweredRequestsNeverBlockTheNewestOne(){
  var r=new SuggestionRequests();var a=UUID.randomUUID();
  // 100 requests the server never answers, then the one the player is waiting for.
  for(int i=0;i<100;i++)assertTrue(r.add(a,i,"/x"+i,0),"request "+i);
  assertTrue(r.add(a,100,"/i dia",0));
  assertEquals(SuggestionRequests.LIMIT,r.pending(a));
  assertEquals("/i dia",r.take(a,100,1).input());
 }
 @Test void retiredIdsStayPoisoned(){
  var r=new SuggestionRequests();var a=UUID.randomUUID();
  assertTrue(r.add(a,0,"/secret ",0));
  for(int i=1;i<=SuggestionRequests.LIMIT;i++)assertTrue(r.add(a,i,"/x",0));
  // id 0 was retired: a reuse is not trusted, and a late answer to it is dropped.
  assertFalse(r.add(a,0,"/visible ",1));
  assertTrue(r.take(a,0,2).poisoned());
 }
 @Test void lateAnswerToARetiredIdIsDropped(){
  var r=new SuggestionRequests();var a=UUID.randomUUID();
  assertTrue(r.add(a,0,"/secret ",0));
  for(int i=1;i<=SuggestionRequests.LIMIT;i++)assertTrue(r.add(a,i,"/x",0));
  assertTrue(r.take(a,0,1).poisoned());
  assertNull(r.take(a,0,2));
 }
 @Test void onlyAFloodPastTheRetiredSetIsRefused(){
  var r=new SuggestionRequests();var a=UUID.randomUUID();int total=SuggestionRequests.LIMIT+SuggestionRequests.RETIRED_LIMIT;
  for(int i=0;i<total;i++)assertTrue(r.add(a,i,"/x",0));
  assertFalse(r.add(a,total,"/x",0));
  assertTrue(r.add(a,total+1,"/x",SuggestionRequests.TTL+1));
 }
}
