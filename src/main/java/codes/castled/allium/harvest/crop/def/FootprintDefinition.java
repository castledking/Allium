package codes.castled.allium.harvest.crop.def;

import codes.castled.allium.harvest.util.BlockPositionKey;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The set of block cells a (gigantic) crop stage occupies, expressed as
 * offsets from the crop's anchor block. The anchor cell {@code (0,0,0)} is
 * always included.
 */
public record FootprintDefinition(List<Cell> cells) {

    public record Cell(int dx, int dy, int dz) {}

    public FootprintDefinition {
        Set<Cell> unique = new LinkedHashSet<>(cells);
        unique.add(new Cell(0, 0, 0));
        cells = List.copyOf(unique);
    }

    public static final FootprintDefinition SINGLE = new FootprintDefinition(List.of());

    public boolean isSingleCell() {
        return cells.size() == 1;
    }

    /** Resolves the absolute positions this footprint occupies around an anchor. */
    public List<BlockPositionKey> resolve(BlockPositionKey anchor) {
        return cells.stream()
            .map(c -> anchor.offset(c.dx(), c.dy(), c.dz()))
            .toList();
    }
}
