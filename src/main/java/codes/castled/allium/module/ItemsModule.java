package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.items.GiveCommand;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.item.delivery.ItemDeliveryService;
import codes.castled.allium.item.delivery.OverflowPolicy;
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
    private volatile OverflowPolicy overflow = OverflowPolicy.DROP;
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
        boolean legacy = give != null && give.getBoolean("legacy-semicolon-syntax", false);
        parser = new ItemSpecParser(legacy);
        long timeout = give == null ? 3000L : Math.max(100L, give.getLong("skull.lookup-timeout-millis", 3000L));
        long ttl = give == null ? 3_600_000L : Math.max(0L, give.getLong("skull.cache-minutes", 60L) * 60_000L);
        int size = give == null ? 256 : Math.max(1, give.getInt("skull.cache-size", 256));
        skulls = new SkullProfiles(name -> Bukkit.getPlayerExact(name), Bukkit::createPlayerProfile,
                name -> Bukkit.createPlayerProfile(name).update(), timeout, ttl, size, System::currentTimeMillis);
        ServerVersion version = ServerVersion.parse(Bukkit.getBukkitVersion());
        ItemLibrary library = new ItemLibrary(plugin.getItemService(), new ComponentAdapter(version, Bukkit.getItemFactory()), skulls);
        ItemDeliveryService delivery = new ItemDeliveryService(
                task -> Bukkit.getScheduler().runTask(plugin, task), Bukkit::isPrimaryThread, Bukkit::getPlayer,
                Bukkit.getPluginManager());
        plugin.getCommandManager().register(new GiveCommand(plugin, () -> parser, library, delivery, () -> overflow));
        plugin.logger().info("Item library enabled: /give harness, overflow " + overflow.configName() + ", raw item data as "
                + (version.hasDataComponents() ? "[...] components" : "{...} NBT") + " (server " + version + ").");
    }

    @Override
    public void onDisable() {
        if (skulls != null) {
            skulls.close();
            skulls = null;
        }
    }
}
