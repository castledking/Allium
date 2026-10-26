package codes.castled.allium.tradingcards.xp;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class QuestXpRulesTest {
    @Test void installedQuestStagesHaveIncreasingDifficulty() {
        QuestXpRules rules = QuestXpRules.defaults();
        assertEquals(1.0, rules.multiplier("activities-00-milk-1", "activities"));
        assertEquals(2.0, rules.multiplier("activities-00-milk-2", "activities"));
        assertEquals(4.0, rules.multiplier("activities-00-milk-3", "activities"));
        assertEquals(8.0, rules.multiplier("activities-00-milk-4", "activities"));
        assertEquals(1.0, rules.multiplier("unknown-99", "activities"));
        assertEquals(4.0, rules.multiplier("daily-hard", null));
    }

    @Test void explicitQuestThenCategoryOverridesStageInference() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
            xp:
              sources:
                quest-complete:
                  difficulty-multipliers:
                    expert: 10
                  quest-difficulties:
                    activities-00-milk-1: expert
                  category-difficulties:
                    activities: hard
            """);
        QuestXpRules rules = XpConfig.load(yaml).config().quests();
        assertEquals(10.0, rules.multiplier("ACTIVITIES-00-MILK-1", "activities"));
        assertEquals(4.0, rules.multiplier("activities-00-milk-2", "activities"));
    }

    @Test void existingConfigsGainOrdinaryCatchXpAndCanOptOutOfTheEcoJobsFix() {
        assertTrue(XpConfig.load(new YamlConfiguration()).config().isEnabled("fish-catch"));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("xp.litefish-ecojobs-catch-value", 0);
        assertEquals(0, XpConfig.load(yaml).config().liteFishCatchValue());
    }
}
