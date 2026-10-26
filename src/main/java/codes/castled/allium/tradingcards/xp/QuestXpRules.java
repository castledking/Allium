package codes.castled.allium.tradingcards.xp;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;

/** Difficulty settings for Quests, which has categories and stages but no difficulty API. */
public record QuestXpRules(Map<String, Double> multipliers, Map<String, String> questDifficulties,
                           Map<String, String> categoryDifficulties, Map<String, String> stageDifficulties,
                           String defaultDifficulty) {
    private static final Pattern STAGE = Pattern.compile("(?:^|[-_])(\\d+)$");

    public QuestXpRules {
        multipliers = Map.copyOf(multipliers);
        questDifficulties = Map.copyOf(questDifficulties);
        categoryDifficulties = Map.copyOf(categoryDifficulties);
        stageDifficulties = Map.copyOf(stageDifficulties);
    }

    public static QuestXpRules defaults() {
        return new QuestXpRules(Map.of("easy", 1.0, "medium", 2.0, "hard", 4.0, "expert", 8.0),
            Map.of(), Map.of(), Map.of("1", "easy", "2", "medium", "3", "hard", "4", "expert"), "easy");
    }

    public static QuestXpRules load(ConfigurationSection yaml) {
        QuestXpRules defaults = defaults();
        ConfigurationSection section = yaml == null ? null
            : yaml.getConfigurationSection("xp.sources.quest-complete");
        if (section == null) return defaults;
        Map<String, Double> multipliers = new LinkedHashMap<>(defaults.multipliers());
        ConfigurationSection values = section.getConfigurationSection("difficulty-multipliers");
        if (values != null) for (String key : values.getKeys(false)) {
            double value = values.getDouble(key);
            if (Double.isFinite(value) && value > 0) multipliers.put(normalize(key), value);
        }
        return new QuestXpRules(multipliers,
            strings(section, "quest-difficulties", defaults.questDifficulties()),
            strings(section, "category-difficulties", defaults.categoryDifficulties()),
            strings(section, "stage-difficulties", defaults.stageDifficulties()),
            normalize(section.getString("default-difficulty", defaults.defaultDifficulty())));
    }

    private static Map<String, String> strings(ConfigurationSection section, String key, Map<String, String> fallback) {
        ConfigurationSection values = section.getConfigurationSection(key);
        if (values == null) return fallback;
        Map<String, String> result = new LinkedHashMap<>();
        for (String id : values.getKeys(false)) result.put(normalize(id), normalize(values.getString(id)));
        return result;
    }

    public double multiplier(String questId, String category) {
        String id = normalize(questId);
        String group = normalize(category);
        String difficulty = questDifficulties.get(id);
        if (difficulty == null) difficulty = categoryDifficulties.get(group);
        if (difficulty == null && multipliers.containsKey(group)) difficulty = group;
        if (difficulty == null) {
            for (String token : id.split("[-_/.]")) {
                if (multipliers.containsKey(token)) { difficulty = token; break; }
            }
        }
        if (difficulty == null) {
            var stage = STAGE.matcher(id);
            if (stage.find()) difficulty = stageDifficulties.get(stage.group(1));
        }
        return multipliers.getOrDefault(difficulty == null ? defaultDifficulty : difficulty,
            multipliers.getOrDefault(defaultDifficulty, 1.0));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
