package codes.castled.allium.broadcast;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

/**
 * Which timed broadcast goes next. {@code SEQUENTIAL} follows the file's order and wraps;
 * {@code RANDOM} never repeats the previous one when there is a choice. The list is read again
 * for each turn, so a broadcast added to or removed from the file takes part from the next turn.
 */
public final class BroadcastRotation {

    public enum Order {
        SEQUENTIAL, RANDOM;

        /** {@code sequential} or {@code random}, ignoring case; anything else is empty. */
        public static Optional<Order> parse(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "sequential":
                case "in-order":
                case "order":
                    return Optional.of(SEQUENTIAL);
                case "random":
                case "shuffle":
                    return Optional.of(RANDOM);
                default:
                    return Optional.empty();
            }
        }
    }

    private final Order order;
    private final Random random;
    private String last;
    private int position;

    public BroadcastRotation(Order order, Random random) {
        this.order = order;
        this.random = random;
    }

    public Order order() {
        return order;
    }

    public synchronized Optional<String> next(List<String> broadcasts) {
        if (broadcasts.isEmpty()) {
            return Optional.empty();
        }
        String chosen;
        if (order == Order.SEQUENTIAL) {
            int at = last == null ? -1 : broadcasts.indexOf(last);
            // A removed broadcast keeps its place in the count, so the rotation does not restart.
            position = at >= 0 ? (at + 1) % broadcasts.size() : position % broadcasts.size();
            chosen = broadcasts.get(position);
            position++;
        } else if (broadcasts.size() == 1) {
            chosen = broadcasts.get(0);
        } else {
            do {
                chosen = broadcasts.get(random.nextInt(broadcasts.size()));
            } while (chosen.equals(last));
        }
        last = chosen;
        return Optional.of(chosen);
    }
}
