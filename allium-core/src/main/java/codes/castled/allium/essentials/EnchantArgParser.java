package codes.castled.allium.essentials;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Pure parser for {@code /enchant <enchantment> <level> [player]} and legacy
 * {@code /enchant <player> <enchantment> <level>}.
 */
public final class EnchantArgParser {

    public static final int MAX_LEVEL = 255;

    public record ParsedEnchant(String enchantmentToken, int level, String targetName) {
    }

    private EnchantArgParser() {
    }

    /**
     * Parses enchantment arguments.
     *
     * @param args             command arguments
     * @param isEnchantment    returns true if token resolves to a valid enchantment
     * @param isVisiblePlayer  returns true if token resolves to an online visible player
     */
    public static Optional<ParsedEnchant> parse(
            String[] args,
            Predicate<String> isEnchantment,
            Predicate<String> isVisiblePlayer
    ) {
        if (args == null || args.length < 2 || args.length > 3) {
            return Optional.empty();
        }
        if (args.length == 2) {
            Integer level = parseLevel(args[1]);
            if (level == null) {
                return Optional.empty();
            }
            return Optional.of(new ParsedEnchant(args[0], level, null));
        }

        boolean firstIsEnchant = isEnchantment.test(args[0]);
        boolean secondIsEnchant = isEnchantment.test(args[1]);
        boolean firstIsPlayer = isVisiblePlayer.test(args[0]);
        boolean thirdIsPlayer = isVisiblePlayer.test(args[2]);

        // Modern order: <enchantment> <level> <player>
        Integer secondAsLevel = parseLevel(args[1]);
        Integer thirdAsLevel = parseLevel(args[2]);

        if (firstIsEnchant && secondAsLevel != null && (thirdAsLevel == null || !secondIsEnchant)) {
            return Optional.of(new ParsedEnchant(args[0], secondAsLevel, args[2]));
        }
        // Legacy order: <player> <enchantment> <level>
        if ((firstIsPlayer || !firstIsEnchant) && secondIsEnchant && thirdAsLevel != null) {
            return Optional.of(new ParsedEnchant(args[1], thirdAsLevel, args[0]));
        }
        // Fallback so caller reports accurate unknown-enchantment vs player-not-found diagnostics
        if (secondAsLevel != null && thirdAsLevel == null && !thirdIsPlayer) {
            return Optional.of(new ParsedEnchant(args[0], secondAsLevel, args[2]));
        }
        if (thirdAsLevel != null) {
            return Optional.of(new ParsedEnchant(args[1], thirdAsLevel, args[0]));
        }
        return Optional.empty();
    }

    /** Level 0 removes the enchantment; 1..255 applies it. */
    public static Integer parseLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int level = Integer.parseInt(raw.trim());
            if (level < 0 || level > MAX_LEVEL) {
                return null;
            }
            return level;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
