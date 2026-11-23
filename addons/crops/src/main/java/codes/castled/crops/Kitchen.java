package codes.castled.crops;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.FurnaceStartSmeltEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Function;
import java.util.function.LongSupplier;

final class Kitchen implements Listener {
    private static final NamespacedKey ALLIUM_BEHAVIOR_KEY = new NamespacedKey("allium", "behavior_id");
    private static final NamespacedKey ALLIUM_SIGNATURE_KEY = new NamespacedKey("allium", "signature");
    private static final String TAMPERED_REF = "\0tampered";

    private record Step(Set<String> items, int amount, boolean filling, String name) {}
    private record Pie(String id, Set<String> fillings, String fillingName, String crust, String crustModel, String coldItem,
                       String bakedItem, String filled, List<String> progress, List<String> cold, List<String> baked,
                       List<Step> steps, Set<Integer> skipped, boolean standard) {}
    private record CraftRecipe(NamespacedKey key, String resultRef, int resultAmount, boolean shaped,
                               List<String> shape, Map<Character, Set<String>> keyRefs,
                               List<Set<String>> shapelessRefs, boolean hasCustomIngredient) {}
    private record SmeltRecipe(NamespacedKey key, Set<String> inputRefs, String resultRef, int resultAmount,
                               int cookTime, float experience, InventoryType station, boolean hasCustomIngredient) {}

    private final CropsPlugin plugin;
    private final ModelWorld world;
    private final ConfigurationSection config;
    private final Map<String, Pie> pies = new LinkedHashMap<>();
    private final Set<NamespacedKey> recipes = new HashSet<>();
    private final Set<NamespacedKey> fallbackRecipes = new HashSet<>();
    private final List<CraftRecipe> craftRecipes = new ArrayList<>();
    private final List<SmeltRecipe> smeltRecipes = new ArrayList<>();
    private final Set<String> customCraftIngredientRefs = new HashSet<>();
    private final Set<Material> fallbackSmeltCarriers = EnumSet.noneOf(Material.class);
    private final Map<String, Material> stoves = new LinkedHashMap<>();
    private final Map<UUID, Long> coolingDrops = new java.util.concurrent.ConcurrentHashMap<>();
    private final NamespacedKey dropCoolingDueKey;
    private final LongSupplier clock;
    private final Function<UUID, Entity> entityLookup;
    private final String standardCrust;
    private final String standardModel;
    private final List<Step> steps;
    private final long cooling;
    private final String bag;
    private final String flour;
    private final int capacity;
    private final boolean hologram;
    private final double hologramHeight;
    private final long hologramCycle;
    private final List<String> stepLines;
    private final List<String> doneLines;
    private final Map<String, String> names = new java.util.concurrent.ConcurrentHashMap<>();

    Kitchen(CropsPlugin plugin, ModelWorld world, ConfigurationSection config) {
        this(plugin, world, config, System::currentTimeMillis, Bukkit::getEntity);
    }

    Kitchen(CropsPlugin plugin, ModelWorld world, ConfigurationSection config,
            LongSupplier clock, Function<UUID, Entity> entityLookup) {
        this.plugin = plugin;
        this.world = world;
        this.config = config;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.entityLookup = Objects.requireNonNull(entityLookup, "entityLookup");
        this.dropCoolingDueKey = new NamespacedKey(plugin, "pie_cooling_due");
        standardCrust = config.getString("pies.crust.item", "");
        standardModel = config.getString("pies.crust.model", "");
        steps = readSteps(config.getMapList("pies.steps"));
        cooling = Catalog.duration(config.getString("pies.cooling.time", "10m"));
        bag = config.getString("bags.flour_bag.item", "");
        flour = config.getString("bags.flour_bag.stores", "");
        capacity = Math.max(1, config.getInt("bags.flour_bag.capacity", 256));
        hologram = config.getBoolean("pies.hologram.enabled", true);
        hologramHeight = config.getDouble("pies.hologram.height", 1.35);
        hologramCycle = Math.max(50, Catalog.duration(config.getString("pies.hologram.icon-cycle", "2s")));
        stepLines = lines("pies.hologram.step", List.of("#ICON: <required-item>", "&aAdd &7<amount>x &6<item>"));
        doneLines = lines("pies.hologram.done", List.of("&6Done!", "&7Pick up the pie and bake it!"));
        var stations = config.getConfigurationSection("furnaces.stations");
        if (stations != null && config.getBoolean("enabled", true) && config.getBoolean("furnaces.enabled", true)) {
            for (String id : stations.getKeys(false)) {
                Material carrier = Material.matchMaterial(stations.getString(id + ".type", "FURNACE"));
                if (carrier == Material.FURNACE || carrier == Material.SMOKER || carrier == Material.BLAST_FURNACE) {
                    stoves.put("allium:" + id, carrier);
                }
            }
        }
        var section = config.getConfigurationSection("pies.types");
        if (section != null && config.getBoolean("enabled", true) && config.getBoolean("pies.enabled", true)) {
            for (String id : section.getKeys(false)) {
                var row = section.getConfigurationSection(id);
                String cold = config.getString("pies.models.item").replace("{state}", "cold").replace("{type}", id);
                String baked = config.getString("pies.models.item").replace("{state}", "baked").replace("{type}", id);
                String filled = config.getString("pies.models.filled").replace("{type}", id);
                pies.put(id, new Pie(id, refs(row.get("filling")), row.getString("filling-name"), row.getString("crust-item", standardCrust),
                        row.getString("crust-model", standardModel), row.getString("cold-item", cold), row.getString("baked-item", baked),
                        row.getString("filled-model", filled), row.getStringList("progress-models"),
                        models(row, id, "cold"), models(row, id, "baked"), readSteps(row.getMapList("steps")),
                        Set.copyOf(row.getIntegerList("skip-steps")), row.getBoolean("opens-on-standard-crust")));
            }
        }
    }

    int pieCount() { return pies.size(); }
    List<String> pieIds() { return List.copyOf(pies.keySet()); }

    private List<String> models(ConfigurationSection row, String id, String state) {
        List<String> specific = row.getStringList(state + "-models");
        if (!specific.isEmpty()) return specific;
        String base = config.getString("pies.models.placed").replace("{state}", state).replace("{type}", id);
        return List.of("", "_slice1", "_slice2", "_slice3").stream().map(slice -> base.replace("{slice}", slice)).toList();
    }

    private static Set<String> refs(Object value) {
        if (value instanceof Collection<?> list) {
            Set<String> refs = new LinkedHashSet<>();
            for (Object item : list) refs.add(String.valueOf(item));
            return refs;
        }
        return value instanceof String s ? Set.of(s) : Set.of();
    }

    private static List<Step> readSteps(List<Map<?, ?>> rows) {
        return rows.stream().map(row -> new Step(refs(row.get("item")), Integer.parseInt(Catalog.string(row, "amount", "1")),
                Boolean.parseBoolean(Catalog.string(row, "filling", "false")), Catalog.string(row, "name", null))).toList();
    }

