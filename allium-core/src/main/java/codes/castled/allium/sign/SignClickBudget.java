package codes.castled.allium.sign;
import java.util.*;
/** One shared monotonic window across all sign types/faces. Shared by all player regions. */
public final class SignClickBudget {
    private record Window(long start,int count,long last){}
    private final Map<UUID,Window> windows=new HashMap<>();
    public synchronized boolean admit(UUID player,long now,int limit){
        var previous=windows.get(player);
        if(previous!=null&&now-previous.last()<50_000_000L)return false;
        var window=previous==null||now-previous.start()>=1_000_000_000L?new Window(now,0,Long.MIN_VALUE):previous;
        if(window.count()>=limit)return false;
        windows.put(player,new Window(window.start(),window.count()+1,now));return true;
    }
    public synchronized void forget(UUID player){windows.remove(player);}
}
