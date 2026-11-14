package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.items.GiveCommand;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.item.delivery.ItemDeliveryService;
import codes.castled.allium.item.delivery.OverflowPolicy;
import codes.castled.allium.item.delivery.SkullFallback;
import codes.castled.allium.item.library.ComponentAdapter;
import codes.castled.allium.item.library.ItemLibrary;
import codes.castled.allium.item.library.ServerVersion;
import codes.castled.allium.item.library.SkullProfiles;
import codes.castled.allium.item.spec.ItemSpecParser;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Item library and the M1 {@code /give} harness: {@code items.give} settings, the skull profile
 * cache, raw component handling for this server version, and delivery.
 */
public final class ItemsModule extends AbstractModule {

    private SkullProfiles skulls;
    private ItemLibrary library;
    private ItemDeliveryService delivery;
    private volatile OverflowPolicy overflow = OverflowPolicy.DROP;
    private volatile SkullFallback skullFallback = SkullFallback.UNTEXTURED;
    private volatile ItemSpecParser parser = new ItemSpecParser(false);

    public ItemsModule(PluginStart plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "items";
    }

    @Override
    public void onEnable() {
        ConfigurationSection give = plugin.getConfig().getConfigurationSection("items.give");
        String rawPolicy = give == null ? "drop" : give.getString("overflow", "drop");
        overflow = OverflowPolicy.fromConfig(rawPolicy).orElseGet(() -> {
            plugin.logger().warning("items.give.overflow must be drop, refuse or partial; using drop");
            return OverflowPolicy.DROP;
        });
        String rawFallback = give == null ? "untextured" : give.getString("skull-timeout-fallback", "untextured");
        skullFallback = SkullFallback.fromConfig(rawFallback).orElseGet(() -> {
            plugin.logger().warning("items.give.skull-timeout-fallback must be untextured or refuse; using untextured");
            return SkullFallback.UNTEXTURED;
        });
        int perMinute = give == null ? 30 : give.getInt("skull.max-lookups-per-minute", 30);
        boolean legacy = give != null && give.getBoolean("legacy-semicolon-syntax", false);
        parser = new ItemSpecParser(legacy);
        long timeout = give == null ? 3000L : Math.max(100L, give.getLong("skull.lookup-timeout-millis", 3000L));
        long ttl = give == null ? 3_600_000L : Math.max(0L, give.getLong("skull.cache-minutes", 60L) * 60_000L);
        int size = give == null ? 256 : Math.max(1, give.getInt("skull.cache-size", 256));
        skulls = new SkullProfiles(name -> Bukkit.getPlayerExact(name), Bukkit::createPlayerProfile,
                name -> Bukkit.createPlayerProfile(name).update(), timeout, ttl, size, System::currentTimeMillis, perMinute,
                profile -> profile.getTextures() != null && profile.getTextures().getSkin() != null,
                plugin.getScheduler().boundedPool(SkullProfiles.POOL_NAME, SkullProfiles.POOL_THREADS, SkullProfiles.POOL_QUEUE));
        ServerVersion version = ServerVersion.parse(Bukkit.getBukkitVersion());
        library = new ItemLibrary(plugin.getItemService(), new ComponentAdapter(version, Bukkit.getItemFactory()), skulls);
        delivery = new ItemDeliveryService(
                plugin.getScheduler().serverExecutor(), plugin.getScheduler()::isServerThread, Bukkit::getPlayer,
                Bukkit.getPluginManager(), () -> skullFallback);
        String route = registerGive(new GiveCommand(plugin, () -> parser, library, delivery, () -> overflow));
        plugin.logger().info("Item library enabled: /give harness (" + route + "), overflow " + overflow.configName() + ", skull fallback "
                + skullFallback.configName() + ", raw item data as "
                + (version.hasDataComponents() ? "[...] components" : "{...} NBT") + " (server " + version + ").");
    }

    public ItemSpecParser parser() {
        return parser;
    }

    public ItemLibrary library() {
        return library;
    }

    public ItemDeliveryService delivery() {
        return delivery;
    }

    private static final String PAPER_GIVE = "codes.castled.allium.command.paper.PaperBrigadierGive";
    private boolean paperGive;

    /**
     * On Paper 1.20.6+ {@code /give} is registered through Paper's Brigadier API, because Paper runs
     * legacy Bukkit commands under {@code /execute} with the initiating sender and no as/at
     * context. Allium's legacy labels are yielded first so only one {@code give} handles a call.
     * Elsewhere (Spigot, older Paper) the Bukkit command is used; there, {@code /execute ... run
     * give} resolves to vanilla's give with the right context.
     */
    private String registerGive(GiveCommand give) {
        if (plugin.getCommandManager().getFamilyPolicy().isDisabled("give")) {
            plugin.getCommandManager().register(give);
            return "disabled";
        }
        if (paperBrigadierAvailable()) {
            try {
                codes.castled.allium.command.CommandMapBridge.yieldCommandFamily(plugin.getServer(), plugin, give.spec());
                Class.forName(PAPER_GIVE).getMethod("register", org.bukkit.plugin.java.JavaPlugin.class, GiveCommand.class,
                                String.class, String.class, java.util.function.Consumer.class)
                        .invoke(null, plugin, give, give.spec().basePermission(), give.spec().description(),
                                (java.util.function.Consumer<java.util.Set<String>>) labels ->
                                        plugin.logger().info("/give registered through Paper's command API as " + labels + "."));
                paperGive = true;
                return "Paper command API";
            } catch (ReflectiveOperationException | LinkageError e) {
                plugin.logger().warning("Paper's command API is present but /give could not use it (" + e
                        + "); falling back to the Bukkit command, where /execute ... run give loses its context on Paper.");
            }
        }
        plugin.getCommandManager().register(give);
        return "Bukkit command";
    }

    private static boolean paperBrigadierAvailable() {
        try {
            Class.forName("io.papermc.paper.command.brigadier.Commands");
            Class.forName("io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents");
            org.bukkit.plugin.Plugin.class.getMethod("getLifecycleManager");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    /** Skull profile cache counters, or {@code null} while the module is disabled. */
    public SkullProfiles.Stats skullStats() {
        SkullProfiles current = skulls;
        return current == null ? null : current.stats();
    }

    @Override
    public void onDisable() {
        if (paperGive) {
            try {
                Class.forName(PAPER_GIVE).getMethod("unbind").invoke(null);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // nothing was registered
            }
            paperGive = false;
        }
        if (skulls != null) {
            skulls.close();
            skulls = null;
        }
        library = null;
        delivery = null;
    }
}
