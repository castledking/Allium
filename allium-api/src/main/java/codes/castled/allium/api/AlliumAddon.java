package codes.castled.allium.api;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Base class for Allium addons. Its {@code onEnable} is final: it calls
 * {@link AlliumApi#connect} first, and only on success calls {@link #onAddonEnable}. A refused
 * addon (an API range this Allium does not provide, a shaded copy of the API, or no
 * {@code allium-api} declaration) never reaches its own code: it logs Allium's reason and
 * disables itself, having registered nothing.
 *
 * <pre>{@code
 * public final class FunItems extends AlliumAddon {
 *     @Override
 *     protected void onAddonEnable(AddonConnection allium) {
 *         allium.items().define(...);
 *     }
 * }
 * }</pre>
 *
 * <p>An addon that extends {@code JavaPlugin} directly must do the same itself: call
 * {@code AlliumApi.get().connect(this)} before anything else in {@code onEnable}, and stop if it
 * throws.
 */
public abstract class AlliumAddon extends JavaPlugin {

    private AddonConnection allium;

    @Override
    public final void onEnable() {
        AddonConnection connection;
        try {
            connection = AlliumApi.get().connect(this);
        } catch (AddonRefusedException | IllegalStateException e) {
            getLogger().severe("Not enabling: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        allium = connection;
        onAddonEnable(connection);
    }

    /** Called once Allium accepted the addon; register items, commands and scheduled work here. */
    protected abstract void onAddonEnable(AddonConnection allium);

    @Override
    public final void onDisable() {
        if (allium != null) {
            try {
                onAddonDisable();
            } finally {
                allium = null;
            }
        }
    }

    /**
     * Called when an accepted addon disables, before Allium releases what it registered. Not
     * called for a refused addon.
     */
    protected void onAddonDisable() {
    }

    /** This addon's connection; only valid between {@link #onAddonEnable} and {@link #onAddonDisable}. */
    protected final AddonConnection allium() {
        if (allium == null) {
            throw new IllegalStateException(getName() + " is not connected to Allium");
        }
        return allium;
    }
}