    private List<String> lines(String path, List<String> fallback) {
        if (config.isString(path)) return List.of(config.getString(path));
        return config.isList(path) ? config.getStringList(path) : fallback;
    }

    void registerRecipes(ConfigurationSection external) {
        if (!config.getBoolean("enabled", true)) return;
        for (String id : external.getKeys(false)) register(id, external.getConfigurationSection(id));
        for (String section : List.of("crafting", "smelting")) {
            var definitions = config.getConfigurationSection(section);
            if (definitions != null) for (String id : definitions.getKeys(false)) register(id, definitions.getConfigurationSection(id));
        }
        for (Pie pie : pies.values()) {
            if (recipes.contains(new NamespacedKey(plugin, "bake_" + pie.id() + "_pie"))
                    || recipes.contains(new NamespacedKey(plugin, "bake_" + pie.id()))) continue;
            var output = plugin.item(pie.bakedItem(), 1);
            NamespacedKey key = new NamespacedKey(plugin, "bake_" + pie.id() + "_pie");
            Set<String> inputRefs = Set.of(pie.coldItem());
            smeltRecipes.add(new SmeltRecipe(key, inputRefs, pie.bakedItem(), 1, 200, 1f,
                    InventoryType.FURNACE, hasCustomRef(inputRefs)));
            add(key, new FurnaceRecipe(key, output, choice(inputRefs), 1, 200));
        }
        registerIdentityFallbackRecipes();
        registerCrafterGuardIfAvailable();
    }

    private void register(String id, ConfigurationSection definition) {
        var key = new NamespacedKey(plugin, id);
        String resultRef = definition.getString("result.item");
        int resultAmount = definition.getInt("result.amount", 1);
        ItemStack output = plugin.item(resultRef, resultAmount);
        if (output == null) throw new IllegalArgumentException("Missing result for " + id);
        if (definition.isString("ingredient")) {
            Set<String> inputRefs = refs(definition.get("ingredient"));
            int time = definition.getInt("time", 200);
            float exp = (float) definition.getDouble("experience", 0);
            InventoryType station = switch (definition.getString("type", "furnace").toLowerCase(Locale.ROOT)) {
                case "smoker" -> InventoryType.SMOKER;
                case "blast_furnace", "blasting" -> InventoryType.BLAST_FURNACE;
                default -> InventoryType.FURNACE;
            };
            smeltRecipes.add(new SmeltRecipe(key, inputRefs, resultRef, resultAmount, time, exp, station, hasCustomRef(inputRefs)));
            Recipe cooking = switch (station) {
                case SMOKER -> new SmokingRecipe(key, output, choice(inputRefs), exp, time);
                case BLAST_FURNACE -> new BlastingRecipe(key, output, choice(inputRefs), exp, time);
                default -> new FurnaceRecipe(key, output, choice(inputRefs), exp, time);
            };
            add(key, cooking);
        } else if (definition.contains("shape")) {
            List<String> shape = trimShape(definition.getStringList("shape"));
            var recipe = new ShapedRecipe(key, output);
            recipe.shape(shape.toArray(String[]::new));
            var keys = definition.getConfigurationSection("keys");
            Map<Character, Set<String>> keyRefs = new LinkedHashMap<>();
            boolean custom = false;
            for (String symbol : keys.getKeys(false)) {
                char ch = symbol.charAt(0);
                Set<String> allowed = refs(keys.get(symbol));
                keyRefs.put(ch, allowed);
                if (hasCustomRef(allowed)) {
                    custom = true;
                    addCustomRefs(allowed);
                }
                recipe.setIngredient(ch, choice(allowed));
            }
            craftRecipes.add(new CraftRecipe(key, resultRef, resultAmount, true, shape, keyRefs, List.of(), custom));
            add(key, recipe);
        } else {
            var recipe = new ShapelessRecipe(key, output);
            List<Set<String>> slots = new ArrayList<>();
            boolean custom = false;
            for (Object ingredient : definition.getList("ingredients", List.of())) {
                Set<String> allowed = refs(ingredient);
                slots.add(allowed);
                if (hasCustomRef(allowed)) {
                    custom = true;
                    addCustomRefs(allowed);
                }
                recipe.addIngredient(choice(allowed));
            }
            craftRecipes.add(new CraftRecipe(key, resultRef, resultAmount, false, List.of(), Map.of(), slots, custom));
            add(key, recipe);
        }
    }

    private void addCustomRefs(Set<String> refs) {
        for (String ref : refs) {
            if (isCustomRef(ref)) customCraftIngredientRefs.add(ref);
        }
    }

    private static boolean hasCustomRef(Set<String> refs) {
        for (String ref : refs) {
            if (isCustomRef(ref)) return true;
        }
        return false;
    }

    private static boolean isCustomRef(String ref) {
        return ref != null && !ref.isEmpty() && !ref.startsWith("minecraft:");
    }

    private RecipeChoice choice(Object ingredient) {
        return choice(refs(ingredient));
    }

    private RecipeChoice choice(Set<String> allowedRefs) {
        if (!hasCustomRef(allowedRefs)) {
            List<Material> materials = carrierMaterials(allowedRefs);
            if (!materials.isEmpty()) return new RecipeChoice.MaterialChoice(materials);
        }
        List<ItemStack> items = allowedRefs.stream().map(ref -> Objects.requireNonNull(plugin.item(ref, 1), ref)).toList();
        return new RecipeChoice.ExactChoice(items);
    }

    private List<Material> carrierMaterials(Set<String> allowedRefs) {
        EnumSet<Material> materials = EnumSet.noneOf(Material.class);
        for (String ref : allowedRefs) {
            ItemStack stack = Objects.requireNonNull(plugin.item(ref, 1), ref);
            materials.add(stack.getType());
        }
        return List.copyOf(materials);
    }

