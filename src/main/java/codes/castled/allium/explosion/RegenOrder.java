package codes.castled.allium.explosion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The order an explosion's blocks come back in: solid blocks first, lowest layer first and each
 * layer from its rim inwards; then everything that rests on or hangs from them (torches, plants,
 * rails, signs, snow layers) the same way. Blocks are put back without block updates, so the
 * order is how it looks, not what keeps each one in place.
 */
public final class RegenOrder {

    /** A block's position and whether it is attached to others. */
    public interface Placed {
        int x();

        int y();

        int z();

        boolean attached();
    }

    private RegenOrder() {
    }

    public static <T extends Placed> List<T> sort(List<T> blocks, double centerX, double centerZ) {
        List<T> sorted = new ArrayList<>(blocks);
        Comparator<T> order = Comparator.<T>comparingInt(block -> block.attached() ? 1 : 0)
                .thenComparingInt(Placed::y)
                .thenComparing(Comparator.<T>comparingDouble(block -> {
                    double dx = block.x() + 0.5D - centerX;
                    double dz = block.z() + 0.5D - centerZ;
                    return dx * dx + dz * dz;
                }).reversed())
                .thenComparingInt(Placed::x)
                .thenComparingInt(Placed::z);
        sorted.sort(order);
        return sorted;
    }
}
