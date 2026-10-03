package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.config.TradingCardsConfig;
import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The /reliques equip line.
 *
 * <p>Servers already running keep their old config.yml, which has no
 * {@code equip-message}, so a missing key has to mean the default rather than
 * silence — while an operator who writes {@code ""} has asked for silence.
 */
class EquipMessageConfigTest {

    @Test
    void theShippedConfigSendsAnEquipLineNamingTheCard() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.load(new File("src/main/resources/tradingcards/config.yml"));
        String message = TradingCardsConfig.load(yaml).config().levelling().announce().equipMessage();
        assertTrue(message.contains("<card>"), message);
    }

    @Test
    void aConfigWithoutTheKeyGetsTheDefault() {
        var yaml = new YamlConfiguration();
        yaml.set("levelling.announce.enabled", true);
        assertEquals(TradingCardsConfig.Announce.DEFAULT_EQUIP_MESSAGE,
            TradingCardsConfig.load(yaml).config().levelling().announce().equipMessage());
    }

    @Test
    void anEmptyMessageTurnsItOff() {
        var yaml = new YamlConfiguration();
        yaml.set("levelling.announce.equip-message", "");
        assertEquals("", TradingCardsConfig.load(yaml).config().levelling().announce().equipMessage());
    }
}
