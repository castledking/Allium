package codes.castled.allium.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Allium's addon API, registered in Bukkit's {@code ServicesManager}.
 *
 * <p>An addon declares {@code depend: [Allium]} and {@code allium-api: "^1.0"} in its
 * {@code plugin.yml}, compiles against the API jar with {@code compileOnly} (never shading it), and
 * calls {@link #connect} from {@code onEnable}. Installing or updating an addon needs a server
 * restart.
 */
public interface AlliumApi {

    /** This Allium's API version (not the plugin version). */
    ApiVersion version();

    /**
     * The addon's handle to Allium. Everything it registers or schedules through the handle is
     * owned by the addon and removed or cancelled when the addon disables.
     *
     * @throws AddonRefusedException if Allium refused the addon; the message explains why
     */
    AlliumAddon connect(Plugin addon);

    /**
     * Allium's API service.
     *
     * @throws IllegalStateException if Allium is not enabled, or if the caller bundles its own copy
     *                               of these classes (then Bukkit's service is registered under
     *                               Allium's copy, which the caller cannot see)
     */
    static AlliumApi get() {
        AlliumApi api = Bukkit.getServicesManager().load(AlliumApi.class);
        if (api == null) {
            throw new IllegalStateException("The Allium API service was not found. Either Allium is not enabled, or this plugin "
                    + "bundles (shades) its own copy of the Allium API: depend on Allium and compile the API as compileOnly.");
        }
        return api;
    }
}
