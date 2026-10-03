package codes.castled.allium.item;

import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Builds the item resolver chain shared by every Allium subsystem that
 * references custom items.
 *
 * <p>Each subsystem used to build its own chain, which meant two independent
 * {@link NexoItemsGate}s waiting on the same {@code NexoItemsLoadedEvent} and
 * two log lines claiming the integration. More importantly, a chain built
 * before Nexo registers its items resolves every {@code nexo:} reference as
 * missing, so the ordering between the gate and the chain is not an
 * optimisation — it is a correctness requirement.
 */
public final class ItemResolvers {

    private ItemResolvers() {}

    /**
     * Registers a resolver for every custom-item plugin currently enabled.
     *
     * <p>A plugin that is present but whose API does not match what we compiled
     * against is logged and skipped: a missing item namespace costs the
     * features that use it, whereas throwing here would take down every
     * subsystem that merely wanted a vanilla item reference.
     *
     * @param label   subsystem name used in log lines, e.g. "Allium Harvest"
     * @param logger  where to report a present-but-unusable plugin
     */
    public static ItemResolverChain build(Plugin plugin, String label, Logger logger) {
        ItemResolverChain chain = new ItemResolverChain();
        if (Bukkit.getPluginManager().isPluginEnabled("Nexo")) {
            try {
                chain.register(new NexoItemResolver());
                logger.info("[" + label + "] Nexo item integration enabled");
            } catch (Throwable t) {
                logger.warning("[" + label + "] Nexo present but API mismatch: " + t);
            }
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Oraxen")) {
            try {
                chain.register(new OraxenItemResolver());
                logger.info("[" + label + "] Oraxen item integration enabled");
            } catch (Throwable t) {
                logger.warning("[" + label + "] Oraxen present but API mismatch: " + t);
            }
        }
        return chain;
    }
}
