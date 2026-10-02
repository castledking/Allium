package codes.castled.allium.tradingcards.item;

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
        IntFunction<Double> xpForLevel,
        java.util.function.Function<String, String> titleColour
    ) {
        public LoreData {
            bands = bands == null ? List.of() : List.copyOf(bands);
            boostLine = boostLine == null ? (id, level) -> id : boostLine;
            headLabel = headLabel == null || headLabel.isBlank() ? "heads" : headLabel;
            bonusSlots = Math.max(1, bonusSlots);
            separator = separator == null ? "" : separator;
            titleColour = titleColour == null ? mob -> null : titleColour;
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
        // is the name and the ornament sits in the rows above the text. A title
        // there collided with the ornament, so the title moved into the lore as
        // line 0 and everything below it shifted clear.
        List<String> lines = new ArrayList<>();
        lines.add(titleLine(card));
        lines.addAll(body(card, tier, xp));
        // The pack's font ships italic glyphs only, so an unstyled line inherits
        // the italic and the whole card reads as slanted. Reset per line, since
        // lore lines are independent components rather than one nested tree.
        return lines.stream().map(CardLore::notItalic).toList();
    }

    /**
     * One bonus boost rendered the way it appears in a card's lore.
     *
     * <p>Public so the bonus menu can label a slot with exactly what the card
     * will show, rather than a second formatting path that can drift from it.
     */
    public String bonusLineFor(String boostId, int level) {
        return data.boostLine().apply(boostId, level);
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
        Tier tier = card.tier() == null ? Tier.SIMPLE : card.tier();
        // One slot opens per tier: a SIMPLE card has one to roll and a FABLED one
        // has all of them. Keyed on the tier rather than on rerolls, because a
        // slot is now bought with its own roll and a reroll must not hand out a
        // free one.
        int unlocked = Math.min(data.bonusSlots(), tier.ordinal() + 1);
        for (int i = 0; i < data.bonusSlots(); i++) {
            String held = card.bonusAt(i);
            if (!held.isEmpty()) {
                out.add(data.boostLine().apply(held, card.level()));
            } else if (i < unlocked) {
                // Rolled for a fee, so it reads as available rather than as a
                // value the drop already gave. Grey, matching the pips.
                out.add("<gray>Bonus:</gray> <#777777>Empty!</#777777>");
            } else {
                out.add("<gray>Bonus:</gray> <#F15A45>Locked 🔒</#F15A45>");
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
