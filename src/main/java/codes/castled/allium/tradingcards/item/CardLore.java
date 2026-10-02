package codes.castled.allium.tradingcards.item;

import codes.castled.allium.tradingcards.card.QualityBand;
import codes.castled.allium.tradingcards.card.Tier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * Renders a card's lore, and the tier-coloured background behind it.
 *
 * <h2>The background is drawn as glyphs, not as a tooltip sprite</h2>
 *
 * <p>Vanilla has exactly one item-tooltip background, at
 * {@code gui/sprites/tooltip/background}, and nine-slicing it via a
 * {@code .mcmeta} is the easy way to get a panel that grows with the lore — but
 * it is one file for the whole client, so every tooltip on the server would get
 * the same tier colour and no tier could have its own.
 *
 * <p>So the panel is drawn here instead: each lore line is prefixed with a
 * 200px-wide band glyph, and the font gives that glyph a negative advance so the
 * text lands on top of its own band rather than after it. A line at the top of
 * the card gets the {@code _top} slice, the last line the {@code _bottom}, and
 * every line between a {@code _middle}. That is why the source art is a
 * three-slice at a fixed width with an 11px middle: one band per line, so the
 * panel grows vertically with the lore exactly as it would have if vanilla had
 * drawn it.
 *
 * <p>Because the server chooses which glyph to emit, all five tiers get their own
 * background. That is the whole reason for the extra machinery.
 *
 * <p><b>Width is a known soft spot.</b> Vanilla sizes the tooltip box to the
 * <i>text</i>, and the band's negative advance cancels its own width, so a short
 * line clips its band. The art was drawn for 200px; lines are kept short to stay
 * inside it. This is unverifiable without a client.
 */
public final class CardLore {

    /**
     * Tier labels, from the existing {@code hyronic_ae_configuration} font.
     *
     * <p>Already coloured per tier, so the badge needs no colour tag of its own.
     * Inventing a parallel set would only be one more thing to keep in step with
     * a font this class does not control.
     */
    private static final Map<Tier, String> TIER_LABEL = Map.of(
        Tier.SIMPLE, "᪗",
        Tier.ELITE, "᪙",
        Tier.ULTIMATE, "᪚",
        Tier.LEGENDARY, "᪛",
        Tier.FABLED, "᪜");

    /** Band glyphs, keyed by tier then slice. Mirrors the pack's font allocation. */
    private static final Map<Tier, String[]> BANDS = Map.of(
        Tier.SIMPLE, new String[] {"", "", ""},
        Tier.ELITE, new String[] {"", "", ""},
        Tier.ULTIMATE, new String[] {"", "", ""},
        Tier.LEGENDARY, new String[] {"", "", ""},
        Tier.FABLED, new String[] {"", "", ""});

    /** The font that owns the band glyphs. Includes the default font for the text. */
    private static final String BAND_FONT = "sf.allium_tradingcards";

    /**
     * The cursor reset: a space provider with a -200 advance, which cancels the
     * band glyph's own 200px advance so the text after it starts back at x=0.
     */
    private static final String RESET = "";

    private static final int BAR_WIDTH = 20;

    /**
     * Everything the lore needs that lives in config rather than on the card.
     *
     * @param boostLine   renders one boost for a boost id at a card level
     * @param bonusSlots  how many bonus slots a card has in total
     * @param headLabel   what the card trades for, plural
     */
    public record LoreData(
        List<QualityBand> bands,
        BiFunction<String, Integer, String> boostLine,
        String headLabel,
        int maximumLevel,
        int bonusSlots,
        String separator,
        IntFunction<Double> xpForLevel
    ) {
        public LoreData {
            bands = bands == null ? List.of() : List.copyOf(bands);
            boostLine = boostLine == null ? (id, level) -> id : boostLine;
            headLabel = headLabel == null || headLabel.isBlank() ? "heads" : headLabel;
            bonusSlots = Math.max(1, bonusSlots);
            separator = separator == null ? "" : separator;
        }
    }

    /**
     * A tier's three band glyphs, in the order they are used: top, middle,
     * bottom.
     *
     * <p>Exposed so the layout can be asserted against the allocation rather
     * than by reading glyphs back out of a rendered line, which only works if a
     * line happens to carry all three and it never does.
     */
    static String[] bands(Tier tier) {
        return BANDS.get(tier == null ? Tier.SIMPLE : tier);
    }

    /** The font that carries the band glyphs. */
    static String bandFont() {
        return BAND_FONT;
    }

    /** The tier label glyph, or an empty string for a null tier. */
    public static String label(Tier tier) {
        return tier == null ? "" : TIER_LABEL.getOrDefault(tier, "");
    }

    private final LoreData data;

    public CardLore(LoreData data) {
        this.data = data;
    }

    /**
     * Builds the lore for a card.
     *
     * @param xp accumulated xp toward the next level; zero omits the progress
     *          row rather than showing "0/25" on every freshly dropped card
     */
    public List<String> render(TradingCardData card, double xp) {
        if (card == null) {
            return List.of();
        }
        Tier tier = card.tier() == null ? Tier.SIMPLE : card.tier();
        List<String> lines = body(card, tier, xp);
        return wrapWithBands(tier, lines);
    }

