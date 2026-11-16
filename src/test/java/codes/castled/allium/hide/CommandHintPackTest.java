package codes.castled.allium.hide;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
class CommandHintPackTest {
 @TempDir Path temp;
 @Test void deterministicPackCoversAllLocalesWithOnlyOneTranslation()throws Exception{byte[] a=CommandHintPack.build("&c&lOops 100%"),b=CommandHintPack.build("&c&lOops 100%");assertArrayEquals(a,b);int locales=0;try(var zip=new ZipInputStream(new ByteArrayInputStream(a))){ZipEntry e;while((e=zip.getNextEntry())!=null){String data=new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);if(e.getName().endsWith(".json")){locales++;var o=JsonParser.parseString(data).getAsJsonObject();assertEquals(1,o.size());assertEquals("§c§lOops 100%%",o.get("command.unknown.command").getAsString());}}}assertEquals(142,locales);}
 @Test void ownershipMarkerRejectsForeignAndInvalidPacks()throws Exception{Path pack=temp.resolve("helper.zip");Files.write(pack,CommandHintPack.build("help"));assertTrue(CommandHintPack.owned(pack));Files.writeString(pack,"foreign");assertFalse(CommandHintPack.owned(pack));}
 @Test void exportMovesOnlyOwnedHelperAndNeverOverwritesForeignPack()throws Exception{Path g=temp.resolve("generated/helper.zip"),m=temp.resolve("mixer/helper.zip");CommandHintPack.export(new CommandHintPack.Targets(g,m,false),"hello");assertTrue(Files.exists(g));CommandHintPack.export(new CommandHintPack.Targets(g,m,true),"hello");assertFalse(Files.exists(g));assertTrue(Files.exists(m));CommandHintPack.export(new CommandHintPack.Targets(g,m,true),"");assertFalse(Files.exists(m));Files.writeString(m,"foreign");assertThrows(IOException.class,()->CommandHintPack.export(new CommandHintPack.Targets(g,m,true),"hello"));CommandHintPack.export(new CommandHintPack.Targets(g,m,true),"");assertEquals("foreign",Files.readString(m));}
 @Test void roundsHexButPreservesDecorations(){String s=CommandHintPack.languageText("<bold><#ff0000>Hi</#ff0000></bold>");assertTrue(s.contains("§l"));assertFalse(s.contains("§x"));assertTrue(s.endsWith("Hi"));assertThrows(IllegalArgumentException.class,()->CommandHintPack.build("x".repeat(4097)));}
}
