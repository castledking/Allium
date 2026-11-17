package codes.castled.allium.item.library;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
class PotionAdapterTest {
 @Test void legacyBasePotionsRetainExtendedAndUpgradedFlags(){
  var meta=mock(PotionMeta.class);
  PotionAdapter.basePotion(meta,"long_swiftness");verify(meta).setBasePotionData(new PotionData(PotionType.SPEED,true,false));
  PotionAdapter.basePotion(meta,"strong_regeneration");verify(meta).setBasePotionData(new PotionData(PotionType.REGEN,false,true));
 }
}
