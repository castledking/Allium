package codes.castled.allium.chat;
import codes.castled.allium.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ChatRepositoryTest {
    @TempDir Path directory;
    ChatRepository repository()throws Exception{var r=new ChatRepository(()->DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("chat.db")),SqlDialect.SQLITE);r.migrate();return r;}
    @Test void rawNicknameAndChannelChoicePersistWithoutStartingDefaults()throws Exception{UUID id=UUID.randomUUID();var r=repository();assertNull(r.load(id).nickname());assertEquals("",r.load(id).writeChannel());r.nickname(id,"<gradient:#ff0000:#00ff00>Alice</gradient>");r.channels(id,"staff-chat",Set.of("staff-chat","default"));var restarted=repository();assertEquals(1,restarted.load(id).version());assertEquals(Set.of("staff-chat","default"),restarted.load(id).reads());assertTrue(restarted.load(id).nickname().contains("gradient"));}
    @Test void resetKeepsVersionedRowAndDoesNotEraseChannelChoice()throws Exception{var r=repository();UUID id=UUID.randomUUID();r.nickname(id,"A");r.channels(id,"default",Set.of("default"));r.nickname(id,null);assertNull(r.load(id).nickname());assertEquals(2,r.load(id).version());assertEquals("default",r.load(id).writeChannel());}
    @Test void unknownStoredFormatFailsClosed()throws Exception{var r=repository();UUID id=UUID.randomUUID();r.nickname(id,"A");try(var c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("chat.db"));var s=c.createStatement()){s.executeUpdate("UPDATE allium_nicknames SET format_version=99");}assertThrows(SQLException.class,()->r.load(id));}
    @Test void channelEncodingIsCanonicalAndRejectsInjection(){assertEquals("a,z",ChatRepository.encodeReads(Set.of("z","a")));assertThrows(IllegalArgumentException.class,()->ChatRepository.decodeReads("staff,../global"));assertThrows(IllegalArgumentException.class,()->ChatRepository.decodeReads("a,,b"));}
}
