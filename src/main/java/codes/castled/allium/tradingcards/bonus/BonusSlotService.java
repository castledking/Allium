package codes.castled.allium.tradingcards.bonus;

import codes.castled.allium.managers.economy.EconomyManager;
import codes.castled.allium.tradingcards.boost.BonusStrength;
import codes.castled.allium.tradingcards.boost.BoostCatalog;
import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.config.TradingCardsConfig;
import codes.castled.allium.tradingcards.item.TradingCardData;
import codes.castled.allium.tradingcards.item.TradingCardKeys;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Rolls the tier's unlocked bonus slots, with persisted prices and optional locks.
 * Signature boosts are fixed by tier and never belong to this service.
 */
public final class BonusSlotService {

    public enum Outcome {
        ROLLED,
        DISABLED,
        SLOT_LOCKED,
        SLOT_UNKNOWN,
        TIER_LOCKED,
        NOTHING_TO_ROLL,
        NOT_ENOUGH_MONEY,
        CHARGE_FAILED
    }

    public record Result(
        Outcome outcome,
        double cost,
        int slot,
        String boostId,
        List<BonusSlot> slots,
        String message
    ) {
        public boolean succeeded() {
            return outcome == Outcome.ROLLED;
        }
    }

    private final BoostCatalog.LoadResult catalog;
    private final TradingCardsConfig.BonusSlotRoll pricing;
    private final int slotCount;
    private final EconomyManager economy;
    private final RandomGenerator random;

    public BonusSlotService(
        BoostCatalog.LoadResult catalog,
        TradingCardsConfig.BonusSlotRoll pricing,
        int slotCount,
        EconomyManager economy,
        RandomGenerator random
    ) {
        this.catalog = catalog;
        this.pricing = pricing;
        this.slotCount = Math.max(1, slotCount);
        this.economy = economy;
        this.random = random;
    }

    public int slotCount() {
        return slotCount;
    }

    public boolean isEnabled() {
        return pricing.enabled();
    }

    /**
     * How many slots a card of this tier may roll: one per tier.
     *
     * <p>Keyed on the tier, not on how many rerolls the player has spent, so the
     * ladder decides how much of a card is available to buy. Keying it on reroll
     * count would have let the reroll button underprice the slot it competes
     * with.
     */
    public int unlockedSlots(Tier tier) {
        Tier t = tier == null ? Tier.SIMPLE : tier;
        return Math.min(slotCount, t.ordinal() + 1);
    }

    public boolean isTierUnlocked(TradingCardData card, int slot) {
        return slot >= 0 && slot < unlockedSlots(card.tier());
    }

    /** The next price, including this item's persisted roll history. */
    public double costFor(TradingCardData card, int slot, ItemStack stack) {
        List<BonusSlot> current = slots(stack, card);
        BonusSlot state = slot >= 0 && slot < slotCount ? current.get(slot) : BonusSlot.EMPTY;
        return pricing.costFor(state.rolls(), card.tier());
    }

    public double costFor(TradingCardData card, int slot) {
        return costFor(card, slot, null);
    }

    public boolean canRoll(TradingCardData card, int slot, ItemStack stack) {
        if (!pricing.enabled() || !isTierUnlocked(card, slot)) return false;
        return !slots(stack, card).get(slot).locked() && !availablePool(card).isEmpty();
    }

    public boolean canRoll(TradingCardData card, int slot) {
        return canRoll(card, slot, null);
    }

    /** The card's slots, padded to the configured slot count. */
    public List<BonusSlot> slots(TradingCardData card) {
        return slots(card, slotCount);
    }

    /** Reads the slot state a stack carries, defaulting to empty slots. */
    public List<BonusSlot> slots(ItemStack stack, TradingCardData card) {
        return slots(card, slotCount, readState(stack));
    }

    private List<BonusSlot> slots(TradingCardData card, int count) {
        return slots(card, count, List.of());
    }

