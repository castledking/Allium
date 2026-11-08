package futureaddon;

import codes.castled.allium.api.AddonConnection;
import codes.castled.allium.api.AlliumAddon;
import codes.castled.allium.api.ItemDefinition;
import org.bukkit.Material;

import java.util.Map;

/**
 * Declares allium-api "^2.0"; Allium (API 1.x) refuses it. Its hook would define an item, start a
 * repeating task and log markers, so the gate can show that a refused AlliumAddon registered nothing.
 */
public final class FutureAddon extends AlliumAddon {
    @Override
    protected void onAddonEnable(AddonConnection allium) {
        getLogger().info("FUTURE-HOOK-REACHED (should not happen)");
        allium.items().define(new ItemDefinition("future_item", Material.STICK, "Future", null, Map.of(), false));
        allium.scheduler().repeatOnServer(() -> {
            getLogger().info("FUTURE-TICK (should not happen)");
            return true;
        }, 50L, 1_000L);
    }
}
