package codes.castled.allium.tradingcards.boost;

import codes.castled.allium.tradingcards.item.TradingCardData;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which card a player has equipped, and what it granted.
 *
 * <p>Kept separately from the boost state so the answer to "what is this player
 * wearing, and what did it give them" survives without asking AuraSkills or
 * re-reading the item. It is also what a reload reconciles against: a card whose
 * boosts could not be applied because the user was still loading is re-applied
 * once they are loaded.
 */
public final class EquippedCardTracker {

    /** One equipped card and the boost ids it actually granted. */
    public record Equipped(TradingCardData card, List<String> granted, int level) {}

    private final Map<UUID, Equipped> equipped = new ConcurrentHashMap<>();

    public void set(java.util.UUID player, TradingCardData card, List<String> granted) {
        equipped.put(player, new Equipped(card, List.copyOf(granted), card.level()));
    }

    public void clear(java.util.UUID player) {
        equipped.remove(player);
    }

    public Equipped get(java.util.UUID player) {
        return equipped.get(player);
    }

    public boolean isEmpty(java.util.UUID player) {
        return !equipped.containsKey(player);
    }

    public int trackedPlayers() {
        return equipped.size();
    }

    /** The card a player is wearing, or null. */
    public TradingCardData card(java.util.UUID player) {
        Equipped entry = equipped.get(player);
        return entry == null ? null : entry.card();
    }

    /**
     * The boost ids granted to a player whose card levels up.
     *
     * <p>Returned by identity rather than recomputed, so a boost that failed to
     * apply is not reported as granted and then re-applied on every level.
     */
    public List<String> granted(java.util.UUID player) {
        Equipped entry = equipped.get(player);
        return entry == null ? List.of() : entry.granted();
    }

    public int level(java.util.UUID player) {
        Equipped entry = equipped.get(player);
        return entry == null ? 0 : entry.level();
    }

    public void clearAll() {
        equipped.clear();
    }
}