    private List<BonusSlot> slots(TradingCardData card, int count, List<BonusSlot> state) {
        int size = Math.max(count, Math.max(card.bonuses().size(), state.size()));
        List<BonusSlot> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            BonusSlot st = i < state.size() ? state.get(i) : BonusSlot.EMPTY;
            out.add(new BonusSlot(card.bonusAt(i), st.rolls(), st.spent(), st.locked(),
                card.bonusStrengthAt(i)));
        }
        return List.copyOf(out);
    }

    /**
     * Rolls one slot, charging for it.
     *
     * <p>Refused before any money moves for every reason the roll could be a
     * no-op — a slot the tier has not opened, a locked slot, a pool the card has
     * already exhausted. Charging for nothing is how a sink becomes a complaint.
     */
    public Result roll(UUID player, TradingCardData card, int slot, ItemStack stack) {
        if (!pricing.enabled()) {
            return fail(Outcome.DISABLED, card, slot, "Rolling a bonus slot is unavailable.");
        }
        if (slot < 0 || slot >= slotCount) {
            return fail(Outcome.SLOT_UNKNOWN, card, slot, "That is not a bonus slot.");
        }
        if (!isTierUnlocked(card, slot)) {
            return fail(Outcome.TIER_LOCKED, card, slot,
                "A " + title(card.tier()) + " card has "
                    + unlockedSlots(card.tier()) + " bonus slot(s) open.");
        }

        List<BonusSlot> current = slots(stack, card);
        BonusSlot target = current.get(slot);
        if (target.locked()) {
            return fail(Outcome.SLOT_LOCKED, card, slot,
                "That bonus slot is locked. Right-click it to unlock.");
        }

        List<String> pool = availablePool(card);
        if (pool.isEmpty()) {
            return fail(Outcome.NOTHING_TO_ROLL, card, slot,
                "This card already holds every bonus its tier can roll.");
        }

        double cost = costFor(card, slot, stack);
        if (economy == null) {
            return fail(Outcome.DISABLED, card, slot,
                "The economy is unavailable, so a bonus slot cannot be charged for.");
        }
        BigDecimal price = BigDecimal.valueOf(cost);
        if (cost > 0 && !economy.hasEnough(player, price)) {
            return fail(Outcome.NOT_ENOUGH_MONEY, card, slot,
                "You need " + economy.formatBalance(price) + " to roll that bonus slot.");
        }
        if (cost > 0 && !economy.withdraw(player, price)) {
            return fail(Outcome.CHARGE_FAILED, card, slot,
                "You need " + economy.formatBalance(price) + " to roll that bonus slot.");
        }

        String rolled = pool.get(random.nextInt(pool.size()));
        // A strength only for a boost with a magnitude: a permission has no
        // weak or strong roll, so it gets no bar rather than a meaningless one.
        var definition = catalog.boosts().get(rolled);
        Integer strength = definition != null && BonusStrength.rolls(definition.mechanism())
            ? pricing.strength().roll(random) : null;
        List<BonusSlot> next = new ArrayList<>(current);
        next.set(slot, target.rolled(rolled, strength, cost));
        return new Result(Outcome.ROLLED, cost, slot, rolled, List.copyOf(next),
            "Rolled " + rolled + (strength == null ? "" : " at " + strength + "% strength")
                + " into bonus slot " + (slot + 1) + ".");
    }

    /** Locks or unlocks a slot. Refused for a slot the tier has not opened. */
    public Result toggleLock(TradingCardData card, int slot, ItemStack stack) {
        if (slot < 0 || slot >= slotCount) {
            return fail(Outcome.SLOT_UNKNOWN, card, slot, "That is not a bonus slot.");
        }
        if (!isTierUnlocked(card, slot)) {
            return fail(Outcome.TIER_LOCKED, card, slot,
                "A " + title(card.tier()) + " card cannot lock that slot.");
        }
        List<BonusSlot> next = new ArrayList<>(slots(stack, card));
        boolean locked = !next.get(slot).locked();
        next.set(slot, next.get(slot).withLocked(locked));
        return new Result(Outcome.ROLLED, 0.0, slot, next.get(slot).id(), List.copyOf(next),
            locked ? "Locked bonus slot " + (slot + 1) + "."
                : "Unlocked bonus slot " + (slot + 1) + ".");
    }

    /**
     * Empties a slot. Refused for a locked one.
     *
     * <p>The roll count and the money spent are kept: the player did pay for those
     * rolls, and showing the slot as never rolled would misrepresent what
     * happened. It also means clearing is not a way to resell a slot cheaply.
     */
    public Result clear(TradingCardData card, int slot, ItemStack stack) {
        if (slot < 0 || slot >= slotCount) {
            return fail(Outcome.SLOT_UNKNOWN, card, slot, "That is not a bonus slot.");
        }
        List<BonusSlot> current = slots(stack, card);
        BonusSlot target = current.get(slot);
        if (target.locked()) {
            return fail(Outcome.SLOT_LOCKED, card, slot,
                "That bonus slot is locked. Right-click it to unlock.");
        }
        if (target.isEmpty()) {
            return fail(Outcome.NOTHING_TO_ROLL, card, slot, "That bonus slot is already empty.");
        }
        List<BonusSlot> next = new ArrayList<>(current);
        next.set(slot, target.cleared());
        return new Result(Outcome.ROLLED, 0.0, slot, "", List.copyOf(next),
            "Cleared bonus slot " + (slot + 1) + ".");
    }

    /** Refunds a slot that was charged for but could not be written. */
    public void refund(UUID player, double cost) {
        if (economy == null || cost <= 0) return;
        economy.deposit(player, BigDecimal.valueOf(cost));
    }

    /** Writes the boost ids, and the strength each rolled at, back onto the card. */
    public void writeBonuses(ItemStack stack, TradingCardData card, List<BonusSlot> slots) {
        List<String> ids = new ArrayList<>(slots.size());
        List<Integer> strengths = new ArrayList<>(slots.size());
        for (BonusSlot s : slots) {
            ids.add(s.id());
            strengths.add(s.strength());
        }
        TradingCardData.write(stack, card.withBonuses(List.copyOf(ids), strengths));
    }

    /** The strength range rolls are drawn against. */
    public BonusStrength strength() {
        return pricing.strength();
    }

    /** Writes roll count, spend and lock flags. Removes the key when all default. */
    public void writeState(ItemStack stack, List<BonusSlot> slots) {
        var meta = stack.getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        List<String> parts = new ArrayList<>(slots.size());
        boolean any = false;
        for (BonusSlot s : slots) {
            String encoded = s.encode();
            any |= !encoded.isEmpty();
            parts.add(encoded);
        }
        if (!any) {
            pdc.remove(TradingCardKeys.SLOT_STATE);
            stack.setItemMeta(meta);
            return;
        }
        pdc.set(TradingCardKeys.SLOT_STATE, PersistentDataType.STRING,
            String.join(";", parts));
        stack.setItemMeta(meta);
    }

    private List<BonusSlot> readState(ItemStack stack) {
        if (stack == null || stack.getItemMeta() == null) return List.of();
        String raw = stack.getItemMeta().getPersistentDataContainer()
            .get(TradingCardKeys.SLOT_STATE, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return List.of();
        List<BonusSlot> out = new ArrayList<>();
        for (String part : raw.split(";", -1)) {
            out.add(BonusSlot.decode(part));
        }
        return out;
    }

    /**
     * Bonuses this card could still roll.
     *
     * <p>Without replacement, so a card never shows the same bonus twice, and
     * excluding what it already holds in any slot — not just this one.
     */
    private List<String> availablePool(TradingCardData card) {
        List<String> pool = new ArrayList<>(catalog.bonusPool()
            .getOrDefault(card.tier() == null ? Tier.SIMPLE : card.tier(), List.of()));
        List<String> active = card.bonuses().stream().limit(unlockedSlots(card.tier())).toList();
        pool.removeIf(id -> id == null || id.isBlank() || active.contains(id)
            || card.signatures().contains(id));
        return pool;
    }

    private Result fail(Outcome outcome, TradingCardData card, int slot, String message) {
        return new Result(outcome, 0.0, slot, "", null, message);
    }

    private static String title(Tier tier) {
        String s = (tier == null ? Tier.SIMPLE : tier).name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
