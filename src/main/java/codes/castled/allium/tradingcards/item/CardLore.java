package codes.castled.allium.tradingcards.item;

import codes.castled.allium.tradingcards.boost.StartingBoosts;
import codes.castled.allium.tradingcards.card.CardDefinition;
import codes.castled.allium.tradingcards.card.QualityBand;
import codes.castled.allium.tradingcards.card.Tier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * Renders a card's lore. The panel behind it is not drawn here.
 *
 * <h2>The background</h2>
 *
 * <p>{@link CardFrame} wraps these lines in the tier's panel after they are
 * parsed, so this class only decides what the card says. The title comes first
 * here; the frame puts its header line above it.
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
     * @param bonusLine   renders a bonus boost at the strength it rolled, null
     *                    for one rolled before strengths existed
     * @param bonusStrength the strength to draw a bonus's bar at, or null when
     *                    that boost has no magnitude to roll
     */
    public record LoreData(
        List<QualityBand> bands,
        BiFunction<String, Integer, String> boostLine,
        String headLabel,
        int maximumLevel,
        int bonusSlots,
        String separator,
        IntFunction<Double> xpForLevel,
        java.util.function.Function<String, String> titleColour,
        StartingBoosts startingBoosts,
        java.util.function.BiFunction<String, Double, String> startingBoostValue,
        java.util.function.Function<String, String> boostLabel,
        int xpBarWidth,
        BiFunction<String, Integer, String> bonusLine,
        BiFunction<String, Integer, Integer> bonusStrength
    ) {
        public LoreData {
            bands = bands == null ? List.of() : List.copyOf(bands);
            boostLine = boostLine == null ? (id, level) -> id : boostLine;
            headLabel = headLabel == null || headLabel.isBlank() ? "heads" : headLabel;
            bonusSlots = Math.max(1, bonusSlots);
            separator = separator == null ? "" : separator;
            titleColour = titleColour == null ? mob -> null : titleColour;
            startingBoosts = startingBoosts;
            startingBoostValue = startingBoostValue == null ? (id, v) -> String.valueOf(v) : startingBoostValue;
            boostLabel = boostLabel == null ? id -> id : boostLabel;
            xpBarWidth = Math.max(1, xpBarWidth);
            BiFunction<String, Integer, String> levelled = boostLine;
            bonusLine = bonusLine == null ? (id, strength) -> levelled.apply(id, 0) : bonusLine;
            bonusStrength = bonusStrength == null ? (id, strength) -> null : bonusStrength;
        }

        /** Lore without rolled bonus strengths: bonuses read at their base value, with no bar. */
        public LoreData(List<QualityBand> bands, BiFunction<String, Integer, String> boostLine,
                        String headLabel, int maximumLevel, int bonusSlots, String separator,
                        IntFunction<Double> xpForLevel,
                        java.util.function.Function<String, String> titleColour,
                        StartingBoosts startingBoosts,
                        java.util.function.BiFunction<String, Double, String> startingBoostValue,
                        java.util.function.Function<String, String> boostLabel, int xpBarWidth) {
            this(bands, boostLine, headLabel, maximumLevel, bonusSlots, separator, xpForLevel,
                titleColour, startingBoosts, startingBoostValue, boostLabel, xpBarWidth,
                null, null);
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
        // The card's name is blank on the item, because the tooltip's first line
        // is the name and the panel's header reaches up through it. A title
        // there collided with the ornament, so the title lives in the lore,
        // under the header CardFrame adds.
        List<String> lines = new ArrayList<>();
        lines.add(titleLine(card));
        lines.addAll(body(card, tier, xp));
        // The pack's font ships italic glyphs only, so an unstyled line inherits
        // the italic and the whole card reads as slanted. Reset per line, since
        // lore lines are independent components rather than one nested tree.
        return lines.stream().map(CardText::smallCaps).map(CardLore::notItalic).toList();
    }

    /**
     * The starting boosts' derived numbers, empty when no rules are installed.
     *
     * <p>Read once per render so every line of the section agrees, and empty
     * rather than throwing when the rules failed to load, so a card still renders.
     */
    private List<StartingBoosts.Value> startingValues(TradingCardData card) {
        if (data.startingBoosts() == null) {
            return List.of();
        }
        return data.startingBoosts().valuesFor(card, data.bands());
    }

    /**
     * One bonus boost rendered the way it appears in a card's lore.
     *
     * <p>Public so the bonus menu can label a slot with exactly what the card
     * will show, rather than a second formatting path that can drift from it.
     */
    public String bonusLineFor(String boostId, Integer strength) {
        return CardText.smallCaps(data.bonusLine().apply(boostId, strength));
    }

    /**
     * How hard a bonus rolled, as the level bar's red-to-green rule: one dark
     * red segment at the weakest roll, all green only at the strongest.
     *
     * <p>Empty for a boost with no magnitude, such as a permission, where a bar
     * would claim a weak or strong roll that does not exist.
     */
    public String strengthBarFor(String boostId, Integer strength) {
        Integer shown = data.bonusStrength().apply(boostId, strength);
        if (shown == null) return "";
        double ratio = Math.max(0, Math.min(100, shown)) / 100.0;
        int width = data.xpBarWidth();
        // Floored, so only a perfect roll fills the bar, and never below one
        // segment, so the weakest roll still shows the red it landed on.
        int filled = Math.max(1, (int) Math.floor(width * ratio));
        return bar(filled, ratio) + " <gray>" + shown + "%</gray>";
    }

    /** The card's title, e.g. {@code Allay Trading Card}, in the mob's colour. */
    private String titleLine(TradingCardData card) {
        String mob = card.mob();
        String label = mob == null || mob.isBlank() ? "Trading Card" : title(mob) + " Trading Card";
        String colour = data.titleColour() == null
            ? CardDefinition.DEFAULT_COLOUR
            : data.titleColour().apply(mob == null ? "" : mob);
        if (colour == null || colour.isBlank()) {
            colour = CardDefinition.DEFAULT_COLOUR;
        }
        return "<" + colour + ">" + label + "</" + colour + ">";
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

        // The tier badge, forced to white so the glyph's own texture colour
        // shows. A colour on the line tints a bitmap font glyph, which washes the
        // texture out to whatever colour the text would have been — the Fabled
        // badge read as dull red instead of the red it is drawn in.
        //
        // Read off the card, never the display name, so a mislabelled item cannot
        // make two parts disagree.
        out.add("<white>" + label(tier) + "</white>");
        if (!sep.isEmpty()) out.add(sep);

        // Every segment is coloured explicitly. Writing
        // "<dark_gray>[<white>0</white>]</dark_gray>" leaves the closing bracket
        // as a child with no colour of its own, relying on inheritance from the
        // dark_gray parent; the client resolved that to vanilla's default lore
        // colour instead, so the bracket rendered dark purple.
        out.add("<gray>Level:</gray> <dark_gray>[</dark_gray><white>" + card.level()
            + "</white><dark_gray>]</dark_gray>");
        String progress = progressLine(card.level(), xp);
        if (!progress.isEmpty()) {
            out.add(progress);
        }
        if (!sep.isEmpty()) out.add(sep);

        // The trailing colon is coloured explicitly. Text after a nested </aqua> is a
        // child with no colour of its own, and the client resolves that to the
        // default lore colour rather than the gray enclosing it.
        out.add("<gray>While equipped in </gray><aqua>/reliques</aqua><gray>:</gray>");
        if (card.signatures().isEmpty()) {
            out.add("  <dark_gray>None</dark_gray>");
        } else {
            var values = startingValues(card);
            List<String> signatures = card.signatures();
            for (int i = 0; i < signatures.size(); i++) {
                String id = signatures.get(i);
                String suffix = i < values.size()
                    ? values.get(i).pointsSuffix() : "";
                // startingBoostValue returns the whole "Luck +6" line, so the
                // label is not added again here.
                out.add("  " + data.startingBoostValue()
                    .apply(id, i < values.size() ? values.get(i).base() : 0.0)
                    + suffix);
            }
        }
        if (!sep.isEmpty()) out.add(sep);

        out.add("<gray>Quality:</gray> " + qualityLine(card.quality(), band));
        if (!sep.isEmpty()) out.add(sep);

        out.add("<gray>Tier:</gray> " + pipRow(tier)
            + " <" + tierColour(tier) + ">[" + (tier.ordinal() + 1)
            + "/" + Tier.values().length + "]</" + tierColour(tier) + ">");

        // bonusSlots returns whole lines: a slot that holds a bonus drops the
        // "Bonus:" prefix, because the boost names itself and the prefix only
        // ever labelled the ones that were empty or locked.
        for (String slot : bonusSlots(card)) {
            out.add(slot);
        }

        if (!sep.isEmpty()) out.add(sep);
        out.add("<dark_gray>Right-Click to Open Menu</dark_gray>");
        return out;
    }

    /**
     * The bonus slots, in order.
     *
     * <p>One slot unlocks per tier. Individual paid rolls fill unlocked slots;
     * locked slots stay inactive even on cards issued under the older rules.
     *
     * <p>Shows {@code Empty!} for an unlocked slot holding nothing and
     * {@code Locked} for one not yet reached, so the card always shows its full
     * potential and a player can see what is still available.
     */
    List<String> bonusSlots(TradingCardData card) {
        List<String> out = new ArrayList<>();
        Tier tier = card.tier() == null ? Tier.SIMPLE : card.tier();
        // One slot opens per tier: a SIMPLE card has one to roll and a FABLED one
        // has all of them. Keyed on the tier rather than on rerolls, because a
        // slot is now bought with its own roll and a reroll must not hand out a
        // free one.
        int unlocked = Math.min(data.bonusSlots(), tier.ordinal() + 1);
        for (int i = 0; i < data.bonusSlots(); i++) {
            String held = card.bonusAt(i);
            if (i >= unlocked) {
                out.add("<gray>Bonus:</gray> <#F15A45>Locked 🔒</#F15A45>");
            } else if (!held.isEmpty()) {
                out.add(data.bonusLine().apply(held, card.bonusStrengthAt(i)));
            } else {
                // Rolled for a fee, so it reads as available rather than as a
                // value the drop already gave. Grey, matching the pips.
                out.add("<gray>Bonus:</gray> <#777777>Empty!</#777777>");
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
        if (level >= data.maximumLevel()) {
            return bar(data.xpBarWidth(), 1.0) + " <green>Max Level</green>";
        }
        double needed = data.xpForLevel().apply(level);
        if (needed <= 0.0) {
            return "";
        }
        double ratio = xp <= 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, xp / needed));
        int filled = (int) Math.round(data.xpBarWidth() * ratio);
        return bar(filled, ratio) + " "
            + "<gray>" + (long) xp + "</gray><dark_gray>/" + (long) needed + " XP</dark_gray>";
    }

    /**
     * The XP bar: a strikethrough rule warming from red to green as it fills.
     *
     * <p>Strikethrough rather than a row of {@code |} because the rest of the
     * card draws its rules the same way — {@code lore-separator} is a
     * strikethrough — so the bar sits in the same visual language instead of
     * looking like a different widget dropped into the middle of a card.
     *
     * <p>Built here rather than with PlaceholderAPI's progress expansion: lore
     * goes through MiniMessage, which does not read legacy {@code &a&m} codes
     * and would render them literally, and a bar that needs another plugin to
     * draw is worse than one that does not.
     */
    private String bar(int filled, double ratio) {
        int width = data.xpBarWidth();
        int f = Math.max(0, Math.min(width, filled));
        String colour = ratio >= 0.95 ? "green" : ratio >= 0.8 ? "dark_green"
            : ratio >= 0.6 ? "yellow" : ratio >= 0.4 ? "gold"
            : ratio >= 0.2 ? "red" : "dark_red";
        return "<" + colour + "><strikethrough>" + BAR_CHAR.repeat(f)
            + "</strikethrough></" + colour + ">"
            + "<dark_gray><strikethrough>" + BAR_CHAR.repeat(width - f)
            + "</strikethrough></dark_gray>";
    }

    /** The character the bar is drawn with. */
    private static final String BAR_CHAR = "\u2500";

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
