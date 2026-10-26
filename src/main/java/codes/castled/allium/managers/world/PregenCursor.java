package codes.castled.allium.managers.world;

/** Constant-memory traversal of a square of chunks, including its edge. */
public final class PregenCursor {
    public record ChunkPosition(int x, int z) { }
    private final int centerX, centerZ, radius;
    private int dx, dz;
    public PregenCursor(int centerX, int centerZ, int radius) {
        if (radius < 1 || radius > 10000) throw new IllegalArgumentException("Radius must be 1..10000 chunks");
        if (Math.abs((long) centerX) + radius > 1874999 || Math.abs((long) centerZ) + radius > 1874999)
            throw new IllegalArgumentException("Generation area exceeds Minecraft's world limit");
        this.centerX = centerX; this.centerZ = centerZ; this.radius = radius;
        this.dx = -radius; this.dz = -radius;
    }
    public boolean hasNext() { return dx <= radius; }
    public ChunkPosition next() {
        if (!hasNext()) throw new java.util.NoSuchElementException();
        ChunkPosition result = new ChunkPosition(centerX + dx, centerZ + dz);
        if (++dz > radius) { dz = -radius; dx++; }
        return result;
    }
    public long total() { long width = radius * 2L + 1; return width * width; }
}
