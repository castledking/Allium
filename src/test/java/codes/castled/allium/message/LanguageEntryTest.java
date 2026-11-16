package codes.castled.allium.message;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class LanguageEntryTest {
    @Test void loaderReadsListTextAndLeavesVolumeForServerDefault(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root)throws Exception{
        var manager=loader(root,"en_US","teleport:\n  success:\n    - 'hello {target}'\n    - '[sound] block.note_block.pling 1.35'\n");
        assertEquals("hello {target}",manager.get(Messages.TELEPORT_SUCCESS));assertNull(manager.getSound(Messages.TELEPORT_SUCCESS).volume());assertEquals(1.35,manager.getSound(Messages.TELEPORT_SUCCESS).pitch());
    }
    @Test void explicitSilentTranslationDoesNotInheritEnglishSound(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root)throws Exception{
        java.nio.file.Files.createDirectories(root.resolve("lang"));java.nio.file.Files.writeString(root.resolve("lang/es_ES.yml"),"teleport:\n  success: ['silencioso', '[sound] none']\n");
        var manager=loader(root,"es_ES","teleport:\n  success: ['hello', '[sound] block.note_block.pling']\n");assertNull(manager.getSound(Messages.TELEPORT_SUCCESS));
    }
    private LanguageManager loader(java.nio.file.Path root,String locale,String text)throws Exception{
        java.nio.file.Files.createDirectories(root.resolve("lang"));java.nio.file.Files.writeString(root.resolve("lang/en_US.yml"),text);
        var plugin=org.mockito.Mockito.mock(codes.castled.allium.PluginStart.class);var config=new YamlConfiguration();config.set("settings.locale",locale);org.mockito.Mockito.when(plugin.getConfig()).thenReturn(config);org.mockito.Mockito.when(plugin.getDataFolder()).thenReturn(root.toFile());org.mockito.Mockito.when(plugin.logger()).thenReturn(org.mockito.Mockito.mock(codes.castled.allium.util.AlliumLogger.class));
        var manager=new LanguageManager(plugin,org.mockito.Mockito.mock(codes.castled.allium.config.ResourceSyncManager.class));manager.load();return manager;
    }
    @Test void oneNumberIsPitchAndVolumeRemainsUnset(){var e=LanguageEntry.read(List.of("hello","[sound] block.note_block.pling 1.35"));assertEquals("hello",e.text());assertEquals(new MessageSoundSpec("minecraft:block.note_block.pling",null,1.35),e.sound());}
    @Test void explicitNamespaceAndVolumeArePreserved(){var e=LanguageEntry.read(List.of("hello","[sound] custom:bell 1.2 0.4"));assertEquals(new MessageSoundSpec("custom:bell",0.4,1.2),e.sound());}
    @Test void textLinesJoinAndDirectiveDoesNotRender(){assertEquals("one\ntwo",LanguageEntry.read(List.of("one","[sound] block.note_block.pling","two")).text());}
    @Test void omittedPitchStaysUnset(){assertNull(LanguageEntry.read(List.of("[sound] block.note_block.pling")).sound().pitch());}
    @Test void oldStringAndSectionsStillWork()throws Exception{var c=new YamlConfiguration();c.loadFromString("old: text\nsection:\n  text: hello\n  sound: minecraft:ui.button.click\n  volume: 0.4\n");assertEquals("text",LanguageEntry.read(c.get("old")).text());assertEquals(0.4,LanguageEntry.read(c.get("section")).sound().volume());}
    @Test void onlyFirstSoundIsSelectedAndNoneDisables(){assertNull(LanguageEntry.read(List.of("[sound] none","[sound] custom:bell")).sound());}
    @Test void invalidNumericLevelsUseDefaults(){assertNull(LanguageEntry.read(List.of("[sound] custom:bell NaN")).sound().pitch());}
}
