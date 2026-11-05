package codes.castled.allium.compat.legacy;

import codes.castled.allium.compat.WorldHeightCompat;
import org.bukkit.World;

public final class LegacyWorldHeightCompat implements WorldHeightCompat {

    @Override
    public int minHeight(World world) {
        return 0;
    }

    @Override
    public int maxHeight(World world) {
        return world.getMaxHeight();
    }
}
