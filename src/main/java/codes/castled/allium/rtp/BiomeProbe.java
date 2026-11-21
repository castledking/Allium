package codes.castled.allium.rtp;

import org.bukkit.Keyed;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * The biome a spot would generate with, without loading its chunk: Paper's
 * {@code RegionAccessor#getComputedBiome}. Spigot has no such call, so there every candidate's chunk is
 * loaded first. Used only to skip candidates early; the landing's real biome is always checked again.
 */
final class BiomeProbe {

    @Nullable
    private static final Method COMPUTED = find();

    private BiomeProbe() {
    }

    @Nullable
    private static Method find() {
        try {
            return World.class.getMethod("getComputedBiome", int.class, int.class, int.class);
        } catch (NoSuchMethodException | RuntimeException absent) {
            return null;
        }
    }

    static boolean available() {
        return COMPUTED != null;
    }

    /** Server thread. The generated biome's key ({@code deep_ocean}) at the block, or null when unknown. */
    @Nullable
    static String computed(World world, int x, int y, int z) {
        if (COMPUTED == null) return null;
        try {
            // Through Keyed: Biome is an enum on 1.20 and an interface on 26.x.
            Object biome = COMPUTED.invoke(world, x, y, z);
            return biome instanceof Keyed keyed ? RtpSettings.biomeKey(keyed.getKey().getKey()) : null;
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return null;
        }
    }
}