    private void registerIdentityFallbackRecipes() {
        record ShapedGroup(List<String> shape, Map<Character, List<Material>> materials, Set<String> customRefs) {}
        record ShapelessGroup(List<List<Material>> slots, Set<String> customRefs) {}

        Map<String, ShapedGroup> shapedGroups = new LinkedHashMap<>();
        Map<String, ShapelessGroup> shapelessGroups = new LinkedHashMap<>();
        for (CraftRecipe craft : craftRecipes) {
            if (!craft.hasCustomIngredient()) continue;
            if (craft.shaped()) {
                Map<Character, List<Material>> slotMaterials = new LinkedHashMap<>();
                Set<String> customRefs = new LinkedHashSet<>();
                StringBuilder signature = new StringBuilder();
                for (String row : craft.shape()) {
                    signature.append(row).append('|');
                }
                for (Map.Entry<Character, Set<String>> entry : craft.keyRefs().entrySet()) {
                    List<Material> mats = carrierMaterials(entry.getValue());
                    slotMaterials.put(entry.getKey(), mats);
                    signature.append(entry.getKey()).append('=').append(mats).append(';');
                    for (String ref : entry.getValue()) if (isCustomRef(ref)) customRefs.add(ref);
                }
                ShapedGroup group = shapedGroups.computeIfAbsent(signature.toString(),
                        k -> new ShapedGroup(craft.shape(), slotMaterials, new LinkedHashSet<>()));
                group.customRefs().addAll(customRefs);
            } else {
                List<List<Material>> slotMaterials = new ArrayList<>();
                Set<String> customRefs = new LinkedHashSet<>();
                for (Set<String> slot : craft.shapelessRefs()) {
                    slotMaterials.add(carrierMaterials(slot));
                    for (String ref : slot) if (isCustomRef(ref)) customRefs.add(ref);
                }
                slotMaterials.sort(Comparator.comparing(Object::toString));
                String signature = slotMaterials.toString();
                ShapelessGroup group = shapelessGroups.computeIfAbsent(signature,
                        k -> new ShapelessGroup(slotMaterials, new LinkedHashSet<>()));
                group.customRefs().addAll(customRefs);
            }
        }

        int fallbackIndex = 1;
        for (ShapedGroup group : shapedGroups.values()) {
            NamespacedKey key = new NamespacedKey(plugin, "identity_craft_" + fallbackIndex++);
            ShapedRecipe fallback = new ShapedRecipe(key, fallbackPlaceholder());
            fallback.shape(group.shape().toArray(String[]::new));
            for (Map.Entry<Character, List<Material>> entry : group.materials().entrySet()) {
                fallback.setIngredient(entry.getKey(), new RecipeChoice.MaterialChoice(entry.getValue()));
            }
            addFallback(key, fallback, group.customRefs());
        }
        for (ShapelessGroup group : shapelessGroups.values()) {
            NamespacedKey key = new NamespacedKey(plugin, "identity_craft_" + fallbackIndex++);
            ShapelessRecipe fallback = new ShapelessRecipe(key, fallbackPlaceholder());
            for (List<Material> mats : group.slots()) {
                fallback.addIngredient(new RecipeChoice.MaterialChoice(mats));
            }
            addFallback(key, fallback, group.customRefs());
        }

        Map<InventoryType, EnumSet<Material>> smeltCarriersByStation = new EnumMap<>(InventoryType.class);
        for (SmeltRecipe smelt : smeltRecipes) {
            if (!smelt.hasCustomIngredient()) continue;
            smeltCarriersByStation.computeIfAbsent(smelt.station(), k -> EnumSet.noneOf(Material.class))
                    .addAll(carrierMaterials(smelt.inputRefs()));
        }
        for (Map.Entry<InventoryType, EnumSet<Material>> entry : smeltCarriersByStation.entrySet()) {
            InventoryType station = entry.getKey();
            for (Material carrier : entry.getValue()) {
                if (hasVanillaCookingRecipe(carrier, station)) continue;
                fallbackSmeltCarriers.add(carrier);
                NamespacedKey key = new NamespacedKey(plugin, "identity_smelt_"
                        + station.name().toLowerCase(Locale.ROOT) + "_" + carrier.name().toLowerCase(Locale.ROOT));
                RecipeChoice carrierChoice = new RecipeChoice.MaterialChoice(carrier);
                Recipe fallback = switch (station) {
                    case SMOKER -> new SmokingRecipe(key, fallbackPlaceholder(), carrierChoice, 0f, 200);
                    case BLAST_FURNACE -> new BlastingRecipe(key, fallbackPlaceholder(), carrierChoice, 0f, 200);
                    default -> new FurnaceRecipe(key, fallbackPlaceholder(), carrierChoice, 0f, 200);
                };
                addFallback(key, fallback, List.of());
            }
        }
    }

    private boolean hasVanillaCookingRecipe(Material carrier, InventoryType station) {
        Server server = plugin.getServer();
        if (server == null) return false;
        Iterator<Recipe> it = server.recipeIterator();
        if (it == null) return false;
        while (it.hasNext()) {
            Recipe recipe = it.next();
            if (!(recipe instanceof CookingRecipe<?> cooking) || !(recipe instanceof Keyed keyed)) continue;
            if (!"minecraft".equals(keyed.getKey().getNamespace())) continue;
            boolean matchesStation = switch (station) {
                case FURNACE -> recipe instanceof FurnaceRecipe;
                case BLAST_FURNACE -> recipe instanceof BlastingRecipe;
                case SMOKER -> recipe instanceof SmokingRecipe;
                default -> false;
            };
            if (!matchesStation) continue;
            RecipeChoice input = cooking.getInputChoice();
            if (input instanceof RecipeChoice.MaterialChoice mats && mats.getChoices().contains(carrier)) {
                return true;
            }
        }
        return false;
    }

    private ItemStack fallbackPlaceholder() {
        return new ItemStack(Material.BARRIER);
    }

    private void add(NamespacedKey key, Recipe recipe) {
        plugin.connection().items().registerRecipe(recipe);
        recipes.add(key);
    }

    private void addFallback(NamespacedKey key, Recipe recipe, Collection<String> allowedCustomRefs) {
        plugin.connection().items().registerRecipe(recipe, allowedCustomRefs);
        recipes.add(key);
        fallbackRecipes.add(key);
    }

    private void registerCrafterGuardIfAvailable() {
        Server server = plugin.getServer();
        if (server == null || server.getPluginManager() == null) return;
        try {
            Class<?> crafterClass = Class.forName("org.bukkit.event.block.CrafterCraftEvent");
            if (Event.class.isAssignableFrom(crafterClass)) {
                @SuppressWarnings("unchecked")
                Class<? extends Event> eventClass = (Class<? extends Event>) crafterClass;
                server.getPluginManager().registerEvent(
                        eventClass,
                        this,
                        EventPriority.LOW,
                        (listener, event) -> cancelFallbackCrafter(event),
                        plugin
                );
            }
        } catch (ClassNotFoundException | LinkageError ignored) {
            // Pre-1.21 server without CrafterCraftEvent
        }
    }

