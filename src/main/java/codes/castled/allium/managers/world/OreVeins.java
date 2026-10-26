package codes.castled.allium.managers.world;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/** The original adjacent-block vein walk, bounded to its owning chunk. */
public final class OreVeins {
    public record Position(int x, int y, int z) { }
    private static final int[][] DIRECTIONS = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private OreVeins() { }

    public static int generate(long seed, int chunkX, int chunkZ, OreDefinition ore,
                               OreDefinition.HeightRange heights, boolean mountain,
                               Predicate<Position> place) {
        if (heights.empty() || ore.chancePerChunk() == 0) return 0;
        Random random = new Random(seed + chunkX * 341873128712L + chunkZ * 132897987541L
            + ore.name().hashCode() * 91815541L);
        if (random.nextDouble() >= ore.chancePerChunk()) return 0;
        Set<Position> attempted = new HashSet<>();
        int total = 0;
        int veins = 1 + random.nextInt(ore.veinsPerChunk() + 1);
        for (int vein = 0; vein < veins; vein++) {
            Position start = new Position(random.nextInt(16), heights.min() + random.nextInt(heights.max() - heights.min() + 1), random.nextInt(16));
            if (!attempted.add(start) || !place.test(start)) continue;
            total++;
            int target = mountain && start.y() >= ore.singleAbove() ? 1
                : Math.min(ore.veinSize(), 3) + random.nextInt(Math.max(1, ore.veinSize() - 2));
            List<Position> placed = new ArrayList<>(List.of(start));
            for (int attempt = 0; placed.size() < target && attempt < target * 3; attempt++) {
                Position from = placed.get(random.nextInt(placed.size()));
                int[] d = DIRECTIONS[random.nextInt(DIRECTIONS.length)];
                Position next = new Position(from.x() + d[0], from.y() + d[1], from.z() + d[2]);
                if (next.x() < 0 || next.x() >= 16 || next.z() < 0 || next.z() >= 16
                    || !heights.contains(next.y()) || !attempted.add(next)) continue;
                if (place.test(next)) { placed.add(next); total++; }
            }
        }
        return total;
    }
}
