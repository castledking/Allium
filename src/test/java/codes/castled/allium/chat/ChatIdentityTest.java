package codes.castled.allium.chat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ChatIdentityTest {
    @Test void identitySurvivesBungeeSerializationWithoutVisibleOutput(){String json=ChatIdentity.tag("{\"text\":\"hello\"}",ChatIdentity.ID+"run:4");var components=net.md_5.bungee.chat.ComponentSerializer.parse(json);assertEquals("hello",net.md_5.bungee.api.chat.TextComponent.toPlainText(components));assertEquals("run:4",ChatIdentity.metadata(net.md_5.bungee.chat.ComponentSerializer.toString(components)).id());}
    @Test void identitySurvivesAdventureCompaction(){var gson=net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson();String tagged=ChatIdentity.tag("{\"text\":\"hello\"}",ChatIdentity.ID+"run:9");String compacted=gson.serialize(gson.deserialize(tagged).compact());assertEquals("run:9",ChatIdentity.metadata(compacted).id());}
    @Test void replayRetainsOriginalIdentityAndHasSeparateMarker(){String json=ChatIdentity.tag(ChatIdentity.tag("{\"text\":\"hello\"}",ChatIdentity.ID+"run:4"),ChatIdentity.REPLAY);assertEquals("run:4",ChatIdentity.metadata(json).id());assertTrue(ChatIdentity.metadata(json).replay());}
    @Test void quotedTextCannotForgeMetadata(){String json="{\"text\":\"{\\\"insertion\\\":\\\"allium:chat:id:fake\\\"}\"}";assertNull(ChatIdentity.metadata(json).id());}
    @Test void unknownComponentFieldsRemainInTheForeignPacket(){String json=ChatIdentity.tag("{\"text\":\"foreign\",\"custom_future_field\":{\"value\":7}}",ChatIdentity.ID+"foreign:1");assertTrue(json.contains("custom_future_field"));assertEquals("foreign:1",ChatIdentity.metadata(json).id());}
}