    /**
     * The lore body, with no bands.
     *
     * <p>Split out so the layout can be tested on its own: banding is a
     * rendering concern and the content is not.
     */
    public List<String> body(TradingCardData card, Tier tier, double xp) {
        List<String> out = new ArrayList<>();
        QualityBand band = card.band(data.bands());
        String sep = data.separator();

        // The badge and the pips below both read the tier off the card, never off
        // the item's display name. A card whose item was mislabelled shows a
        // consistent (if wrong) tier rather than two tiers disagreeing on it.
        out.add(label(tier) + " <" + tierColour(tier) + ">" + title(tier.name())
            + "</" + tierColour(tier) + ">");
        if (!sep.isEmpty()) out.add(sep);

        out.add("<gray>Level:</gray> <dark_gray>[<white>" + card.level() + "</white>]</dark_gray>");
        String progress = progressLine(card.level(), xp);
        if (!progress.isEmpty()) {
            out.add(progress);
        }
        if (!sep.isEmpty()) out.add(sep);

        out.add("<gray>While equipped in <aqua>/reliques</aqua>:</gray>");
        if (card.signatures().isEmpty()) {
            out.add("  <dark_gray>None</dark_gray>");
        } else {
            for (String id : card.signatures()) {
                out.add("  " + data.boostLine().apply(id, card.level()));
            }
        }
        if (!sep.isEmpty()) out.add(sep);

        out.add("<gray>Quality:</gray> " + qualityLine(card.quality(), band));
        if (!sep.isEmpty()) out.add(sep);

        out.add("<gray>Tier:</gray> <dark_gray>" + tier.pips()
            + "</dark_gray> <" + tierColour(tier) + ">[" + (tier.ordinal() + 1)
            + "/" + Tier.values().length + "]</" + tierColour(tier) + ">");

        for (String slot : bonusSlots(card)) {
            out.add("<gray>Bonus:</gray> " + slot);
        }

        if (!sep.isEmpty()) out.add(sep);
        out.add("<dark_gray>Right-click to roll bonuses</dark_gray>");
        if (band != null && band.heads() > 0) {
            out.add("<dark_gray>Trades for <white>" + band.heads()
                + "</white> <dark_gray>" + data.headLabel() + "</dark_gray>");
        }
        return out;
    }

    /**
     * The bonus slots, in order.
     *
     * <p>Slots unlock one per reroll, so a card's bonus slots fill in as it is
     * rerolled rather than all at once. That is what makes a reroll worth
     * something on a card whose signature pool is already full.
     *
     * <p>Shows {@code Empty!} for an unlocked slot holding nothing and
     * {@code Locked} for one not yet reached, so the card always shows its full
     * potential and a player can see what is still available.
     */
    List<String> bonusSlots(TradingCardData card) {
        List<String> out = new ArrayList<>();
        List<String> held = card.bonuses();
        // One slot is open from the start; every reroll opens another.
        int unlocked = Math.min(data.bonusSlots(), card.rerolls() + 1);
        for (int i = 0; i < data.bonusSlots(); i++) {
            if (i >= unlocked) {
                out.add("<dark_gray>Locked</dark_gray>");
            } else if (i < held.size()) {
                out.add(data.boostLine().apply(held.get(i), card.level()));
            } else {
                out.add("<red>Empty!</red>");
            }
        }
        return out;
    }

    /** Wraps each line in the tier's band glyph and the font that carries it. */
    List<String> wrapWithBands(Tier tier, List<String> lines) {
        if (lines.isEmpty()) {
            return List.of();
        }
        String[] band = BANDS.getOrDefault(tier, BANDS.get(Tier.SIMPLE));
        List<String> out = new ArrayList<>(lines.size());
        int last = lines.size() - 1;
        for (int i = 0; i <= last; i++) {
            // Two bands on a one-line card would stack the caps on each other, so
            // a single line gets the top slice alone.
            String slice = (lines.size() == 1) ? band[0]
                : (i == 0 ? band[0] : (i == last ? band[2] : band[1]));
            out.add("<font:" + BAND_FONT + ">" + slice + RESET + lines.get(i) + "</font>");
        }
        return out;
    }

    private String qualityLine(int quality, QualityBand band) {
        String colour = band == null ? "gray" : band.colour();
        String name = band == null ? "Unknown" : title(band.id());
        // The percentage, because a raw 1..100 reads as a number nobody has a
        // frame of reference for.
        return "<" + colour + ">" + name + "</" + colour + "> "
            + "<dark_gray>[" + quality + "%]</dark_gray>";
    }

    /** The progress row, or empty when there is nothing to show. */
    private String progressLine(int level, double xp) {
        double needed = data.xpForLevel().apply(level);
        if (needed <= 0.0) {
            return "";
        }
        int filled = xp <= 0.0 ? 0
            : Math.max(1, Math.min(BAR_WIDTH, (int) Math.round(BAR_WIDTH * (xp / needed))));
        return "<green>" + "|".repeat(filled) + "</green>"
            + "<dark_gray>" + "|".repeat(BAR_WIDTH - filled) + "</dark_gray> "
            + "<gray>" + (long) xp + "</gray><dark_gray>/" + (long) needed + " XP</dark_gray>";
    }

    /**
     * Tier colours, approximating the badge glyph's own colour.
     *
     * <p>The word is there to stay readable without the resource pack, not to be
     * the badge.
     */
    private static String tierColour(Tier tier) {
        if (tier == null) return "gray";
        return switch (tier) {
            case SIMPLE -> "gray";
            case ELITE -> "aqua";
            case ULTIMATE -> "light_purple";
            case LEGENDARY -> "gold";
            case FABLED -> "red";
        };
    }

    /** Title-cases an enum or config id for display. */
    static String title(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
