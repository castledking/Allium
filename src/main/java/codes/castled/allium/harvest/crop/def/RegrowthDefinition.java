package codes.castled.allium.harvest.crop.def;

/**
 * Regrowth behavior after a harvest, configured per {@link HarvestSource}.
 *
 * <p>Picking a fruit and uprooting the plant are different acts, so they get
 * different answers. The usual shape is right-click regrowing to a mid stage
 * (the plant keeps producing) while breaking removes it for good (you get the
 * seeds back and replant), but any combination is expressible.
 */
public record RegrowthDefinition(Rule onRightClick, Rule onBreak) {

    /**
     * @param stage zero-based stage the crop regresses to after harvest
     */
    public record Rule(boolean enabled, int stage) {

        public static final Rule DISABLED = new Rule(false, 0);
    }

    public static final RegrowthDefinition DISABLED =
        new RegrowthDefinition(Rule.DISABLED, Rule.DISABLED);

    public RegrowthDefinition {
        if (onRightClick == null) {
            onRightClick = Rule.DISABLED;
        }
        if (onBreak == null) {
            onBreak = Rule.DISABLED;
        }
    }

    /** Shorthand for a crop that regrows the same way however it was taken. */
    public RegrowthDefinition(boolean enabled, int stage) {
        this(new Rule(enabled, stage), new Rule(enabled, stage));
    }

    public Rule forSource(HarvestSource source) {
        return source == HarvestSource.BREAK ? onBreak : onRightClick;
    }

    /** True when at least one source regrows — used for validation messages. */
    public boolean anyEnabled() {
        return onRightClick.enabled() || onBreak.enabled();
    }
}
