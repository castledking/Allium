package codes.castled.allium.worth;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.economy.EconomyDisplay;
import codes.castled.allium.economy.Money;
import codes.castled.allium.module.ItemsModule;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** {@code worth.yml}: prices, what may be sold, and {@code /setworth}. Server thread. */
public final class WorthService {

    public static final String PERMISSION_WORTH = "allium.worth";
    public static final String PERMISSION_SELL = "allium.sell";
    public static final String PERMISSION_SELL_HAND = "allium.sell.hand";
    public static final String PERMISSION_SELL_BULK = "allium.sell.bulk";
    public static final String PERMISSION_SELL_CREATIVE = "allium.sell.creative";
    public static final String PERMISSION_SETWORTH = "allium.setworth";

    private final PluginStart plugin;
    private final Path file;
    private final NamespacedKey creativeKey;
    private final List<String> multiplierNodes = new ArrayList<>();
    private volatile WorthSettings settings;
    private LiteFishWorth liteFish;
    private final java.util.Map<String, BigDecimal> addonPrices = new java.util.HashMap<>();

    public WorthService(PluginStart plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("worth.yml");
        this.creativeKey = new NamespacedKey(codes.castled.allium.PluginStart.bukkitPlugin(plugin), "creative_item");
    }

    public void enable() throws IOException, InvalidConfigurationException {
        if (!Files.exists(file)) plugin.saveResource("worth.yml", false);
        install(parse(read()));
        var fishing = plugin.getServer().getPluginManager().getPlugin("LiteFish");
        if (fishing != null && fishing.isEnabled()) {
            try {
                liteFish = LiteFishWorth.connect(fishing);
            } catch (ReflectiveOperationException | LinkageError failure) {
                plugin.logger().warning("LiteFish pricing unavailable: " + failure.getMessage());
            }
        }
    }

    public void disable() {
        unregisterMultipliers();
    }

    /** Reads and validates {@code worth.yml} without publishing prices or permissions yet. */
    public codes.castled.allium.config.ReloadCoordinator.Prepared prepareReload()
            throws IOException, InvalidConfigurationException {
        WorthSettings next = parse(read());
        return codes.castled.allium.config.ReloadCoordinator.Prepared.of(() -> install(next));
    }

    /** Re-reads {@code worth.yml}; on an error the previous prices stay and false is returned. */
    public boolean reload() {
        try {
            install(parse(read()));
            return true;
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException invalid) {
            plugin.logger().warning("Invalid worth.yml; the previous prices are kept: " + invalid.getMessage());
            return false;
        }
    }

    public WorthSettings settings() {
        return settings;
    }

    private void install(WorthSettings next) {
        settings = next;
        unregisterMultipliers();
        // An undeclared node defaults to op: ops would all sell at the highest multiplier.
        for (String tier : next.multipliers().keySet()) {
            if (tier.equals("default")) continue;
            String node = WorthSettings.MULTIPLIER_PERMISSION + tier;
            if (plugin.getServer().getPluginManager().getPermission(node) != null) continue;
            plugin.getServer().getPluginManager().addPermission(new Permission(node,
                    "Sell at the '" + tier + "' price multiplier of worth.yml", PermissionDefault.FALSE));
            multiplierNodes.add(node);
        }
    }

    private void unregisterMultipliers() {
        for (String node : multiplierNodes) plugin.getServer().getPluginManager().removePermission(node);
        multiplierNodes.clear();
    }

    private YamlConfiguration read() throws IOException, InvalidConfigurationException {
        if (Files.size(file) > 1_048_576) throw new IOException("worth.yml exceeds 1 MiB");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        return yaml;
    }

    private WorthSettings parse(ConfigurationSection yaml) {
        return WorthSettings.read(yaml, this::alias, plugin.logger()::warning);
    }

    @Nullable
    private Material alias(String name) {
        return plugin.getModuleManager() != null && plugin.getModuleManager().getModule("items") instanceof ItemsModule items
                && items.isEnabled() ? items.material(name) : null;
    }

