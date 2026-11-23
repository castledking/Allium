package codes.castled.crops;

import codes.castled.allium.api.AddonConnection;
import codes.castled.allium.api.AlliumAddon;
import codes.castled.allium.api.ItemDefinition;
import org.bukkit.*;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class CropsPlugin extends AlliumAddon {
    static final int JADE_SEED_CMD_OFFSET = 1_000_000;
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern LEGACY_CODE_PATTERN =
            Pattern.compile("[&§](?:x(?:[&§][0-9a-fA-F]){6}|#[0-9a-fA-F]{6}|[0-9a-fA-Fk-oK-OrR])");

    private ModelWorld world;
    private boolean cropModelInJade;

    @Override
    protected void onAddonEnable(AddonConnection core) {
        saveDefaultConfig();
        cropModelInJade = getConfig().getBoolean("crop-model-in-jade", false);
        try {
            var catalog = Catalog.read(definition("crops.yml").getConfigurationSection("crops"));
            Set<String> cropStageIds = new HashSet<>();
            for (Catalog.Crop crop : catalog.values()) {
                for (Catalog.Path path : crop.paths().values()) {
                    for (Catalog.Stage stage : path.stages()) {
                        cropStageIds.add(stripNamespace(stage.visual().item()));
                    }
                }
            }

            var kitchenConfig = definition("kitchen.yml");
            String bagId = stripNamespace(kitchenConfig.getString("bags.flour_bag.item", ""));
            var itemData = definition("items.yml");
            var bundledItems = new YamlConfiguration();
            try (var reader = new InputStreamReader(getResource("items.yml"), StandardCharsets.UTF_8)) {
                bundledItems.load(reader);
            }
            itemData.setDefaults(bundledItems);
            itemData.options().copyDefaults(true);
            var items = itemData.getConfigurationSection("items");
            java.util.List<String> itemIds = new java.util.ArrayList<>();
            for (String id : items.getKeys(false)) {
                itemIds.add(id);
                var row = items.getConfigurationSection(id);
                boolean cropStage = cropStageIds.contains(id);
                String displayName = normalizeDisplayName(placedName(id, row.getString("name")), cropStage);
                int cmd = resolveCustomModelData(row.getInt("cmd"), cropStage, cropModelInJade);
                var item = new ItemDefinition(id, Material.PAPER, displayName, cmd, Map.of(), true);
                if (id.equals(bagId)) core.items().define(item);
                else core.items().defineStackableSigned(item);
                core.items().decorate(id, stack -> {
                    ensureNotItalic(stack);
                    JadeLabel.apply(stack, "crops:" + id);
                    if (id.equals(bagId)) Kitchen.showBagAmount(this, kitchenConfig, stack, 0);
                });
            }
            java.util.Collections.sort(itemIds);
            var prices = definition("prices.yml").getConfigurationSection("prices");
            for (String id : prices.getKeys(false)) core.items().setWorth(id, new BigDecimal(prices.getString(id)));

            world = new ModelWorld(this);
            var farm = new FarmInputs(this, world, catalog.values(), definition("fertilizers.yml"), definition("sprinklers.yml"));
            var crops = new Planting(this, world, catalog, farm);
            var kitchen = new Kitchen(this, world, kitchenConfig);
            kitchen.registerRecipes(definition("recipes.yml").getConfigurationSection("recipes"));
            var command = new CropsCommand(this, world, crops, kitchen, itemIds);
            core.commands().register(command.spec(), command::execute, command::complete);
            getServer().getPluginManager().registerEvents(crops, this);
            getServer().getPluginManager().registerEvents(kitchen, this);
            getServer().getPluginManager().registerEvents(farm, this);
            world.start(placement -> {
                switch (placement.kind) {
                    case "crop" -> crops.restore(placement);
                    case "sprinkler" -> farm.restore(placement);
                    default -> kitchen.restore(placement);
                }
            });
            long interval = Math.max(1L, getConfig().getLong("growth.interval-ticks", 20)) * 50L;
            core.scheduler().repeatOnServer(() -> {
                if (core.isFolia()) {
                    for (var placement : world.active()) {
                        core.runForEntity(placement.hitbox, () -> tickPlacement(placement, crops, farm, kitchen),
                                () -> world.forgetRetired(placement), 0L);
                    }
                    return true;
                }
                for (var placement : world.active()) {
                    tickPlacement(placement, crops, farm, kitchen);
                }
                kitchen.coolDrops();
                return true;
            }, interval, interval);
            getLogger().info("Loaded " + crops.count() + " crops and " + kitchen.pieCount() + " Kitchen pie types.");
        } catch (IOException | InvalidConfigurationException | RuntimeException invalid) {
            getLogger().log(Level.SEVERE, "Could not start Crops", invalid);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private YamlConfiguration definition(String name) throws IOException, InvalidConfigurationException {
        java.io.File file = new java.io.File(getDataFolder(), name);
        if (!file.isFile()) saveResource(name, false);
        var yaml = new YamlConfiguration();
        yaml.load(file);
        return yaml;
    }

    @Override
    protected void onAddonDisable() {
        if (world != null) world.stop();
    }

    AddonConnection connection() { return allium(); }
    boolean cropModelInJade() { return cropModelInJade; }

    static String stripNamespace(String ref) {
        if (ref == null) return "";
        int colon = ref.lastIndexOf(':');
        return colon >= 0 ? ref.substring(colon + 1) : ref;
    }

    static String stripColors(String input) {
        if (input == null) return null;
        String stripped = TAG_PATTERN.matcher(input).replaceAll("");
        stripped = LEGACY_CODE_PATTERN.matcher(stripped).replaceAll("");
        return stripped.trim();
    }

    static String normalizeDisplayName(String rawName, boolean cropStage) {
        if (rawName == null) return null;
        if (cropStage) {
            return stripColors(rawName);
        }
        if (rawName.isEmpty() || rawName.startsWith("&") || rawName.startsWith("§") || rawName.startsWith("<")) {
            return rawName;
        }
        return "&f" + rawName;
    }

    /**
     * A placed model's name without the " Placed" earlier catalogues gave it: Jade shows this name,
     * and the item it stands for has none. Catalogues saved by those versions keep working.
     */
    static String placedName(String id, String name) {
        if (name == null || !id.matches(".*_placed(_slice\\d+)?")) return name;
        return name.replaceFirst("(?i)\\s+placed\\s*$", "");
    }

    static int resolveCustomModelData(int baseCmd, boolean cropStage, boolean cropModelInJade) {
        return (cropStage && !cropModelInJade) ? baseCmd + JADE_SEED_CMD_OFFSET : baseCmd;
    }

    static void ensureNotItalic(ItemStack stack) {
        if (stack == null) return;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;
        try {
            Method getDisplay = ItemMeta.class.getMethod("displayName");
            Object component = getDisplay.invoke(meta);
            if (component != null) {
                Class<?> compClass = Class.forName("net.kyori.adventure.text.Component");
                Class<?> decoClass = Class.forName("net.kyori.adventure.text.format.TextDecoration");
                Class<?> stateClass = Class.forName("net.kyori.adventure.text.format.TextDecoration$State");
                Object italic = decoClass.getField("ITALIC").get(null);
                Object falseState = stateClass.getField("FALSE").get(null);
                Object updated = compClass.getMethod("decorationIfAbsent", decoClass, stateClass)
                        .invoke(component, italic, falseState);
                ItemMeta.class.getMethod("displayName", compClass).invoke(meta, updated);
                stack.setItemMeta(meta);
                return;
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Spigot / non-Paper runtime: fall through to legacy non-italic prefix.
        }
        String legacy = meta.getDisplayName();
        if (legacy != null && !legacy.isEmpty() && !legacy.startsWith("§")) {
            meta.setDisplayName("§f" + legacy);
            stack.setItemMeta(meta);
        }
    }

    ItemStack item(String reference, int amount) {
        return allium().items().create(reference, amount).stack().orElse(null);
    }

    String reference(ItemStack stack) {
        return allium().items().identify(stack).map(identity -> {
            if (identity.appearance().startsWith("allium:") && identity.behavior().isEmpty()) return "";
            return identity.appearance();
        }).orElse("");
    }

    private void tickPlacement(ModelWorld.Placed placement, Planting crops, FarmInputs farm, Kitchen kitchen) {
        if (!placement.active) return;
        if (!placement.hitbox.isValid()) {
            world.remove(placement);
            return;
        }
        switch (placement.kind) {
            case "crop" -> crops.tick(placement);
            case "sprinkler" -> farm.tick(placement);
            default -> kitchen.tick(placement);
        }
    }

    void give(Player player, ItemStack stack) {
        allium().onOwner(player, () -> {
            if (!player.isOnline()) return;
            player.getInventory().addItem(stack).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        });
    }

    static void spend(Player player, ItemStack stack, int amount) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        stack.setAmount(stack.getAmount() - amount);
        player.getInventory().setItemInMainHand(stack);
    }
}
