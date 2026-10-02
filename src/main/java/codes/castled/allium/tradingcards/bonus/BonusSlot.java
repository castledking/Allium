package codes.castled.allium.tradingcards.bonus;

/**
 * One bonus slot's state on a card.
 *
 * <p>A slot is not a one-shot fill. It is bought repeatedly: each roll costs
 * more than the last, the money spent is shown back so a player can see what
 * they have invested, and a slot can be locked so neither a roll nor a card
 * reroll can take a bonus out of it.
 *
 * @param id     the boost in this slot, or {@code ""} when it is empty
 * @param rolls  how many times this slot has been rolled, which is what the
 *               escalating price is based on
 * @param spent  total money paid into this slot, shown so the player can see
 *               what a slot has cost them
 * @param locked when set, the slot keeps whatever it holds
 */
public record BonusSlot(String id, int rolls, double spent, boolean locked) {

    public static final BonusSlot EMPTY = new BonusSlot("", 0, 0.0, false);

    public BonusSlot {
        id = id == null ? "" : id.trim();
        rolls = Math.max(0, rolls);
        spent = Math.max(0.0, spent);
    }

    public boolean isEmpty() {
        return id.isEmpty();
    }

    /** A copy with the boost set, counting the roll that put it there. */
    public BonusSlot rolled(String boostId, double cost) {
        return new BonusSlot(boostId, rolls + 1, spent + cost, locked);
    }

    /** A copy with the boost removed. The roll count and the money stay spent. */
    public BonusSlot cleared() {
        return new BonusSlot("", rolls, spent, locked);
    }

    public BonusSlot withLocked(boolean value) {
        return new BonusSlot(id, rolls, spent, value);
    }

    /**
     * Serialises to {@code rolls:spent:locked}, or empty when there is nothing
     * worth storing, so a card that has never been touched writes no state at
     * all rather than five copies of the same default.
     */
    public String encode() {
        if (rolls == 0 && spent == 0.0 && !locked) {
            return "";
        }
        return rolls + ":" + spent + ":" + (locked ? "1" : "0");
    }

    public static BonusSlot decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return EMPTY;
        }
        String[] parts = raw.split(":", 3);
        try {
            int rolls = Integer.parseInt(parts[0].trim());
            double spent = parts.length > 1 ? Double.parseDouble(parts[1].trim()) : 0.0;
            boolean locked = parts.length > 2 && "1".equals(parts[2].trim());
            return new BonusSlot("", rolls, spent, locked);
        } catch (NumberFormatException e) {
            // A malformed state is not worth losing the card over; the boost in
            // the slot is stored separately and still survives.
            return EMPTY;
        }
    }
}