    /** The item {@code name} names: an item name, an Essentials worth key or an Allium alias. */
    @Nullable
    public Material material(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null) {
            String key = WorthSettings.key(name);
            for (Material candidate : Material.values()) {
                if (!candidate.name().startsWith("LEGACY_") && WorthSettings.key(candidate).equals(key)) {
                    material = candidate;
                    break;
                }
            }
        }
        if (material == null) material = alias(name);
        return material != null && material.isItem() && !material.isAir() ? material : null;
    }

    public BigDecimal multiplier(Player player) {
        return settings.multiplier(player::hasPermission);
    }

    public String format(Money amount) {
        return EconomyDisplay.read(plugin.getConfig()).format(amount);
    }

    public static String name(ItemStack item) {
        return UtilitySupport.itemName(item);
    }

    public void setAddonWorth(String behavior, BigDecimal price) {
        if (price.signum() < 0 || price.compareTo(WorthSettings.MAX_PRICE) > 0) {
            throw new IllegalArgumentException("Invalid addon item price for " + behavior);
        }
        addonPrices.put(behavior, price.setScale(2, java.math.RoundingMode.HALF_UP));
    }

    public void removeAddonWorth(String behavior) {
        addonPrices.remove(behavior);
    }

    /** A verified, registered custom item is priced by its owner instead of its vanilla carrier. */
    public Optional<BigDecimal> price(ItemStack item) {
        var fish = fish(item);
        if (fish.isPresent()) return fish.map(LiteFishWorth.Quote::price);
        var ref = plugin.getItemService() == null ? null : plugin.getItemService().identify(item).orElse(null);
        if (ref != null && (!ref.appearance().isVanilla() || ref.behaviorId().isPresent())) {
            return ref.behaviorId().map(addonPrices::get);
        }
        return price(item.getType());
    }

    @Nullable
    public ItemStack item(String reference) {
        if (reference.toLowerCase(Locale.ROOT).startsWith("allium:") && plugin.getItemService() != null) {
            try {
                return plugin.getItemService().create(plugin.getItemService().parseRef(reference)).itemStack().orElse(null);
            } catch (IllegalArgumentException invalid) {
                return null;
            }
        }
        Material material = material(reference);
        return material == null ? null : new ItemStack(material);
    }

    private Optional<LiteFishWorth.Quote> fish(ItemStack item) {
        return liteFish == null ? Optional.empty() : liteFish.quote(item);
    }

    public String reference(ItemStack item) {
        var ref = plugin.getItemService() == null ? null : plugin.getItemService().identify(item).orElse(null);
        return ref == null || ref.appearance().isVanilla()
                ? item.getType().name().toLowerCase(Locale.ROOT) : ref.appearance().asString();
    }

    /** Why {@code item} may not be sold whatever its price, or null. */
    @Nullable
    public SalePlan.Refusal refusal(ItemStack item) {
        return refusal(item, false);
    }

    @Nullable
    SalePlan.Refusal refusal(ItemStack item, boolean quoted) {
        var identified = plugin.getItemService() == null ? null : plugin.getItemService().identify(item).orElse(null);
        boolean custom = identified != null && (!identified.appearance().isVanilla() || identified.behaviorId().isPresent());
        boolean fish = fish(item).isPresent();
        if (!quoted && custom && !fish && identified.behaviorId().filter(addonPrices::containsKey).isEmpty()) return SalePlan.Refusal.CUSTOM;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        if (meta.getPersistentDataContainer().has(creativeKey, PersistentDataType.STRING)) return SalePlan.Refusal.CREATIVE;
        if (!quoted && !custom && !fish && meta.hasDisplayName() && !settings.allowNamedItems()) return SalePlan.Refusal.NAMED;
        if (meta instanceof BlockStateMeta block && block.hasBlockState() && block.getBlockState() instanceof Container container
                && !container.getInventory().isEmpty()) {
            return SalePlan.Refusal.CONTAINER;
        }
        if (meta instanceof BundleMeta bundle && bundle.hasItems()) return SalePlan.Refusal.CONTAINER;
        return null;
    }

    /** The price of one {@code material} in {@code worth.yml}, before any multiplier. */
    public Optional<BigDecimal> price(Material material) {
        return settings.price(material);
    }

    /**
     * Sets {@code material}'s price ({@code null} stops it selling) and saves {@code worth.yml}. Any key
     * that already names the material (an Essentials or alias key) gives way to its item name.
     */
    public void setWorth(Material material, @Nullable BigDecimal price) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = read();
        ConfigurationSection worth = yaml.getConfigurationSection("worth");
        if (worth == null) worth = yaml.createSection("worth");
        String target = WorthSettings.key(material);
        for (String key : List.copyOf(worth.getKeys(false))) {
            if (WorthSettings.key(key).equals(target) || material == alias(key)) worth.set(key, null);
        }
        if (price != null) worth.set(material.name().toLowerCase(Locale.ROOT), price.doubleValue());
        WorthSettings next = parse(yaml);
        save(yaml);
        install(next);
    }

    private void save(YamlConfiguration yaml) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), ".allium-worth-", ".tmp");
        try {
            Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
            Files.copy(file, file.resolveSibling("worth.yml.before-edit"), StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
