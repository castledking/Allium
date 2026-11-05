package codes.castled.allium.compat;

import org.bukkit.World;

public final class WorldHeightCompatProvider {

    private static final WorldHeightCompat INSTANCE = new StandardWorldHeightCompat();

    private WorldHeightCompatProvider() {
    }

    public static WorldHeightCompat current() {
        return INSTANCE;
    }

    private static final class StandardWorldHeightCompat implements WorldHeightCompat {

        @Override
        public int minHeight(World world) {
            return world.getMinHeight();
        }

        @Override
        public int maxHeight(World world) {
            return world.getMaxHeight();
        }
    }
}
