package codes.castled.allium.chat;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ChatHistoryTest {
    final UUID a=UUID.randomUUID(),b=UUID.randomUUID();
    @Test void replayNeverBorrowsAnotherAudienceOrGlobalHistory(){var h=new ChatHistory(8,8,1000);h.add(h.nextId(),a,"Alice","staff",1,Map.of(a,"secret"));assertTrue(h.visible(b,2).isEmpty());assertEquals("secret",h.visible(a,2).get(0).rendered().get(a));}
    @Test void identicalTextHasIndependentIdsAndDeletionState(){var h=new ChatHistory(8,8,1000);var x=h.add(h.nextId(),a,"A","global",1,Map.of(a,"same"));var y=h.add(h.nextId(),a,"A","global",2,Map.of(a,"same"));assertTrue(h.delete(x.id()));assertFalse(h.delete(x.id()));assertEquals(List.of(y),h.visible(a,3));}
    @Test void delayedOriginalAndReplayShareTombstone(){var h=new ChatHistory(8,8,1000);String id=h.nextId();var m=h.add(id,a,"A","global",1,Map.of(a,"old"));h.delete(id);assertSame(m,h.add(id,a,"A","global",2,Map.of(b,"forged late copy")));assertTrue(m.deleted().get());assertTrue(h.visible(a,3).isEmpty());assertTrue(h.visible(b,3).isEmpty());}
    @Test void limitsAndExpirationBoundEachViewer(){var h=new ChatHistory(3,2,10);for(int i=0;i<5;i++)h.add(h.nextId(),a,"A","global",i,Map.of(a,"line"));assertEquals(2,h.visible(a,5).size());assertTrue(h.visible(a,15).isEmpty());}
    @Test void quittingForgetsAudienceWithoutMakingItPublic(){var h=new ChatHistory(8,8,1000);h.add(h.nextId(),a,"A","global",1,Map.of(a,"line"));h.forget(a);assertTrue(h.visible(a,2).isEmpty());assertTrue(h.visible(b,2).isEmpty());}
    @Test void restartIdentityCannotMatchOldHoverId(){var a=new ChatHistory(8,8,1000);var b=new ChatHistory(8,8,1000);assertNotEquals(a.nextId(),b.nextId());}
}
