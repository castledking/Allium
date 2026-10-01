package codes.castled.allium.tradingcards.card;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The loaded card table, keyed by config id and indexed by mob.
 *
 * <p>Indexing by mob as well as by id is what makes the drop path a hash
 * lookup: the death event only knows an entity type, and without the index
 * every kill would scan every configured card.
 */
public final class CardRegistry {

    private Map<String, CardDefinition> byId = new LinkedHashMap<>();
    private Map<String, CardDefinition> byMob = new LinkedHashMap<>();

    /**
     * Replaces the whole table.
     *
     * <p>Swapped atomically rather than mutated in place, so a lookup running
     * against the old table sees a consistent set and never a half-applied
     * reload.
     */
    public synchronized void swap(Map<String, CardDefinition> cards) {
        Map<String, CardDefinition> nextById = new LinkedHashMap<>(cards);
        Map<String, CardDefinition> nextByMob = new LinkedHashMap<>();
        for (CardDefinition definition : nextById.values()) {
            String mob = CardDropListener.mobKey(definition.mob());
            CardDefinition existing = nextByMob.put(mob, definition);
            if (existing != null) {
                // Two configs claiming one mob would make the drop roll
                // depend on iteration order. The loader reports this; the
                // registry keeps the first so behaviour stays deterministic.
                continue;
            }
        }
        this.byId = Map.copyOf(nextById);
        this.byMob = Map.copyOf(nextByMob);
    }

    public Optional<CardDefinition> byId(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public Optional<CardDefinition> byMob(String mobType) {
        return byMobType(mobType).map(byMob::get);
    }

    private Optional<String> byMobType(String mobType) {
        String key = CardDropListener.mobKey(mobType);
        return key.isEmpty() ? Optional.empty() : Optional.of(key);
    }

    public Collection<CardDefinition> all() {
        return byId.values();
    }

    public Map<String, CardDefinition> byId() {
        return byId;
    }

    public int size() {
        return byId.size();
    }

    public boolean hasCards() {
        return !byId.isEmpty();
    }

    /** How many mobs have a card, which can be fewer than the card count. */
    public int mobCount() {
        return byMob.size();
    }
}
