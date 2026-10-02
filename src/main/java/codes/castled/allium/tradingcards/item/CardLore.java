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
 * Renders a card's lore. The panel behind it is drawn by the client, not here.
 *
 * <h2>The background</h2>
 *
 * <p>Drawn by vanilla, not by this class. The client renders every item tooltip
 * from one sprite, {@code gui/sprites/tooltip/background}, and a {@code .mcmeta}
 * nine-slice on that sprite makes vanilla stretch or tile its centre to fit the
 * tooltip — so the panel grows with the lore for free, on every client, with no
 * server-side work at all.
 *
 * <p>The obvious alternative, drawing the panel as font glyphs in the lore so
 * each tier could have its own, is not viable here: Nexo rewrites any
 * font JSON it finds under any namespace, replacing the declared metrics with
 * its own defaults and dropping negative advances outright, so the glyph resolves
 * to placeholders and every character renders as a box.
 *
 * <p>Tiers are therefore distinguished by the badge and colour rather than by
 * panel colour, which is what the tier label glyph is for.
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

    /** The tier label glyph, or an empty string for a null tier. */
    public static String label(Tier tier) {
        return tier == null ? "" : TIER_LABEL.getOrDefault(tier, "");
    }

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
        // The pack's font ships italic glyphs only, so an unstyled line inherits
        // the italic and the whole card reads as slanted. Reset per line, since
        // lore lines are independent components rather than one nested tree.
        return body(card, tier, xp).stream().map(CardLore::notItalic).toList();
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

        // The badge on its own. It used to be followed by the tier name, which
        // printed the tier twice: once in the item name and once here.
        //
        // Read off the card, never the item's display name, so a mislabelled item
        // shows a consistent tier rather than two disagreeing.
        out.add(label(tier));
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

        out.add("<gray>Tier:</gray> " + pipRow(tier)
            + " <" + tierColour(tier) + ">[" + (tier.ordinal() + 1)
            + "/" + Tier.values().length + "]</" + tierColour(tier) + ">");

        for (String slot : bonusSlots(card)) {
            out.add("<gray>Bonus:</gray> " + slot);
        }

        if (!sep.isEmpty()) out.add(sep);
        out.add("<dark_gray>Right-Click to Open Menu</dark_gray>");
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
        // A card that rolled bonuses must show all of them, so the slot count
        // cannot be below what it holds. Keying this on rerolls alone hid every
        // rolled bonus past the first behind "Locked", which read as the drop
        // having awarded nothing. One slot is open from the start and every
        // reroll opens another.
        int unlocked = Math.min(data.bonusSlots(),
            Math.max(held.size(), card.rerolls() + 1));
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


    /**
     * The pip row: filled diamonds green, hollow ones the grey the design uses.
     *
     * <p>Coloured per glyph rather than one colour for the row, so a SIMPLE card
     * reads as one green pip and four grey ones rather than five grey diamonds
     * that happen to differ in shape.
     */
    private static String pipRow(Tier tier) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < Tier.values().length; i++) {
            boolean filled = i <= tier.ordinal();
            row.append(filled ? "<green>◆</green>" : "<#777777>◇</#777777>");
        }
        return row.toString();
    }

    private String qualityLine(int quality, QualityBand band) {
        String colour = wrapColour(band == null ? null : band.colour());
        String name = band == null ? "Unknown" : title(band.id());
        // The percentage, because a raw 1..100 reads as a number nobody has a
        // frame of reference for.
        return colour + name + "</" + colour.substring(1, colour.length() - 1) + "> "
            + "<dark_gray>[" + quality + "%]</dark_gray>";
    }

    /**
     * Normalises a configured colour into an opening MiniMessage tag.
     *
     * <p>config.yml writes these wrapped, as {@code colour: "<gold>"}, so they
     * are used as they are. A bare {@code gold} is also accepted and wrapped,
     * because an operator writing the short form should not get
     * {@code <<gold>>} — which renders as gold text between two literal angle
     * brackets, and looked exactly like a missing glyph when it happened.
     *
     * <p>Returning the opening tag only is what makes the close tag derivable:
     * a second wrap is the whole bug this exists to prevent.
     */
    private static String wrapColour(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "<gray>";
        }
        if (value.startsWith("<") && value.endsWith(">")) {
            return value;
        }
        // Hex and named colours both arrive as a bare word.
        return value.startsWith("#") ? "<color:" + value + ">" : "<" + value + ">";
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
    private static final int BAR_WIDTH = 20;

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

    /** One lore line with the italic reset in front of it. */
    private static String notItalic(String line) {
        return line.startsWith("<!italic>") ? line : "<!italic>" + line;
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
