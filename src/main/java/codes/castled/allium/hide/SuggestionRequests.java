package codes.castled.allium.hide;
import java.util.*;

/**
 * Per-connection request correlation. Reused IDs are poisoned rather than overwritten.
 *
 * <p>Some requests are never answered (a completer throws, or a plugin swallows the answer).
 * Refusing new requests once 32 such requests piled up left a player without suggestions until
 * they expired, which looked like "suggestions only come back after typing and deleting". The
 * client only waits for its newest request, so the oldest pending entry is retired instead. A
 * retired ID still acts as poisoned: an answer to it is dropped, and a request that reuses it is
 * not trusted. Only a flood that fills the retired set too is refused.
 */
public final class SuggestionRequests {
    public record Request(String input,long expires,boolean poisoned) {}
    static final int LIMIT=32;
    static final int RETIRED_LIMIT=256;
    static final long TTL=10_000;
    private static final Request RETIRED=new Request("",0,true);
    private static final class Pending {
        final LinkedHashMap<Integer,Request> live=new LinkedHashMap<>();
        final LinkedHashMap<Integer,Long> retired=new LinkedHashMap<>();
        boolean empty(){return live.isEmpty()&&retired.isEmpty();}
    }
    private final Map<UUID,Pending> players=new HashMap<>();
    public synchronized boolean add(UUID player,int id,String input,long now){
        if(player==null||input==null||input.length()>4096)return false;
        prune(player,now);var state=players.computeIfAbsent(player,k->new Pending());
        if(state.live.containsKey(id)){state.live.put(id,new Request("",now+TTL,true));return false;}
        if(state.retired.remove(id)!=null){state.retired.put(id,now+TTL);return false;}
        if(state.live.size()>=LIMIT){
            if(state.retired.size()>=RETIRED_LIMIT)return false;
            int oldest=state.live.keySet().iterator().next();
            state.retired.put(oldest,now+TTL);state.live.remove(oldest);
        }
        state.live.put(id,new Request(input,now+TTL,false));return true;
    }
    public synchronized Request take(UUID player,int id,long now){
        prune(player,now);var state=players.get(player);if(state==null)return null;
        Request r=state.live.get(id);
        if(r==null){if(state.retired.remove(id)!=null)r=RETIRED;}
        else if(!r.poisoned())state.live.remove(id);
        if(state.empty())players.remove(player);return r;
    }
    /** Pending (unanswered, not retired) requests for {@code player}. */
    synchronized int pending(UUID player){var state=players.get(player);return state==null?0:state.live.size();}
    public synchronized void forget(UUID player){players.remove(player);}
    public synchronized void clear(){players.clear();}
    private void prune(UUID player,long now){
        var state=players.get(player);if(state==null)return;
        state.live.values().removeIf(r->r.expires()<now);state.retired.values().removeIf(expires->expires<now);
        if(state.empty())players.remove(player);
    }
}
