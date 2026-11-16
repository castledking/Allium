package codes.castled.allium.hide;
import java.util.UUID;
interface HidePacketHooks extends AutoCloseable {void forget(UUID player);void close();}
