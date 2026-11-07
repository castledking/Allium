package codes.castled.allium.item.delivery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * Plans an insertion into storage slots the way Bukkit's {@code addItem} does: each stack first
 * tops up similar partial stacks in slot order, then fills empty slots in slot order. Pure, so
 * a {@link OverflowPolicy#REFUSE} delivery can decide before anything is mutated.
 */
public final class InventoryFit {

    private InventoryFit() {
    }

    /** One stack: a similarity key, how many it holds, and its maximum stack size. */
    public record Stack<K>(K key, int count, int maxStack) {
        public Stack {
            Objects.requireNonNull(key, "key");
            if (count < 0 || maxStack < 1) {
                throw new IllegalArgumentException("count must be 0+ and maxStack 1+");
            }
        }
    }

    /**
     * @param inserted  per insert, how many were placed
     * @param leftovers per insert, how many did not fit
     */
    public record Plan(int[] inserted, int[] leftovers) {
        public int totalInserted() {
            return Arrays.stream(inserted).sum();
        }

        public int totalLeftover() {
            return Arrays.stream(leftovers).sum();
        }

        public boolean fitsAll() {
            return totalLeftover() == 0;
        }
    }

    /**
     * @param slots   storage slots in order; {@code null} entries are empty
     * @param inserts stacks to add, in order
     * @param similar whether two keys can share a stack
     */
    public static <K> Plan plan(List<Stack<K>> slots, List<Stack<K>> inserts, BiPredicate<K, K> similar) {
        List<K> keys = new ArrayList<>(slots.size());
        int[] counts = new int[slots.size()];
        int[] max = new int[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            Stack<K> slot = slots.get(i);
            keys.add(slot == null || slot.count() == 0 ? null : slot.key());
            counts[i] = slot == null ? 0 : slot.count();
            max[i] = slot == null ? 0 : slot.maxStack();
        }
        int[] inserted = new int[inserts.size()];
        int[] leftovers = new int[inserts.size()];
        for (int n = 0; n < inserts.size(); n++) {
            Stack<K> insert = inserts.get(n);
            int remaining = insert.count();
            for (int i = 0; i < keys.size() && remaining > 0; i++) {
                if (keys.get(i) != null && similar.test(keys.get(i), insert.key())) {
                    int room = Math.max(0, Math.min(max[i], insert.maxStack()) - counts[i]);
                    int moved = Math.min(room, remaining);
                    counts[i] += moved;
                    remaining -= moved;
                }
            }
            for (int i = 0; i < keys.size() && remaining > 0; i++) {
                if (keys.get(i) == null) {
                    int moved = Math.min(insert.maxStack(), remaining);
                    keys.set(i, insert.key());
                    counts[i] = moved;
                    max[i] = insert.maxStack();
                    remaining -= moved;
                }
            }
            inserted[n] = insert.count() - remaining;
            leftovers[n] = remaining;
        }
        return new Plan(inserted, leftovers);
    }
}
