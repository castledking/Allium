package codes.castled.allium.compat.modern;

import codes.castled.allium.compat.WorldHeightCompat;
import org.bukkit.World;

public final class ModernWorldHeightCompat implements WorldHeightCompat {

    @Override
    public int minHeight(World world) {
        return world.getMinHeight();
    }

    @Override
    public int maxHeight(World world) {
        return world.getMaxHeight();
    }
}
