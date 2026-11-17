package codes.castled.allium.item.library;
import codes.castled.allium.PluginStart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class LegacyItemDatabaseTest {
 @TempDir Path dir;
 @Test void fullCompressedDatabaseExportsOnceAndPreservesOperatorFile()throws Exception{var p=mock(PluginStart.class);when(p.getDataFolder()).thenReturn(dir.toFile());when(p.getResource("itemdb.yml.gz")).thenAnswer(c->getClass().getClassLoader().getResourceAsStream("itemdb.yml.gz"));var db=LegacyItemDatabase.load(p);assertEquals("POTION;POISON;120",db.lookup("acid2pot"));assertEquals("SPAWNER;ALLAY",db.lookup("allay_spawner"));assertEquals("GRANITE",db.lookup("1:1"));assertNull(db.lookup("allium:acid2pot"));assertTrue(db.complete("allium:",List.of("allium:mob_disarmer")).contains("allium:mob_disarmer"));Files.writeString(dir.resolve("itemdb.yml"),"aliases:\n  custom: DIAMOND\n");assertEquals("DIAMOND",LegacyItemDatabase.load(p).lookup("custom"));}
 @Test void canonicalMaterialsWinAndCompletionIsBounded(){var db=new LegacyItemDatabase(Map.of("stone","DIAMOND","dsword","DIAMOND_SWORD","bad","NO_SUCH_MATERIAL"));assertNull(db.lookup("stone"));assertEquals("DIAMOND_SWORD",db.lookup("dsword"));assertTrue(db.complete("diamond_s",List.of()).contains("diamond_sword"));assertFalse(db.complete("bad",List.of()).contains("bad"));assertTrue(db.complete("",List.of()).size()<=64);}
}
