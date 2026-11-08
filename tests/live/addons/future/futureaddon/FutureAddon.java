package futureaddon;

import codes.castled.allium.api.AddonRefusedException;
import codes.castled.allium.api.AlliumApi;
import org.bukkit.plugin.java.JavaPlugin;

/** Declares allium-api "^2.0"; Allium (API 1.x) refuses it. */
public final class FutureAddon extends JavaPlugin {
    @Override
    public void onEnable() {
        try {
            AlliumApi.get().connect(this);
            getLogger().info("FUTURE-CONNECTED (should not happen)");
        } catch (AddonRefusedException e) {
            getLogger().info("FUTURE-REFUSED " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("FUTURE-DISABLED");
    }
}
