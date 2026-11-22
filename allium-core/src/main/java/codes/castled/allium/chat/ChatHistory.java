package codes.castled.allium.chat;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Recipient-only bounded history; one tombstone shared by originals, replay and relay callbacks. */
public final class ChatHistory {
    public record Message(String id,UUID sender,String senderName,String channel,long time,Map<UUID,String> rendered,AtomicBoolean deleted){
        public Message{rendered=Map.copyOf(rendered);}
    }
    private final String run=UUID.randomUUID().toString();
    private long sequence;
    private final int globalLimit,viewerLimit;
    private final long ageMillis;
    private final LinkedHashMap<String,Message> messages=new LinkedHashMap<>();
    private final Map<UUID,ArrayDeque<String>> viewers=new HashMap<>();
    public ChatHistory(int globalLimit,int viewerLimit,long ageMillis){if(globalLimit<1||viewerLimit<1||ageMillis<1)throw new IllegalArgumentException();this.globalLimit=globalLimit;this.viewerLimit=viewerLimit;this.ageMillis=ageMillis;}
    public synchronized String nextId(){return run+":"+(++sequence);}
    public synchronized Message add(String id,UUID sender,String name,String channel,long time,Map<UUID,String> rendered){
        Message prior=messages.get(id);if(prior!=null)return prior;
        var message=new Message(id,sender,name,channel,time,rendered,new AtomicBoolean());messages.put(id,message);
        for(UUID viewer:rendered.keySet()){var ids=viewers.computeIfAbsent(viewer,k->new ArrayDeque<>());ids.addLast(id);while(ids.size()>viewerLimit)ids.removeFirst();}
        trim(time);return message;
    }
    public synchronized Optional<Message> find(String id){return Optional.ofNullable(messages.get(id));}
    public synchronized boolean retained(UUID viewer, String id) {
        var index = viewers.get(viewer);
        return index != null && index.contains(id);
    }
    public synchronized List<Message> visible(UUID viewer,long now){trim(now);var out=new ArrayList<Message>();for(String id:viewers.getOrDefault(viewer,new ArrayDeque<>())){var m=messages.get(id);if(m!=null&&!m.deleted().get())out.add(m);}return List.copyOf(out);}
    public synchronized List<Message> recentBySender(UUID sender,int count){return messages.values().stream().filter(m->m.sender().equals(sender)&&!m.deleted().get()).sorted(Comparator.comparingLong(Message::time).reversed()).limit(count).toList();}
    public synchronized boolean delete(String id){var message=messages.get(id);return message!=null&&message.deleted().compareAndSet(false,true);}
    public synchronized void forget(UUID viewer){viewers.remove(viewer);}
    public synchronized void clear(){messages.clear();viewers.clear();}
    private void trim(long now){
        var iterator=messages.entrySet().iterator();while(iterator.hasNext()){var m=iterator.next().getValue();if(messages.size()>globalLimit||now-m.time()>ageMillis)iterator.remove();else break;}
        // Keep per-viewer indices bounded and remove stale identities, never substitute global history.
        viewers.values().forEach(ids->ids.removeIf(id->!messages.containsKey(id)));
    }
}