    private void cancelFallbackCrafter(Event event) {
        if (!(event instanceof org.bukkit.event.Cancellable cancellable)) return;
        try {
            Object recipe = event.getClass().getMethod("getRecipe").invoke(event);
            if (recipe instanceof Keyed keyed && fallbackRecipes.contains(keyed.getKey())) {
                cancellable.setCancelled(true);
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private boolean isKitchenRecipe(@Nullable Recipe recipe) {
        return recipe instanceof Keyed keyed && recipes.contains(keyed.getKey());
    }

    private boolean isFallbackRecipe(@Nullable Recipe recipe) {
        return recipe instanceof Keyed keyed && fallbackRecipes.contains(keyed.getKey());
    }

    private String verifiedReference(@Nullable ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return "";
        var identity = plugin.connection().items().identify(stack).orElse(null);
        if (identity == null) return "";
        if (identity.behavior().isEmpty() && hasAlliumClaimTags(stack)) {
            return TAMPERED_REF;
        }
        if (identity.appearance().startsWith("allium:") && identity.behavior().isEmpty()) {
            return TAMPERED_REF;
        }
        return identity.appearance();
    }

    private static boolean hasAlliumClaimTags(ItemStack stack) {
        if (!stack.hasItemMeta()) return false;
        var meta = stack.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc != null && (pdc.has(ALLIUM_BEHAVIOR_KEY, PersistentDataType.STRING)
                || pdc.has(ALLIUM_SIGNATURE_KEY, PersistentDataType.STRING));
    }

    private boolean matrixHasKitchenCustomItem(@Nullable ItemStack[] matrix) {
        if (matrix == null) return false;
        for (ItemStack item : matrix) {
            String ref = verifiedReference(item);
            if (customCraftIngredientRefs.contains(ref)) return true;
        }
        return false;
    }

    CraftRecipe matchCrafting(@Nullable ItemStack[] matrix) {
        if (matrix == null || matrix.length == 0) return null;
        int gridSide = matrix.length <= 4 ? 2 : 3;
        String[] refs = new String[matrix.length];
        List<String> present = new ArrayList<>();
        for (int i = 0; i < matrix.length; i++) {
            String ref = verifiedReference(matrix[i]);
            if (TAMPERED_REF.equals(ref)) return null;
            refs[i] = ref;
            if (!ref.isEmpty()) present.add(ref);
        }
        if (present.isEmpty()) return null;

        for (CraftRecipe recipe : craftRecipes) {
            if (recipe.shaped()) {
                if (matchesShaped(refs, gridSide, recipe)) return recipe;
            } else {
                if (matchesShapeless(present, recipe.shapelessRefs())) return recipe;
            }
        }
        return null;
    }

    private static boolean matchesShaped(String[] grid, int side, CraftRecipe recipe) {
        List<String> rows = recipe.shape();
        int height = rows.size();
        int width = rows.stream().mapToInt(String::length).max().orElse(0);
        if (height == 0 || width == 0 || height > side || width > side) return false;
        for (int rowOffset = 0; rowOffset <= side - height; rowOffset++) {
            for (int colOffset = 0; colOffset <= side - width; colOffset++) {
                if (matchesShapedAt(grid, side, rows, recipe.keyRefs(), rowOffset, colOffset, false)
                        || matchesShapedAt(grid, side, rows, recipe.keyRefs(), rowOffset, colOffset, true)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesShapedAt(String[] grid, int side, List<String> rows,
                                           Map<Character, Set<String>> keyRefs,
                                           int rowOffset, int colOffset, boolean mirrored) {
        int height = rows.size();
        int width = rows.stream().mapToInt(String::length).max().orElse(0);
        for (int r = 0; r < side; r++) {
            for (int c = 0; c < side; c++) {
                int index = r * side + c;
                String actual = index < grid.length ? grid[index] : "";
                int sr = r - rowOffset;
                int sc = c - colOffset;
                if (sr < 0 || sr >= height || sc < 0 || sc >= width) {
                    if (!actual.isEmpty()) return false;
                    continue;
                }
                int sampleCol = mirrored ? (width - 1 - sc) : sc;
                String row = rows.get(sr);
                char symbol = sampleCol < row.length() ? row.charAt(sampleCol) : ' ';
                if (symbol == ' ') {
                    if (!actual.isEmpty()) return false;
                } else {
                    Set<String> allowed = keyRefs.get(symbol);
                    if (allowed == null || !allowed.contains(actual)) return false;
                }
            }
        }
        return true;
    }

    private static boolean matchesShapeless(List<String> present, List<Set<String>> slots) {
        if (present.size() != slots.size()) return false;
        return matchSlot(present, slots, 0, new boolean[present.size()]);
    }

    private static boolean matchSlot(List<String> present, List<Set<String>> slots, int slotIndex, boolean[] used) {
        if (slotIndex == slots.size()) return true;
        Set<String> allowed = slots.get(slotIndex);
        for (int i = 0; i < present.size(); i++) {
            if (!used[i] && allowed.contains(present.get(i))) {
                used[i] = true;
                if (matchSlot(present, slots, slotIndex + 1, used)) return true;
                used[i] = false;
            }
        }
        return false;
    }

    private static List<String> trimShape(List<String> raw) {
        int minRow = raw.size(), maxRow = -1, minCol = Integer.MAX_VALUE, maxCol = -1;
        for (int r = 0; r < raw.size(); r++) {
            String row = raw.get(r);
            for (int c = 0; c < row.length(); c++) {
                if (row.charAt(c) != ' ') {
                    minRow = Math.min(minRow, r);
                    maxRow = Math.max(maxRow, r);
                    minCol = Math.min(minCol, c);
                    maxCol = Math.max(maxCol, c);
                }
            }
        }
        if (maxRow < minRow) return List.of();
        List<String> trimmed = new ArrayList<>();
        for (int r = minRow; r <= maxRow; r++) {
            String row = raw.get(r);
            StringBuilder line = new StringBuilder();
            for (int c = minCol; c <= maxCol; c++) {
                line.append(c < row.length() ? row.charAt(c) : ' ');
            }
            trimmed.add(line.toString());
        }
        return trimmed;
    }

    SmeltRecipe matchSmelting(@Nullable ItemStack source, InventoryType station) {
        String ref = verifiedReference(source);
        if (ref.isEmpty() || TAMPERED_REF.equals(ref)) return null;
        for (SmeltRecipe recipe : smeltRecipes) {
            if (recipe.station() == station && recipe.inputRefs().contains(ref)) {
                return recipe;
            }
        }
        return null;
    }

    private static InventoryType stationForBlock(Material type) {
        return switch (type) {
            case BLAST_FURNACE -> InventoryType.BLAST_FURNACE;
            case SMOKER -> InventoryType.SMOKER;
            default -> InventoryType.FURNACE;
        };
    }

    @EventHandler(priority = EventPriority.LOW)
    public void prepareCraft(PrepareItemCraftEvent event) {
        if (!config.getBoolean("enabled", true)) return;
        Recipe recipe = event.getRecipe();
        ItemStack[] matrix = event.getInventory().getMatrix();
        boolean kitchenRecipe = isKitchenRecipe(recipe);
        if (!kitchenRecipe && !matrixHasKitchenCustomItem(matrix)) return;
        CraftRecipe matched = matchCrafting(matrix);
        if (matched != null) {
            event.getInventory().setResult(plugin.item(matched.resultRef(), matched.resultAmount()));
        } else if (kitchenRecipe) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void craftItem(CraftItemEvent event) {
        if (!config.getBoolean("enabled", true)) return;
        Recipe recipe = event.getRecipe();
        ItemStack[] matrix = event.getInventory().getMatrix();
        boolean kitchenRecipe = isKitchenRecipe(recipe);
        if (!kitchenRecipe && !matrixHasKitchenCustomItem(matrix)) return;
        CraftRecipe matched = matchCrafting(matrix);
        if (matched == null) {
            if (kitchenRecipe) {
                event.setCancelled(true);
                event.getInventory().setResult(null);
            }
            return;
        }
        ItemStack currentResult = event.getInventory().getResult();
        if (currentResult == null || isFallbackRecipe(recipe)
                || !matched.resultRef().equals(verifiedReference(currentResult))
                || currentResult.getAmount() != matched.resultAmount()) {
            ItemStack expected = plugin.item(matched.resultRef(), matched.resultAmount());
            if (expected == null) {
                event.setCancelled(true);
                return;
            }
            event.getInventory().setResult(expected);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void furnaceBurn(FurnaceBurnEvent event) {
        if (!config.getBoolean("enabled", true)) return;
        if (!(event.getBlock().getState() instanceof org.bukkit.block.Furnace furnace)) return;
        ItemStack smelting = furnace.getInventory().getSmelting();
        if (smelting == null || smelting.getType().isAir()) return;
        if (!fallbackSmeltCarriers.contains(smelting.getType())) return;
        if (matchSmelting(smelting, stationForBlock(event.getBlock().getType())) == null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void furnaceStartSmelt(FurnaceStartSmeltEvent event) {
        if (!config.getBoolean("enabled", true)) return;
        SmeltRecipe matched = matchSmelting(event.getSource(), stationForBlock(event.getBlock().getType()));
        if (matched != null) {
            event.setTotalCookTime(matched.cookTime());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void furnaceSmelt(FurnaceSmeltEvent event) {
        if (!config.getBoolean("enabled", true)) return;
        InventoryType station = stationForBlock(event.getBlock().getType());
        SmeltRecipe matched = matchSmelting(event.getSource(), station);
        if (matched != null) {
            ItemStack out = plugin.item(matched.resultRef(), matched.resultAmount());
            if (out != null) {
                event.setResult(out);
            } else {
                event.setCancelled(true);
            }
            return;
        }
        ItemStack source = event.getSource();
        if (source != null && fallbackSmeltCarriers.contains(source.getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void rightClick(PlayerInteractEvent event) {
        if (!config.getBoolean("enabled", true) || event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) return;
        ItemStack held = event.getItem();
        String item = plugin.reference(held);
        Player player = event.getPlayer();
        Block target = event.getClickedBlock();
        if (item.equals(bag) && !bag.isEmpty() && (target == null || !target.getType().isInteractable())) {
            event.setCancelled(true);
            if (player.hasPermission("crops.use") && plugin.connection().canEditInventory(player)) withdraw(player, held);
            return;
        }
        if (event.isCancelled() || target == null || !player.hasPermission("crops.use")
                || !plugin.connection().canEditInventory(player)) return;
        if (knead(event, target, item)) return;
        if (event.getBlockFace() != BlockFace.UP || target.getType().isInteractable() && !player.isSneaking()) return;
        Material stove = stoves.get(item);
        if (stove != null) {
            placeStove(event, target, item, stove);
            return;
        }
        for (Pie pie : pies.values()) {
            if (item.equals(pie.bakedItem()) || item.equals(pie.coldItem())) {
                place(event, target, pie.id(), item.equals(pie.bakedItem()) ? "baked" : "cold");
                return;
            }
            if (!pie.crust().equals(standardCrust) && item.equals(pie.crust())) {
                place(event, target, pie.id(), "assembly");
                return;
            }
        }
        if (!pies.isEmpty() && item.equals(standardCrust)) place(event, target, "plain", "assembly");
    }

    private boolean knead(PlayerInteractEvent event, Block block, String held) {
        String ingredient = config.getString("kneading.flour.item");
        boolean fromBag = held.equals(bag) && !bag.isEmpty() && flour.equals(ingredient);
        if (!config.getBoolean("kneading.enabled", true) || block.getType() != Material.WATER_CAULDRON
                || !held.equals(ingredient) && !fromBag) return false;
        event.setCancelled(true);
        var player = event.getPlayer();
        int water = Math.max(1, config.getInt("kneading.water.required", 3));
        int required = Math.max(1, config.getInt("kneading.flour.required", 3));
        var hold = event.getItem();
        if (!(block.getBlockData() instanceof Levelled cauldron) || cauldron.getLevel() < water
                || !plugin.connection().mayModify(player, block, "knead", event)) return true;
        var data = block.getChunk().getPersistentDataContainer();
        var key = BlockKeys.at(plugin, "kneading", block);
        int prior = Math.max(0, Math.min(required - 1, data.getOrDefault(key, PersistentDataType.INTEGER, 0)));
        int use = fromBag ? 1 : Math.min(hold.getAmount(), required - prior);
        boolean complete = prior + use >= required;
        ItemStack dough = complete
                ? plugin.item(config.getString("kneading.result.item"), config.getInt("kneading.result.amount", 1))
                : null;
        if (complete && dough == null) return true;
        if (fromBag) {
            int stored = stored(hold);
            if (hold.getAmount() != 1 || stored == 0 || !writeBag(hold, stored - 1)) {
                player.sendMessage("§eThis flour bag is empty or stacked.");
                return true;
            }
            player.getInventory().setItemInMainHand(hold);
        } else CropsPlugin.spend(player, hold, use);
        if (!complete) {
            data.set(key, PersistentDataType.INTEGER, prior + use);
            player.sendMessage("§7Flour: §f" + (prior + use) + "/" + required);
            return true;
        }
        data.remove(key);
        int left = cauldron.getLevel() - water;
        if (left <= 0) block.setType(Material.CAULDRON);
        else {
            cauldron.setLevel(left);
            block.setBlockData(cauldron);
        }
        plugin.give(player, dough);
        block.getWorld().playSound(block.getLocation(), config.getString("kneading.sound", "block.mud.place"), 1, 1);
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breakKneadingStation(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.WATER_CAULDRON || block.getType() == Material.CAULDRON) {
            block.getChunk().getPersistentDataContainer().remove(BlockKeys.at(plugin, "kneading", block));
        }
    }

    private void place(PlayerInteractEvent event, Block support, String type, String state) {
        event.setCancelled(true);
        Block cell = support.getRelative(BlockFace.UP);
        if (!support.getType().isSolid() || !plugin.connection().mayModify(event.getPlayer(), cell, "place_pie", event)) return;
        long deadline = state.equals("baked") ? System.currentTimeMillis() + cooling : 0;
        ModelWorld.Placed pie = world.place(cell, "pie", type, state, 0, deadline, visual(type, state, 0));
        if (pie != null) CropsPlugin.spend(event.getPlayer(), event.getItem(), 1);
    }

    private void placeStove(PlayerInteractEvent event, Block support, String item, Material furnace) {
        event.setCancelled(true);
        Block cell = support.getRelative(BlockFace.UP);
        Player player = event.getPlayer();
        if (!support.getType().isSolid() || !plugin.connection().mayModify(player, cell, "place_stove", event)) return;
        var placed = world.place(cell, "stove", item, "", 0, 0, stoveVisual(item));
        if (placed == null) return;
        cell.setType(furnace);
        if (cell.getBlockData() instanceof org.bukkit.block.data.Directional direction) {
            direction.setFacing(player.getFacing().getOppositeFace());
            cell.setBlockData(direction);
        }
        CropsPlugin.spend(player, event.getItem(), 1);
    }

    private static Catalog.Visual stoveVisual(String id) {
        return new Catalog.Visual(id, ItemDisplayTransform.NONE, 1, .5, false);
    }

    void restore(ModelWorld.Placed placed) {
        if (placed.kind.equals("stove")) {
            if (stoves.get(placed.type) == placed.cell().getType()) world.show(placed, stoveVisual(placed.type));
            return;
        }
        if (placed.type.equals("plain") || pies.containsKey(placed.type)) {
            world.show(placed, visual(placed.type, placed.path, placed.stage));
            world.label(placed, frames(placed), hologramHeight, hologramCycle);
        }
    }

    /**
     * The step hologram, as legacy drew it: while a pie is assembled, what to add next, cycling
     * through every item the step takes (every filling, before the pie has one); once it is
     * finished, the done lines until it is picked up.
     */
    private List<ModelWorld.Frame> frames(ModelWorld.Placed placed) {
        if (!hologram) return List.of();
        Pie pie = pies.get(placed.type);
        if (placed.path.equals("ready")) {
            return List.of(frame(doneLines, pie == null ? null : pie.coldItem(), null, 1));
        }
        if (!placed.path.equals("assembly")) return List.of();
        List<Step> sequence = pie != null && !pie.steps().isEmpty() ? pie.steps() : steps;
        if (placed.stage >= sequence.size()) return List.of();
        Step step = sequence.get(placed.stage);
        List<ModelWorld.Frame> frames = new ArrayList<>();
        if (step.filling()) {
            // As assemble(): any pie made with the shared steps takes its filling here. One frame per
            // pie, showing its first filling, as legacy cycled through the flavours.
            for (Pie option : pies.values()) {
                if (!option.steps().isEmpty() || option.fillings().isEmpty()) continue;
                String filling = option.fillings().iterator().next();
                frames.add(frame(stepLines, filling, step.name() != null ? step.name() : option.fillingName(), step.amount()));
            }
        } else {
            for (String item : step.items()) frames.add(frame(stepLines, item, step.name(), step.amount()));
        }
        if (pie == null && placed.stage == 0) {
            // A plain crust can also open a pie with its own steps (deep-dish pizza).
            for (Pie option : pies.values()) {
                if (!option.standard() || option.steps().isEmpty()) continue;
                Step first = option.steps().get(0);
                for (String item : first.items()) frames.add(frame(stepLines, item, first.name(), first.amount()));
            }
        }
        return frames;
    }

    private ModelWorld.Frame frame(List<String> template, String item, String name, int amount) {
        List<String> above = new ArrayList<>(), below = new ArrayList<>();
        String icon = null;
        String label = name != null ? name : item == null ? "" : name(item);
        for (String line : template) {
            String text = line.replace("<required-item>", item == null ? "" : item)
                    .replace("<item>", label).replace("<amount>", Integer.toString(amount));
            if (text.regionMatches(true, 0, "#ICON:", 0, 6)) {
                if (icon == null) icon = text.substring(6).trim();
                continue;
            }
            (icon == null ? above : below).add(plugin.connection().text().legacy(text));
        }
        return new ModelWorld.Frame(List.copyOf(above), icon == null || icon.isEmpty() ? null : icon, List.copyOf(below));
    }

    /** An item's plain name for the hologram: its display name, or the vanilla item's name. */
    private String name(String reference) {
        return names.computeIfAbsent(reference, ref -> {
            ItemStack stack = plugin.item(ref, 1);
            if (stack != null && stack.getItemMeta() != null && stack.getItemMeta().hasDisplayName()) {
                return ChatColor.stripColor(stack.getItemMeta().getDisplayName());
            }
            String id = CropsPlugin.stripNamespace(ref).replace('_', ' ');
            StringBuilder out = new StringBuilder();
            for (String word : id.split(" ")) {
                if (word.isEmpty()) continue;
                if (!out.isEmpty()) out.append(' ');
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            return out.toString();
        });
    }

    private Catalog.Visual visual(String type, String state, int stage) {
        Pie pie = pies.get(type);
        String item = standardModel;
        if (pie != null) {
            if (state.equals("assembly")) {
                item = stage == 0 ? pie.crustModel() : stage <= pie.progress().size()
                        ? pie.progress().get(stage - 1) : pie.filled();
            } else {
                item = (state.equals("baked") ? pie.baked() : pie.cold()).get(Math.max(0, Math.min(3, stage)));
            }
        }
        // Pie models carry no display transform, so they are drawn centred on the display, as in
        // legacy (NONE at the cell centre): the model's floor sits half a block below the centre,
        // which the .5 offset puts on the soil. GROUND with a .001 offset sank it into the soil.
        return new Catalog.Visual(item, ItemDisplayTransform.NONE, 1, .501, true);
    }

    void tick(ModelWorld.Placed placed) {
        if (placed.kind.equals("stove")) {
            if (stoves.get(placed.type) != placed.cell().getType()) world.remove(placed);
            else restore(placed);
            return;
        }
        var attack = placed.hitbox.getLastAttack();
        if (attack != null && attack.getTimestamp() > placed.lastAttack) {
            placed.lastAttack = attack.getTimestamp();
            Player player = attack.getPlayer().getPlayer();
            if (player != null && (!plugin.connection().isFolia() || plugin.connection().ownsEntity(player))
                    && canUse(player, placed, null)) take(player, placed);
        }
        if (!placed.hitbox.isValid()) return;
        if (!placed.cell().getRelative(BlockFace.DOWN).getType().isSolid()) {
            take(null, placed);
            return;
        }
        if (placed.path.equals("baked") && placed.due <= System.currentTimeMillis()) change(placed, placed.type, "cold", placed.stage, 0);
        restore(placed);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void interact(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        var placed = world.get(event.getRightClicked());
        if (placed == null || !placed.kind.equals("pie")) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!canUse(player, placed, event)) return;
        if (player.isSneaking()) {
            take(player, placed);
        } else if (placed.path.equals("assembly")) {
            assemble(player, placed);
        } else if (placed.path.equals("baked")) {
            eat(player, placed);
        } else {
            player.sendMessage("§ePick up the pie and bake it.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        var placed = world.get(event.getEntity());
        if (placed == null || !placed.kind.equals("pie")) return;
        event.setCancelled(true);
        if (canUse(player, placed, event)) take(player, placed);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectCells(BlockPlaceEvent event) {
        if (world.at(event.getBlock()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakStove(BlockBreakEvent event) {
        Block block = event.getBlock();
        var placed = world.at(block);
        if (placed == null || !placed.kind.equals("stove")) return;
        boolean drop = event.isDropItems() && event.getPlayer().getGameMode() != GameMode.CREATIVE;
        event.setDropItems(false);
        plugin.connection().runForRegion(block.getWorld(), block.getX() >> 4, block.getZ() >> 4, () -> {
            if (event.isCancelled() || stoves.get(placed.type) == block.getType()) return;
            world.remove(placed);
            if (drop) {
                ItemStack item = plugin.item(placed.type, 1);
                if (item != null) block.getWorld().dropItemNaturally(block.getLocation(), item);
            }
        }, 0L);
    }

    private boolean canUse(Player player, ModelWorld.Placed placed, org.bukkit.event.Event source) {
        return player.hasPermission("crops.use") && plugin.connection().canEditInventory(player)
                && plugin.connection().mayModify(player, placed.cell(), "use_pie", source);
    }

    private void assemble(Player player, ModelWorld.Placed placed) {
        ItemStack held = player.getInventory().getItemInMainHand();
        String input = plugin.reference(held);
        Pie pie = pies.get(placed.type);
        if (pie == null && placed.stage == 0) {
            pie = pies.values().stream().filter(candidate -> candidate.standard() && !candidate.steps().isEmpty()
                    && candidate.steps().get(0).items().contains(input)).findFirst().orElse(null);
        }
        List<Step> sequence = pie != null && !pie.steps().isEmpty() ? pie.steps() : steps;
        if (placed.stage >= sequence.size()) return;
        Step step = sequence.get(placed.stage);
        if (step.filling()) {
            pie = pies.values().stream().filter(candidate -> candidate.steps().isEmpty() && candidate.fillings().contains(input))
                    .findFirst().orElse(null);
            if (pie == null) { player.sendMessage("§eAdd a pie filling."); return; }
        } else if (!step.items().contains(input)) {
            player.sendMessage("§eAdd " + String.join(" or ", step.items()) + ".");
            return;
        }
        if (held.getAmount() < step.amount()) return;
        int next = placed.stage + 1;
        while (pie != null && pie.skipped().contains(next + 1)) next++;
        boolean finished = next >= sequence.size() && pie != null;
        String type = pie == null ? placed.type : pie.id();
        // "ready": finished here, shown as the cold pie with the done hologram until it is picked up.
        if (!change(placed, type, finished ? "ready" : "assembly", finished ? 0 : next, 0)) return;
        CropsPlugin.spend(player, held, step.amount());
        if (finished) player.sendMessage("§aThe pie is ready. Sneak-right-click to pick it up and bake it.");
    }

    private boolean change(ModelWorld.Placed placed, String type, String state, int stage, long due) {
        if (!world.show(placed, visual(type, state, stage))) return false;
        placed.type = type;
        placed.path = state;
        placed.stage = stage;
        placed.due = due;
        world.store(placed);
        world.label(placed, frames(placed), hologramHeight, hologramCycle);
        return true;
    }

    private void take(Player player, ModelWorld.Placed placed) {
        Pie pie = pies.get(placed.type);
        String item = pie == null ? standardCrust : switch (placed.path) {
            case "baked" -> pie.bakedItem();
            case "cold", "ready" -> pie.coldItem();
            default -> pie.crust();
        };
        // A part-eaten pie cannot turn back into a whole item.
        if (placed.stage == 0 || placed.path.equals("assembly")) {
            ItemStack result = plugin.item(item, 1);
            if (result == null) return;
            if (player == null) placed.hitbox.getWorld().dropItemNaturally(placed.hitbox.getLocation(), result);
            else plugin.give(player, result);
        }
        world.remove(placed);
    }

    private void eat(Player player, ModelWorld.Placed placed) {
        if (config.getBoolean("pies.eating.require-hunger", true) && player.getFoodLevel() >= 20
                && player.getGameMode() != GameMode.CREATIVE) return;
        if (placed.stage >= 3) world.remove(placed);
        else if (!change(placed, placed.type, "baked", placed.stage + 1, placed.due)) return;
        player.setFoodLevel(Math.min(20, player.getFoodLevel() + config.getInt("pies.eating.nutrition", 3)));
        player.setSaturation(Math.min(player.getFoodLevel(), player.getSaturation() + (float) config.getDouble("pies.eating.saturation", .6)));
        player.playSound(player.getLocation(), config.getString("pies.eating.sound", "entity.generic.eat"), 1, 1);
    }

    void scanLoadedDrops(Iterable<World> worlds) {
        if (!config.getBoolean("enabled", true) || !config.getBoolean("pies.cooling.dropped-items", true)) return;
        if (plugin.connection().isFolia()) {
            for (World loadedWorld : worlds) {
                for (Chunk chunk : loadedWorld.getLoadedChunks()) {
                    int cx = chunk.getX(), cz = chunk.getZ();
                    plugin.connection().onRegion(loadedWorld, cx, cz, () -> {
                        if (!loadedWorld.isChunkLoaded(cx, cz)) return;
                        for (Entity entity : loadedWorld.getChunkAt(cx, cz).getEntities()) {
                            if (entity instanceof Item drop && drop.isValid()) reconcileLoadedDrop(drop);
                        }
                    });
                }
            }
        } else {
            for (World loadedWorld : worlds) {
                for (Item drop : loadedWorld.getEntitiesByClass(Item.class)) {
                    if (drop.isValid()) reconcileLoadedDrop(drop);
                }
            }
        }
    }

    @EventHandler
    public void dropped(ItemSpawnEvent event) {
        if (!config.getBoolean("enabled", true) || !config.getBoolean("pies.cooling.dropped-items", true)) return;
        Item drop = event.getEntity();
        if (baked(drop.getItemStack()) == null) return;
        Long existingDue = drop.getPersistentDataContainer().get(dropCoolingDueKey, PersistentDataType.LONG);
        long due = existingDue != null ? existingDue : clock.getAsLong() + cooling;
        drop.getPersistentDataContainer().set(dropCoolingDueKey, PersistentDataType.LONG, due);
        scheduleOrCoolDrop(drop, due);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (!config.getBoolean("enabled", true) || !config.getBoolean("pies.cooling.dropped-items", true)) return;
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Item drop && drop.isValid()) {
                reconcileLoadedDrop(drop);
            }
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Item drop) {
                coolingDrops.remove(drop.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickupCoolingDrop(EntityPickupItemEvent event) {
        if (!config.getBoolean("enabled", true) || !config.getBoolean("pies.cooling.dropped-items", true)) return;
        Item drop = event.getItem();
        Long due = drop.getPersistentDataContainer().get(dropCoolingDueKey, PersistentDataType.LONG);
        if (due != null && due <= clock.getAsLong()) {
            coolDrop(drop);
        }
    }

    void reconcileLoadedDrop(Item drop) {
        if (drop == null || !drop.isValid()) return;
        PersistentDataContainer pdc = drop.getPersistentDataContainer();
        Long due = pdc.get(dropCoolingDueKey, PersistentDataType.LONG);
        if (baked(drop.getItemStack()) == null) {
            if (due != null) pdc.remove(dropCoolingDueKey);
            coolingDrops.remove(drop.getUniqueId());
            return;
        }
        if (due == null) {
            due = clock.getAsLong() + cooling;
            pdc.set(dropCoolingDueKey, PersistentDataType.LONG, due);
        }
        scheduleOrCoolDrop(drop, due);
    }

    private void scheduleOrCoolDrop(Item drop, long due) {
        long remaining = due - clock.getAsLong();
        if (remaining <= 0L) {
            coolDrop(drop);
            return;
        }
        if (plugin.connection().isFolia()) {
            plugin.connection().runForEntity(drop, () -> resolveDropCooling(drop), () -> {}, Math.max(50L, remaining));
        } else {
            coolingDrops.put(drop.getUniqueId(), due);
        }
    }

    private void resolveDropCooling(Item drop) {
        if (!drop.isValid()) return;
        Long due = drop.getPersistentDataContainer().get(dropCoolingDueKey, PersistentDataType.LONG);
        if (due == null) return;
        long remaining = due - clock.getAsLong();
        if (remaining <= 0L) {
            coolDrop(drop);
        } else if (plugin.connection().isFolia()) {
            plugin.connection().runForEntity(drop, () -> resolveDropCooling(drop), () -> {}, Math.max(50L, remaining));
        }
    }

    private void coolDrop(Item drop) {
        coolingDrops.remove(drop.getUniqueId());
        if (!drop.isValid()) return;
        drop.getPersistentDataContainer().remove(dropCoolingDueKey);
        Pie pie = baked(drop.getItemStack());
        if (pie != null) {
            ItemStack cold = plugin.item(pie.coldItem(), drop.getItemStack().getAmount());
            if (cold != null) drop.setItemStack(cold);
        }
    }

    private Pie baked(ItemStack item) {
        String reference = plugin.reference(item);
        return pies.values().stream().filter(pie -> pie.bakedItem().equals(reference)).findFirst().orElse(null);
    }

    int trackedCoolingDropCount() {
        return coolingDrops.size();
    }

    void coolDrops() {
        long now = clock.getAsLong();
        coolingDrops.entrySet().removeIf(entry -> {
            if (!(entityLookup.apply(entry.getKey()) instanceof Item drop) || !drop.isValid()) return true;
            Long persisted = drop.getPersistentDataContainer().get(dropCoolingDueKey, PersistentDataType.LONG);
            long due = persisted != null ? persisted : entry.getValue();
            if (due > now) return false;
            drop.getPersistentDataContainer().remove(dropCoolingDueKey);
            Pie pie = baked(drop.getItemStack());
            if (pie != null) {
                ItemStack cold = plugin.item(pie.coldItem(), drop.getItemStack().getAmount());
                if (cold != null) drop.setItemStack(cold);
            }
            return true;
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void clickBag(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || bag.isEmpty() || flour.isEmpty()
                || !config.getBoolean("enabled", true) || !plugin.reference(event.getCurrentItem()).equals(bag)
                || !player.hasPermission("crops.use") || !plugin.connection().canEditInventory(player)) return;
        ItemStack bagItem = event.getCurrentItem();
        if (bagItem.getAmount() != 1) return;
        ItemStack cursor = event.getCursor();
        boolean empty = cursor == null || cursor.getType().isAir();
        boolean matching = !empty && plugin.reference(cursor).equals(flour);
        int stored = stored(bagItem);
        switch (event.getClick()) {
            case LEFT -> {
                if (!matching) return;
                event.setCancelled(true);
                int amount = Math.min(cursor.getAmount(), capacity - stored);
                if (amount <= 0) {
                    player.sendMessage("§eThe flour bag is full.");
                    return;
                }
                ItemStack remainder = cursor.clone();
                remainder.setAmount(cursor.getAmount() - amount);
                setBagClick(event, player, bagItem, stored + amount, remainder.getAmount() > 0 ? remainder : null);
            }
            case RIGHT, SHIFT_LEFT -> {
                boolean shift = event.isShiftClick();
                if (shift && !empty || !shift && !empty && !matching || stored == 0 && !matching) return;
                event.setCancelled(true);
                if (stored == 0 || matching && cursor.getAmount() >= cursor.getMaxStackSize()) return;
                ItemStack outgoing = matching ? cursor.clone() : plugin.item(flour, 1);
                if (outgoing == null) return;
                int amount = shift ? Math.min(stored, outgoing.getMaxStackSize()) : 1;
                outgoing.setAmount(amount + (matching ? cursor.getAmount() : 0));
                setBagClick(event, player, bagItem, stored - amount, outgoing);
            }
            default -> { }
        }
    }

    private void setBagClick(InventoryClickEvent event, Player player, ItemStack original, int amount, ItemStack cursor) {
        ItemStack changed = original.clone();
        if (!writeBag(changed, amount)) return;
        event.setCurrentItem(changed);
        player.setItemOnCursor(cursor);
    }

    private void withdraw(Player player, ItemStack item) {
        if (item.getAmount() != 1) return;
        int stored = stored(item), amount = player.isSneaking() ? Math.min(64, stored) : Math.min(1, stored);
        if (amount == 0) return;
        ItemStack result = plugin.item(flour, amount);
        if (result == null || !writeBag(item, stored - amount)) return;
        player.getInventory().setItemInMainHand(item);
        plugin.give(player, result);
    }

    private int stored(ItemStack item) {
        String value = plugin.connection().items().identify(item).map(identity -> identity.state().get("flour")).orElse("0");
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    private boolean writeBag(ItemStack item, int stored) {
        if (!plugin.connection().items().updateState(item, Map.of("flour", Integer.toString(stored)))) return false;
        showBagAmount(plugin, config, item, stored);
        return true;
    }

    static void showBagAmount(CropsPlugin plugin, ConfigurationSection settings, ItemStack item, int stored) {
        var meta = item.getItemMeta();
        if (meta == null) return;
        String lore = settings.getString("bags.flour_bag.lore", "&7Flour: &f<amount>/<capacity>")
                .replace("<amount>", Integer.toString(stored))
                .replace("<capacity>", Integer.toString(Math.max(1, settings.getInt("bags.flour_bag.capacity", 256))));
        meta.setLore(List.of(plugin.connection().text().legacy(lore)));
        item.setItemMeta(meta);
    }
}
