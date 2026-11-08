package shadedaddon;

import codes.castled.allium.api.AlliumApi;
import org.bukkit.plugin.java.JavaPlugin;

/** Its jar bundles the API classes, as an addon that used implementation/shadow instead of compileOnly would. */
public final class ShadedAddon extends JavaPlugin {
    @Override
    public void onEnable() {
        try {
            AlliumApi.get().connect(this);
            getLogger().info("SHADED-CONNECTED (should not happen)");
        } catch (RuntimeException e) {
            getLogger().info("SHADED-NO-SERVICE " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("SHADED-DISABLED");
    }
}
