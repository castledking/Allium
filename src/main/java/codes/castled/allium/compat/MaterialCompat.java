package codes.castled.allium.compat;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public final class MaterialCompat {

    private MaterialCompat() {
    }

    @Nullable
    public static Material getMaterial(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Material direct = Material.getMaterial(name.trim().toUpperCase(Locale.ROOT));
        if (direct != null) {
            return direct;
        }
        return Material.matchMaterial(name.trim());
    }

    public static boolean isAir(@Nullable Material material) {
        return material == null
                || material == Material.AIR
                || material == Material.CAVE_AIR
                || material == Material.VOID_AIR;
    }
}
