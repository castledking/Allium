package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.config.TradingCardsConfig;
import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** {@code drops.delivery}: on the ground where the mob died, or into the inventory. */
class DropDeliveryConfigTest {

    @Test
    void theShippedConfigDropsOnTheGround() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.load(new File("src/main/resources/tradingcards/config.yml"));
        assertFalse(TradingCardsConfig.load(yaml).config().drops().toInventory());
    }

    @Test
    void aConfigWithoutTheSectionDropsOnTheGround() {
        // Servers already running keep their old config.yml.
        assertFalse(TradingCardsConfig.load(new YamlConfiguration()).config().drops().toInventory());
    }

    @Test
    void inventoryDeliveryIsReadAndAnUnknownValueWarns() {
        var yaml = new YamlConfiguration();
        yaml.set("drops.delivery", "Inventory");
        assertTrue(TradingCardsConfig.load(yaml).config().drops().toInventory());

        yaml.set("drops.delivery", "backpack");
        var result = TradingCardsConfig.load(yaml);
        assertFalse(result.config().drops().toInventory());
        assertTrue(result.issues().stream().anyMatch(i -> i.path().equals("drops.delivery")));
    }
}
