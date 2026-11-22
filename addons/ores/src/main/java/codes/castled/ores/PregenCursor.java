package codes.castled.ores;

import java.util.NoSuchElementException;

/** Walks a square of chunks, edge included, in constant memory. */
final class PregenCursor {
    record ChunkPosition(int x, int z) {}

    private final int centerX, centerZ, radius;
    private int dx, dz;

    PregenCursor(int centerX, int centerZ, int radius) {
        if (radius < 1 || radius > 10000) throw new IllegalArgumentException("Radius must be 1..10000 chunks.");
        if (Math.abs((long) centerX) + radius > 1874999 || Math.abs((long) centerZ) + radius > 1874999)
            throw new IllegalArgumentException("That area goes past Minecraft's world limit.");
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        dx = -radius;
        dz = -radius;
    }

    boolean hasNext() {
        return dx <= radius;
    }

    ChunkPosition next() {
        if (!hasNext()) throw new NoSuchElementException();
        ChunkPosition result = new ChunkPosition(centerX + dx, centerZ + dz);
        if (++dz > radius) {
            dz = -radius;
            dx++;
        }
        return result;
    }

    long total() {
        long width = radius * 2L + 1;
        return width * width;
    }
}
