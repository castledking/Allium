package shadedaddon;

import codes.castled.allium.api.AddonConnection;
import codes.castled.allium.api.AlliumAddon;
import codes.castled.allium.api.ItemDefinition;
import org.bukkit.Material;

import java.util.Map;

/**
 * Its jar bundles the API classes, as an addon that used implementation/shadow instead of
 * compileOnly would. Its copy of AlliumAddon finds no Allium service, so this hook never runs.
 */
public final class ShadedAddon extends AlliumAddon {
    @Override
    protected void onAddonEnable(AddonConnection allium) {
        getLogger().info("SHADED-HOOK-REACHED (should not happen)");
        allium.items().define(new ItemDefinition("shaded_item", Material.STICK, "Shaded", null, Map.of(), false));
    }
}
