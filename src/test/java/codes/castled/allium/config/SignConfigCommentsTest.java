package codes.castled.allium.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SignConfigCommentsTest {
 @TempDir Path dir;
 @Test void allDisabledChecklistSurvivesRepeatedNormalization() throws Exception {
  Path path=dir.resolve("config.yml");Files.writeString(path,"enabledSigns: []\nsign-use-per-second: 4\n");
  var yaml=new YamlConfiguration();yaml.load(path.toFile());YamlFiles.normalize(path,yaml);
  String first=Files.readString(path);assertTrue(first.contains("  #- balance"));assertTrue(first.contains("  #- repair"));
  yaml.load(path.toFile());YamlFiles.normalize(path,yaml);assertEquals(first,Files.readString(path));assertTrue(yaml.getStringList("enabledSigns").isEmpty());
  assertEquals("enabledSigns: []\nsign-use-per-second: 4\n",Files.readString(dir.resolve("config.yml.before-format")));
 }
 @Test void uncommentingTypeEnablesOnlyThatTypeAndRetainsOtherHints() throws Exception {
  Path path=dir.resolve("config.yml");String template=SignConfigComments.format("enabledSigns: []\nsign-use-per-second: 4\n",java.util.List.of());Files.writeString(path,template.replace("#- repair","- repair"));
  var yaml=new YamlConfiguration();yaml.load(path.toFile());YamlFiles.normalize(path,yaml);
  assertEquals(java.util.List.of("repair"),yaml.getStringList("enabledSigns"));assertTrue(Files.readString(path).contains("  - repair"));assertTrue(Files.readString(path).contains("  #- balance"));
 }
 @Test void colourCanonicalizationAndUnknownEntriesPreserveConfiguredValues() throws Exception {
  String text=SignConfigComments.format("enabledSigns:\n- colour\n- custom\n# Click budget\nsign-use-per-second: 4\n",java.util.List.of("colour","custom"));
  var yaml=new YamlConfiguration();yaml.loadFromString(text);assertEquals(java.util.List.of("color","custom"),yaml.getStringList("enabledSigns"));assertTrue(text.contains("# Click budget"));assertEquals(4,yaml.getInt("sign-use-per-second"));
 }
}
