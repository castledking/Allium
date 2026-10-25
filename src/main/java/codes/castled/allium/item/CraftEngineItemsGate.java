package codes.castled.allium.item;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Holds the harvest module back until CraftEngine has loaded its packs.
 *
 * <p>CraftEngine enables before Allium but only fills its item and block
 * registries when it reloads its packs, which happens after the enable phase.
 * Loading then would not just log errors: stored crops whose definition failed
 * to load are dropped from the database when their chunk loads. CraftEngine
 * fires {@link CraftEngineReloadEvent} when its packs are ready, and again
 * after every {@code /ce reload}, which is used to re-resolve everything.
 *
 * <p>Touches CraftEngine classes directly; only use it when CraftEngine is
 * enabled.
 */
public final class CraftEngineItemsGate implements Listener {

    private final Runnable onFirstLoad;
    private final Runnable onReload;
    private boolean fired;

    private CraftEngineItemsGate(Runnable onFirstLoad, Runnable onReload, boolean alreadyLoaded) {
        this.onFirstLoad = onFirstLoad;
        this.onReload = onReload;
        this.fired = alreadyLoaded;
    }

    /**
     * Runs {@code onFirstLoad} as soon as CraftEngine's packs are available
     * (now, if they already are), and {@code onReload} each time CraftEngine
     * reloads them after that.
     */
    public static void await(Plugin plugin, Runnable onFirstLoad, Runnable onReload) {
        boolean loaded = isLoaded();
        Bukkit.getPluginManager().registerEvents(
            new CraftEngineItemsGate(onFirstLoad, onReload, loaded), plugin);
        if (loaded) {
            onFirstLoad.run();
        }
    }

    /** Whether CraftEngine has already filled its registries. */
    public static boolean isLoaded() {
        try {
            return !CraftEngineItems.loadedItems().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    @EventHandler
    public void onReload(CraftEngineReloadEvent event) {
        if (!fired) {
            fired = true;
            onFirstLoad.run();
        } else {
            onReload.run();
        }
    }
}
