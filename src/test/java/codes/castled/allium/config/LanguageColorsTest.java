package codes.castled.allium.config;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class LanguageColorsTest {
 @Test void stockUpgradeRetainsListSoundAndIsIdempotent(){var y=new YamlConfiguration();var value=List.of("You were kicked for being AFK.","[sound] entity.villager.no 0.8");y.set("info.afk.kick",value);assertEquals(1,LanguageColors.upgrade(y));assertEquals(List.of("&cYou were kicked for being AFK.",value.get(1)),y.getList("info.afk.kick"));assertEquals(0,LanguageColors.upgrade(y));}
 @Test void customizedNoticeIsUntouchedAndSectionSoundVolumeSurvives(){var y=new YamlConfiguration();y.set("info.afk.kick",List.of("Custom notice"));y.set("moderation.discipline.kick-notice.text","You were kicked: {message}");y.set("moderation.discipline.kick-notice.sound","custom:notice");y.set("moderation.discipline.kick-notice.volume",.25);assertEquals(1,LanguageColors.upgrade(y));assertEquals(List.of("Custom notice"),y.getList("info.afk.kick"));assertEquals("custom:notice",y.getString("moderation.discipline.kick-notice.sound"));assertEquals(.25,y.getDouble("moderation.discipline.kick-notice.volume"));}
 @Test void stockRepairUpgradeKeepsSoundAndAddsItemNames(){
  var yaml=new YamlConfiguration();
  yaml.set("utility.repair-held",List.of("{prefix} &aRepaired the held item.","[sound] block.anvil.use 1.2"));
  yaml.set("utility.repair-all","Custom repair for {count} items");
  assertEquals(1,LanguageColors.upgrade(yaml));
  assertEquals(List.of("{prefix} &aRepaired &f{items}&a.","[sound] block.anvil.use 1.2"),yaml.getList("utility.repair-held"));
  assertEquals("Custom repair for {count} items",yaml.getString("utility.repair-all"));
 }
}
