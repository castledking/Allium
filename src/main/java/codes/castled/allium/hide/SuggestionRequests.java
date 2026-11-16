package codes.castled.allium.hide;
import java.util.*;

/** Per-connection request correlation. Reused IDs are poisoned rather than overwritten. */
public final class SuggestionRequests {
    public record Request(String input,long expires,boolean poisoned) {}
    private final Map<UUID,LinkedHashMap<Integer,Request>> players=new HashMap<>();
    public synchronized boolean add(UUID player,int id,String input,long now){
        if(player==null||input==null||input.length()>4096)return false;
        prune(player,now);var map=players.computeIfAbsent(player,k->new LinkedHashMap<>());
        if(map.containsKey(id)){map.put(id,new Request("",now+30_000,true));return false;}
        if(map.size()>=32)return false;map.put(id,new Request(input,now+30_000,false));return true;
    }
    public synchronized Request take(UUID player,int id,long now){prune(player,now);var map=players.get(player);if(map==null)return null;Request r=map.get(id);if(r!=null&&!r.poisoned())map.remove(id);if(map.isEmpty())players.remove(player);return r;}
    public synchronized void forget(UUID player){players.remove(player);}
    public synchronized void clear(){players.clear();}
    private void prune(UUID player,long now){var map=players.get(player);if(map==null)return;map.values().removeIf(r->r.expires()<now);if(map.isEmpty())players.remove(player);}
}
