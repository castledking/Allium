package codes.castled.allium.item.library;

import java.util.Locale;
import java.util.Map;

/**
 * The vanilla potions an effect belongs to, with their brewed durations, so a legacy
 * {@code potion;effect;seconds;level} item that matches one is given as that very potion.
 *
 * <p>Durations are what the potion item stores (a lingering cloud applies a quarter of it, a
 * tipped arrow an eighth); the legacy syntax states the duration the target receives.
 */
final class VanillaPotions {

    /** normal/long seconds (0 when absent; -1 for instant), strong seconds in ticks, strong level (0 when absent). */
    private record Potion(int normal, int extended, int strongTicks, int strongLevel) {
    }

    private static final Map<String, Potion> POTIONS = Map.ofEntries(
            Map.entry("night_vision", new Potion(180, 480, 0, 0)),
            Map.entry("invisibility", new Potion(180, 480, 0, 0)),
            Map.entry("leaping", new Potion(180, 480, 1800, 2)),
            Map.entry("fire_resistance", new Potion(180, 480, 0, 0)),
            Map.entry("swiftness", new Potion(180, 480, 1800, 2)),
            Map.entry("slowness", new Potion(90, 240, 400, 4)),
            Map.entry("turtle_master", new Potion(20, 40, 400, 2)),
            Map.entry("water_breathing", new Potion(180, 480, 0, 0)),
            Map.entry("healing", new Potion(-1, 0, -1, 2)),
            Map.entry("harming", new Potion(-1, 0, -1, 2)),
            Map.entry("poison", new Potion(45, 90, 432, 2)),
            Map.entry("regeneration", new Potion(45, 90, 450, 2)),
            Map.entry("strength", new Potion(180, 480, 1800, 2)),
            Map.entry("weakness", new Potion(90, 240, 0, 0)),
            Map.entry("luck", new Potion(300, 0, 0, 0)),
            Map.entry("slow_falling", new Potion(90, 240, 0, 0)),
            Map.entry("wind_charged", new Potion(180, 0, 0, 0)),
            Map.entry("weaving", new Potion(180, 0, 0, 0)),
            Map.entry("oozing", new Potion(180, 0, 0, 0)),
            Map.entry("infested", new Potion(180, 0, 0, 0)));

    /** Effect ids whose potion is named differently ({@code speed} brews Swiftness). */
    private static final Map<String, String> BY_EFFECT = Map.of(
            "speed", "swiftness", "jump_boost", "leaping", "instant_health", "healing", "instant_damage", "harming");

    private VanillaPotions() {
    }

    /** The vanilla potion key for effect {@code id}, or null when no potion carries that effect (haste, wither, ...). */
    static String key(String id) {
        String effect = id.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        String key = BY_EFFECT.getOrDefault(effect, effect);
        return POTIONS.containsKey(key) ? key : null;
    }

    /**
     * The exact vanilla potion ({@code poison}, {@code long_poison}, {@code strong_poison}) for a
     * duration the target receives ({@code seconds}, 0 or less for the default) and a level, or null
     * when none matches. {@code factor} is how much the carrier divides the stored duration by.
     */
    static String variant(String key, int seconds, int level, int factor) {
        Potion potion = POTIONS.get(key);
        if (potion == null) return null;
        int lvl = Math.max(1, level);
        boolean instant = potion.normal() < 0;
        if (lvl == 1) {
            if (instant || seconds <= 0 || seconds == potion.normal() / factor) return key;
            if (potion.extended() > 0 && seconds == potion.extended() / factor) return "long_" + key;
            return null;
        }
        if (lvl != potion.strongLevel()) return null;
        if (instant || seconds <= 0 || seconds == potion.strongTicks() / (factor * 20)) return "strong_" + key;
        return null;
    }
}
