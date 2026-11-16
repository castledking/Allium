package codes.castled.allium.chat;
/** No external service is contacted unless its plugin and an explicit destination are configured. */
public interface ChatRelay extends AutoCloseable {
    void send(ChatHistory.Message message,ChatSettings.Channel channel,String player,String body);
    void delete(String id);
    @Override default void close(){}
    ChatRelay NONE=new ChatRelay(){public void send(ChatHistory.Message m,ChatSettings.Channel c,String p,String b){}public void delete(String id){}};
}
