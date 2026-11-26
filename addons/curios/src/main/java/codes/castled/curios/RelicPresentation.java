package codes.castled.curios;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class RelicPresentation {
    private static final Map<String, String> COLORS = Map.of(
            "piglin_signet", "#F4C060", "echoing_circlet", "#63D6C9", "idol_of_depths", "#59BADF",
            "evokers_mantle", "#CF8AE1", "strider_hide_girdle", "#E8865F", "membrane_cowl", "#92A5F4",
            "chorus_buckle", "#DA92E7", "blaze_ember_ring", "#FF9A4D", "witchs_brewstone", "#9ED766");

    private RelicPresentation() {}

    static String defaultColor(String id) { return COLORS.getOrDefault(id, "#E2C181"); }

    static List<String> effects(Catalog.Entry entry) {
        var lines = new ArrayList<String>();
        // Enum order keeps stored item lore stable across restarts.
        for (var ability : entry.abilities().stream().sorted().toList()) {
            lines.addAll(switch (ability) {
                case UNARMED_IGNITE -> List.of("Melee hits without a tool", "ignite targets for " + seconds(entry, "fire-ticks", 80) + ".");
                case PHANTOM_TRUCE -> List.of("Phantoms stay neutral", "unless provoked.");
                case PIGLIN_TRUCE -> List.of("Piglins stay neutral", "unless provoked.");
                case ECHO_STRIKE -> List.of("Hits blind and reveal targets", "for " + seconds(entry, "duration-ticks", 100) + " (" + seconds(entry, "cooldown-ticks", 200) + " cooldown).");
                case PURIFY -> List.of("Turns certain harmful effects", "into Regeneration for " + seconds(entry, "duration-ticks", 100) + ".",
                        "Cooldown: " + seconds(entry, "cooldown-ticks", 600) + ".");
                case WATER_RETALIATE -> List.of("Taking a hit in water deals", decimal(entry.effect("retaliation-damage", 2)) + " damage back.");
                case SUMMON_VEX -> List.of("Taking a hit below " + decimal(entry.effect("health-threshold", 0.3) * 100) + "% health",
                        "summons " + entry.effectTicks("vex-count", 2) + " allied vexes for " + seconds(entry, "duration-ticks", 300) + ".",
                        "Cooldown: " + seconds(entry, "cooldown-ticks", 600) + ".");
                case LAVA_WALKER -> List.of("Temporarily solidifies lava", "within " + entry.effectTicks("radius", 3) + " blocks as you walk.");
                case CHORUS_BLINK -> List.of("Sneak and drop to blink", "up to " + entry.effectTicks("radius", 16) + " blocks in an axis.",
                        "Cooldown: " + seconds(entry, "cooldown-ticks", 300) + ".");
                case GOLEM_KNOCKBACK_IMMUNITY -> List.of("Resists knockback from", "iron golems and ravagers.");
            });
        }
        for (var attribute : entry.attributes()) {
            String name = attribute.attribute().replace("minecraft:", "").replace("generic.", "").replace('_', ' ');
            name = name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
            boolean percent = !attribute.operation().name().equals("ADD_NUMBER");
            lines.add((attribute.amount() >= 0 ? "+" : "") + decimal(attribute.amount() * (percent ? 100 : 1))
                    + (percent ? "% " : " ") + name);
        }
        return lines;
    }

    private static String seconds(Catalog.Entry entry, String key, int fallback) {
        return decimal(entry.effectTicks(key, fallback) / 20.0) + "s";
    }

    private static String decimal(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
