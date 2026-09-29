package codes.castled.allium.harvest.integration;

import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.api.events.NexoItemsLoadedEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Holds the harvest module back until Nexo has registered its items.
 *
 * <p>Nexo enables before Allium but only loads its items afterwards, so at
 * Allium's enable every {@code nexo:} reference fails to resolve. Loading then
 * would not just log errors: stored crops whose definition failed to load are
 * dropped from the database when their chunk loads. Nexo fires
 * {@link NexoItemsLoadedEvent} when its items are ready, and again after every
 * {@code /nexo reload}, which is used to re-resolve everything.
 *
 * <p>Touches Nexo classes directly; only use it when Nexo is enabled.
 */
public final class NexoItemsGate implements Listener {

    private final Runnable onFirstLoad;
    private final Runnable onReload;
    private boolean fired;

    private NexoItemsGate(Runnable onFirstLoad, Runnable onReload, boolean alreadyLoaded) {
        this.onFirstLoad = onFirstLoad;
        this.onReload = onReload;
        this.fired = alreadyLoaded;
    }

    /**
     * Runs {@code onFirstLoad} as soon as Nexo's items are available (now, if
     * they already are), and {@code onReload} each time Nexo reloads them after that.
     */
    public static void await(Plugin plugin, Runnable onFirstLoad, Runnable onReload) {
        boolean loaded = !NexoItems.itemNames().isEmpty();
        Bukkit.getPluginManager().registerEvents(new NexoItemsGate(onFirstLoad, onReload, loaded), plugin);
        if (loaded) {
            onFirstLoad.run();
        }
    }

    @EventHandler
    public void onItemsLoaded(NexoItemsLoadedEvent event) {
        if (!fired) {
            fired = true;
            onFirstLoad.run();
        } else {
            onReload.run();
        }
    }
}
