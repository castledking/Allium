package codes.castled.allium.harvest.kitchen;

import codes.castled.allium.harvest.HarvestBranding;
import codes.castled.allium.harvest.crop.def.ValidationIssue;
import codes.castled.allium.item.ItemResolverChain;
import java.io.File;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The kitchen: storage bags, the kneading space, and pies (assembly, baking,
 * cooling, eating). Configured in {@code harvest/kitchen.yml} and reloaded
 * with the rest of the harvest module.
 *
 * <p>Listeners are registered once and read the current configuration through
 * a supplier, so a reload only has to swap the parsed config.
 */
public final class KitchenModule {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final ItemResolverChain items;
    private final File dataFolder;

    private volatile KitchenConfig config = KitchenConfig.disabled();
    private PieHolograms holograms = PieHolograms.NONE;
    private StoveService stoves;

    public KitchenModule(JavaPlugin plugin, ItemResolverChain items, File dataFolder) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.items = items;
        this.dataFolder = dataFolder;
    }

    public List<ValidationIssue> enable() {
        List<ValidationIssue> issues = load();
        BuildPermission permission = new BuildPermission(logger);
        BagListener bags = new BagListener(() -> config, items);
        KneadingService kneading = new KneadingService(() -> config, items, bags);

        if (Bukkit.getPluginManager().isPluginEnabled("DecentHolograms")) {
            try {
                long cycle = config.pies() == null ? 40L : config.pies().hologram().cycleTicks();
                holograms = new DecentPieHolograms(plugin, cycle);
            } catch (Throwable t) {
                logger.warning("[" + HarvestBranding.DISPLAY_NAME + "] DecentHolograms present but API mismatch: " + t);
            }
        }
        PieService pies = new PieService(plugin, () -> config, items, holograms);
        PieListener pieListener = new PieListener(plugin, pies, items, permission);

        var manager = Bukkit.getPluginManager();
        manager.registerEvents(bags, plugin);
        manager.registerEvents(new KneadingListener(kneading, permission), plugin);
        if (manager.isPluginEnabled("Nexo")) {
            try {
                manager.registerEvents(new NexoKneadingListener(kneading, permission), plugin);
            } catch (Throwable t) {
                logger.warning("[" + HarvestBranding.DISPLAY_NAME + "] Nexo kneading stations unavailable: " + t);
            }
            try {
                stoves = new StoveService(plugin, () -> config);
                NexoStoveListener stoveListener = new NexoStoveListener(plugin, stoves, permission);
                manager.registerEvents(stoveListener, plugin);
                stoveListener.bootstrapLoadedChunks();
            } catch (Throwable t) {
                stoves = null;
                logger.warning("[" + HarvestBranding.DISPLAY_NAME + "] Nexo furnaces unavailable: " + t);
            }
        }
        manager.registerEvents(pieListener, plugin);
        pieListener.bootstrapLoadedChunks();
        return issues;
    }

    public List<ValidationIssue> reload() {
        return load();
    }

    public void disable() {
        holograms.removeAll();
        if (stoves != null) {
            stoves.shutdown();
        }
    }

    private List<ValidationIssue> load() {
        File file = new File(dataFolder, KitchenConfig.FILE);
        KitchenConfig.LoadResult result = KitchenConfig.load(YamlConfiguration.loadConfiguration(file), items);
        config = result.config();
        if (config.enabled()) {
            logger.info("[" + HarvestBranding.DISPLAY_NAME + "] Kitchen: " + config.bags().size() + " bag(s), kneading "
                + (config.kneading() == null ? "off" : "on") + ", "
                + (config.pies() == null ? "pies off" : config.pies().types().size() + " pie type(s)") + ", "
                + config.furnaces().size() + " furnace station(s)");
        }
        return result.issues();
    }
}
