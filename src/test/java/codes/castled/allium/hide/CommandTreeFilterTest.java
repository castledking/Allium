package codes.castled.allium.hide;
import com.github.retrooper.packetevents.protocol.chat.Node;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CommandTreeFilterTest {
 @org.junit.jupiter.api.BeforeAll static void packetApi(){var api=org.mockito.Mockito.mock(com.github.retrooper.packetevents.PacketEventsAPI.class);org.mockito.Mockito.when(api.getSettings()).thenReturn(new com.github.retrooper.packetevents.settings.PacketEventsSettings());com.github.retrooper.packetevents.PacketEvents.setAPI(api);}
 @org.junit.jupiter.api.AfterAll static void clearApi(){com.github.retrooper.packetevents.PacketEvents.setAPI(null);}

 Node node(byte flags,String name,Integer... children){return new Node(flags,new ArrayList<>(List.of(children)),0,name,(Integer)null,null,null);}
 @Test void compactsHiddenRootsAndDoesNotMutateInput(){var root=node(Node.TYPE_ROOT,null,1,2);var hidden=node(Node.TYPE_LITERAL,"hidden",3);var visible=node(Node.TYPE_LITERAL,"visible");var secret=node(Node.TYPE_LITERAL,"secret");var t=CommandTreeFilter.filter(List.of(root,hidden,visible,secret),0,s->s.equals("visible"),Set.of());assertEquals(2,t.nodes().size());assertEquals(List.of(1),t.nodes().get(0).getChildren());assertEquals(List.of(1,2),root.getChildren());assertEquals("visible",t.nodes().get(1).getName().orElseThrow());}
 @Test void flattensScopedCachedLiteralsToServerSuggestions(){var t=CommandTreeFilter.filter(List.of(node(Node.TYPE_ROOT,null,1),node(Node.TYPE_LITERAL,"party",2),node(Node.TYPE_LITERAL,"admin")),0,s->true,Set.of("party"));assertEquals(3,t.nodes().size());assertEquals("args",t.nodes().get(2).getName().orElseThrow());assertEquals("minecraft:ask_server",t.nodes().get(2).getSuggestionsType().orElseThrow().toString());}
 @Test void redirectsCannotRetainHiddenRootEvenWhenNested(){var redirect=node((byte)(Node.TYPE_LITERAL|Node.FLAG_REDIRECT),"child");redirect.setRedirectNodeIndex(3);var t=CommandTreeFilter.filter(List.of(node(Node.TYPE_ROOT,null,1,3),node(Node.TYPE_LITERAL,"alias",2),redirect,node(Node.TYPE_LITERAL,"hidden")),0,s->s.equals("alias"),Set.of());assertEquals(3,t.nodes().size());assertTrue(t.nodes().stream().noneMatch(n->n.getName().orElse("").equals("hidden")));}
 @Test void remapsNonzeroRootAndRejectsInvalidReferences(){var t=CommandTreeFilter.filter(List.of(node(Node.TYPE_LITERAL,"ok"),node(Node.TYPE_ROOT,null,0)),1,s->true,Set.of());assertEquals(0,t.root());assertEquals("ok",t.nodes().get(1).getName().orElseThrow());assertThrows(IllegalArgumentException.class,()->CommandTreeFilter.filter(List.of(node(Node.TYPE_ROOT,null,5)),0,s->true,Set.of()));}
}
