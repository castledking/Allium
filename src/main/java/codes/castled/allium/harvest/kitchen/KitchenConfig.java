package codes.castled.allium.harvest.kitchen;

import codes.castled.allium.harvest.crop.def.ValidationIssue;
import codes.castled.allium.harvest.item.ItemRef;
import codes.castled.allium.harvest.item.ItemResolverChain;
import codes.castled.allium.harvest.util.Durations;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

/**
 * Parsed {@code harvest/kitchen.yml}: storage bags, the kneading space and pie
 * assembly, baking, cooling and eating.
 *
 * <p>Parsing never throws. Problems are collected as {@link ValidationIssue}s
 * and the affected entry is dropped, so one broken pie type or a missing Nexo
 * item leaves the rest of the kitchen working — the same best-effort rule the
 * crop loader follows.
 */
public record KitchenConfig(
    boolean enabled,
    Map<ItemRef, Bag> bags,
    Kneading kneading,
    Pies pies,
    Map<String, Stove> furnaces
) {

    public static final String FILE = "kitchen.yml";

    /** Which vanilla recipe book a stove cooks from. */
    public enum StoveType { FURNACE, SMOKER, BLAST_FURNACE }

    /**
     * Nexo furniture that works as a furnace when right-clicked.
     *
     * @param speed cook time multiplier; 0.5 cooks twice as fast
     */
    public record Stove(String furnitureId, String title, StoveType type, double speed) {}

    /** A storage bag: one item that holds up to {@code capacity} of another. */
    public record Bag(String id, ItemRef item, ItemRef stores, int capacity, String loreLine) {}

    public record Kneading(
        boolean enabled,
        Set<Material> blocks,
        Set<String> nexoStations,
        int waterRequired,
        int bucketUnits,
        int bottleUnits,
        ItemRef flour,
        int flourRequired,
        ItemRef result,
        int resultAmount,
        String sound
    ) {}

    /**
     * One assembly step. {@code item} is null for the filling step, which
     * accepts the filling of any configured pie type and decides the flavour.
     */
    public record Step(ItemRef item, int amount, String name) {
        public boolean isFilling() {
            return item == null;
        }
    }

    public record PieType(
        String id,
        List<ItemRef> fillings,
        String fillingName,
        ItemRef coldItem,
        ItemRef bakedItem,
        ItemRef filledModel,
        List<ItemRef> coldModels,
        List<ItemRef> bakedModels,
        Set<Integer> skipSteps
    ) {
        /** Whether this pie leaves out the assembly step at the given (0-based) index. */
        public boolean skips(int step) {
            return skipSteps.contains(step);
        }

        /** The first filling, used as the hologram icon. */
        public ItemRef filling() {
            return fillings.get(0);
        }

        public ItemRef item(boolean baked) {
            return baked ? bakedItem : coldItem;
        }

        public List<ItemRef> models(boolean baked) {
            return baked ? bakedModels : coldModels;
        }
    }

    public record Eating(int nutrition, float saturation, boolean requireHunger,
                         boolean requireBuildPermission, String sound) {}

    public record Cooling(long millis, boolean droppedItems) {}

    public record Hologram(boolean enabled, double height, long cycleTicks,
                           List<String> stepLines, List<String> doneLines) {}

    public record Pies(
        boolean enabled,
        ItemRef crust,
        ItemRef crustModel,
        List<Step> steps,
        Map<String, PieType> types,
        Eating eating,
        Cooling cooling,
        Hologram hologram
    ) {
        /** The number of bite states, i.e. models per pie. */
        public static final int SLICES = 4;

        public Optional<PieType> type(String id) {
            return Optional.ofNullable(id == null ? null : types.get(id));
        }

        public Optional<PieType> typeByFilling(ItemRef filling) {
            return types.values().stream().filter(t -> t.fillings().contains(filling)).findFirst();
        }

        /** The pie type and baked flag of a whole-pie item, if it is one. */
        public Optional<Map.Entry<PieType, Boolean>> byItem(ItemRef ref) {
            for (PieType type : types.values()) {
                if (type.coldItem().equals(ref)) return Optional.of(Map.entry(type, false));
                if (type.bakedItem().equals(ref)) return Optional.of(Map.entry(type, true));
            }
            return Optional.empty();
        }
    }

    public static KitchenConfig disabled() {
        return new KitchenConfig(false, Map.of(), null, null, Map.of());
    }

    // ==================== loading ====================

    public record LoadResult(KitchenConfig config, List<ValidationIssue> issues) {}

    public static LoadResult load(ConfigurationSection yaml, ItemResolverChain items) {
        return load(yaml, items::hasNamespace, items::exists, ref -> items.create(ref, 1));
    }

    /**
     * Loads with explicit item lookups, the same seam the crop loader uses so
     * parsing can be tested without a running server.
     *
     * @param create builds a stack for a reference, used only for item names
     */
    public static LoadResult load(ConfigurationSection yaml, Predicate<String> knownNamespace,
                                  Predicate<ItemRef> itemExists,
                                  Function<ItemRef, Optional<ItemStack>> create) {
        Loader loader = new Loader(knownNamespace, itemExists, create);
        KitchenConfig config = loader.load(yaml);
        return new LoadResult(config, loader.issues);
    }

    private static final class Loader {
        private final Predicate<String> knownNamespace;
        private final Predicate<ItemRef> itemExists;
        private final Function<ItemRef, Optional<ItemStack>> create;
        private final List<ValidationIssue> issues = new ArrayList<>();

        Loader(Predicate<String> knownNamespace, Predicate<ItemRef> itemExists,
               Function<ItemRef, Optional<ItemStack>> create) {
            this.knownNamespace = knownNamespace;
            this.itemExists = itemExists;
            this.create = create;
        }

        KitchenConfig load(ConfigurationSection yaml) {
            if (yaml == null || !yaml.getBoolean("enabled", true)) {
                return disabled();
            }
            return new KitchenConfig(true, bags(yaml.getConfigurationSection("bags")),
                kneading(yaml.getConfigurationSection("kneading")),
                pies(yaml.getConfigurationSection("pies")),
                furnaces(yaml.getConfigurationSection("furnaces")));
        }

        private Map<String, Stove> furnaces(ConfigurationSection section) {
            Map<String, Stove> stoves = new LinkedHashMap<>();
            if (section == null || !section.getBoolean("enabled", true)) return stoves;
            ConfigurationSection root = section.getConfigurationSection("stations");
            if (root == null) return stoves;
            for (String rawId : root.getKeys(false)) {
                String base = "furnaces.stations." + rawId;
                ConfigurationSection s = root.getConfigurationSection(rawId);
                if (s == null) {
                    error(base, "Furnace station is not a section");
                    continue;
                }
                StoveType type;
                try {
                    type = StoveType.valueOf(s.getString("type", "FURNACE").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    error(base + ".type", "Type must be FURNACE, SMOKER or BLAST_FURNACE");
                    continue;
                }
                double speed = s.getDouble("speed", 1.0D);
                if (speed <= 0.0D) {
                    error(base + ".speed", "Speed must be greater than zero");
                    continue;
                }
                String id = rawId.toLowerCase(Locale.ROOT);
                stoves.put(id, new Stove(id, s.getString("title", "Furnace"), type, speed));
            }
            return Collections.unmodifiableMap(stoves);
        }

        private Map<ItemRef, Bag> bags(ConfigurationSection root) {
            Map<ItemRef, Bag> bags = new LinkedHashMap<>();
            if (root == null) return bags;
            for (String id : root.getKeys(false)) {
                String base = "bags." + id;
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) {
                    error(base, "Bag entry is not a section");
                    continue;
                }
                ItemRef item = item(section.getString("item"), base + ".item");
                ItemRef stores = item(section.getString("stores"), base + ".stores");
                int capacity = section.getInt("capacity", 256);
                if (capacity < 1) {
                    error(base + ".capacity", "Capacity must be at least 1");
                    continue;
                }
                if (item == null || stores == null) continue;
                if (bags.containsKey(item)) {
                    error(base + ".item", "Item " + item + " is already used by another bag");
                    continue;
                }
                bags.put(item, new Bag(id, item, stores, capacity,
                    section.getString("lore", "<gray>Stored: <white><amount></white>/<capacity>")));
            }
            return Collections.unmodifiableMap(bags);
        }

        private Kneading kneading(ConfigurationSection section) {
            if (section == null || !section.getBoolean("enabled", true)) {
                return null;
            }
            Set<Material> blocks = EnumSet.noneOf(Material.class);
            for (String raw : section.getStringList("stations.blocks")) {
                Material material = Material.matchMaterial(raw);
                if (material == null || !isBlock(material)) {
                    error("kneading.stations.blocks", "'" + raw + "' is not a block");
                    continue;
                }
                blocks.add(material);
                // An empty cauldron and a filled one are different materials,
                // but they are the same station.
                if (material == Material.CAULDRON || material == Material.WATER_CAULDRON) {
                    blocks.add(Material.CAULDRON);
                    blocks.add(Material.WATER_CAULDRON);
                }
            }
            Set<String> nexo = new LinkedHashSet<>();
            for (String raw : section.getStringList("stations.nexo")) {
                nexo.add(raw.toLowerCase(Locale.ROOT));
            }
            if (blocks.isEmpty() && nexo.isEmpty()) {
                warning("kneading.stations", "No kneading stations configured");
            }
            int waterRequired = Math.max(0, section.getInt("water.required", 3));
            if (blocks.contains(Material.CAULDRON) && waterRequired > 3) {
                error("kneading.water.required",
                    "A cauldron holds 3 levels of water, so it can never reach " + waterRequired);
                return null;
            }
            ItemRef flour = item(section.getString("flour.item"), "kneading.flour.item");
            ItemRef result = item(section.getString("result.item"), "kneading.result.item");
            if (flour == null || result == null) {
                return null;
            }
            return new Kneading(true, Collections.unmodifiableSet(blocks), Collections.unmodifiableSet(nexo),
                waterRequired,
                Math.max(1, section.getInt("water.bucket", 3)),
                Math.max(1, section.getInt("water.bottle", 1)),
                flour, Math.max(1, section.getInt("flour.required", 3)),
                result, Math.max(1, section.getInt("result.amount", 1)),
                section.getString("sound", "block.mud.place"));
        }

        private Pies pies(ConfigurationSection section) {
            if (section == null || !section.getBoolean("enabled", true)) {
                return null;
            }
            ItemRef crust = item(section.getString("crust.item"), "pies.crust.item");
            ItemRef crustModel = item(section.getString("crust.model"), "pies.crust.model");

            List<Step> steps = new ArrayList<>();
            int fillingSteps = 0;
            int fillingIndex = -1;
            List<Map<?, ?>> rawSteps = section.getMapList("steps");
            for (int i = 0; i < rawSteps.size(); i++) {
                Map<?, ?> raw = rawSteps.get(i);
                String path = "pies.steps[" + i + "]";
                int amount = raw.get("amount") instanceof Number n ? n.intValue() : 1;
                if (amount < 1) {
                    error(path + ".amount", "Amount must be at least 1");
                    return null;
                }
                String name = raw.get("name") == null ? null : raw.get("name").toString();
                if (Boolean.TRUE.equals(raw.get("filling"))) {
                    fillingSteps++;
                    fillingIndex = i;
                    steps.add(new Step(null, amount, name));
                    continue;
                }
                ItemRef ref = item(raw.get("item") == null ? null : raw.get("item").toString(), path + ".item");
                if (ref == null) return null;
                steps.add(new Step(ref, amount, name == null ? displayName(ref) : name));
            }
            if (fillingSteps != 1) {
                error("pies.steps", "Exactly one step must be `filling: true` (found " + fillingSteps + ")");
                return null;
            }

            ConfigurationSection patterns = section.getConfigurationSection("models");
            String itemPattern = patterns == null ? "nexo:{state}_{type}_pie"
                : patterns.getString("item", "nexo:{state}_{type}_pie");
            String placedPattern = patterns == null ? "nexo:{state}_{type}_pie_placed{slice}"
                : patterns.getString("placed", "nexo:{state}_{type}_pie_placed{slice}");
            String filledPattern = patterns == null ? "nexo:{type}_pie_filled"
                : patterns.getString("filled", "nexo:{type}_pie_filled");

            Map<String, PieType> types = new LinkedHashMap<>();
            ConfigurationSection typeRoot = section.getConfigurationSection("types");
            if (typeRoot != null) {
                for (String rawId : typeRoot.getKeys(false)) {
                    String id = rawId.toLowerCase(Locale.ROOT);
                    String base = "pies.types." + rawId;
                    ConfigurationSection t = typeRoot.getConfigurationSection(rawId);
                    if (t == null) {
                        error(base, "Pie type is not a section");
                        continue;
                    }
                    int before = errorCount();
                    List<ItemRef> fillings = new ArrayList<>();
                    List<String> rawFillings = t.isList("filling") ? t.getStringList("filling")
                        : t.getString("filling") == null ? List.of() : List.of(t.getString("filling"));
                    if (rawFillings.isEmpty()) {
                        error(base + ".filling", "Missing filling");
                    }
                    for (int i = 0; i < rawFillings.size(); i++) {
                        ItemRef ref = item(rawFillings.get(i), base + ".filling[" + i + "]");
                        if (ref != null) fillings.add(ref);
                    }
                    ItemRef cold = item(t.getString("cold-item", fill(itemPattern, "cold", id, 0)), base + ".cold-item");
                    ItemRef baked = item(t.getString("baked-item", fill(itemPattern, "baked", id, 0)), base + ".baked-item");
                    ItemRef filled = item(t.getString("filled-model", fill(filledPattern, "cold", id, 0)), base + ".filled-model");
                    List<ItemRef> coldModels = models(t, "cold-models", placedPattern, "cold", id, base);
                    List<ItemRef> bakedModels = models(t, "baked-models", placedPattern, "baked", id, base);
                    // skip-steps uses the step numbers as listed (1-based). Only
                    // steps after the filling can be skipped: before it, nothing
                    // yet says which pie this is.
                    Set<Integer> skipSteps = new java.util.TreeSet<>();
                    for (Integer number : t.getIntegerList("skip-steps")) {
                        if (number <= fillingIndex + 1 || number > steps.size()) {
                            warning(base + ".skip-steps", "Ignoring step " + number + ": only steps "
                                + (fillingIndex + 2) + ".." + steps.size() + " come after the filling");
                        } else {
                            skipSteps.add(number - 1);
                        }
                    }
                    if (errorCount() > before) {
                        continue;
                    }
                    PieType duplicate = types.values().stream()
                        .filter(other -> other.fillings().stream().anyMatch(fillings::contains))
                        .findFirst().orElse(null);
                    if (duplicate != null) {
                        error(base + ".filling", "A filling here is already used by pie type '"
                            + duplicate.id() + "'");
                        continue;
                    }
                    types.put(id, new PieType(id, List.copyOf(fillings),
                        t.getString("filling-name", displayName(fillings.get(0))),
                        cold, baked, filled, coldModels, bakedModels, Set.copyOf(skipSteps)));
                }
            }
            if (types.isEmpty()) {
                warning("pies.types", "No usable pie types — pies are disabled");
            }
            if (crust == null || crustModel == null || types.isEmpty()) {
                return null;
            }

            ConfigurationSection eat = section.getConfigurationSection("eating");
            Eating eating = new Eating(
                eat == null ? 3 : Math.max(0, eat.getInt("nutrition", 3)),
                (float) (eat == null ? 0.6D : Math.max(0.0D, eat.getDouble("saturation", 0.6D))),
                eat == null || eat.getBoolean("require-hunger", true),
                eat == null || eat.getBoolean("require-build-permission", true),
                eat == null ? "entity.generic.eat" : eat.getString("sound", "entity.generic.eat"));

            ConfigurationSection cool = section.getConfigurationSection("cooling");
            long coolMillis = 600_000L;
            String coolRaw = cool == null ? null : cool.getString("time");
            if (coolRaw != null && !coolRaw.isBlank()) {
                try {
                    coolMillis = Durations.parseMillis(coolRaw);
                } catch (IllegalArgumentException e) {
                    error("pies.cooling.time", e.getMessage());
                }
            }
            Cooling cooling = new Cooling(coolMillis, cool == null || cool.getBoolean("dropped-items", true));

            ConfigurationSection holo = section.getConfigurationSection("hologram");
            long cycleTicks = 40L;
            String cycleRaw = holo == null ? null : holo.getString("icon-cycle");
            if (cycleRaw != null && !cycleRaw.isBlank()) {
                try {
                    cycleTicks = Math.max(1L, Durations.parseMillis(cycleRaw) / 50L);
                } catch (IllegalArgumentException e) {
                    error("pies.hologram.icon-cycle", e.getMessage());
                }
            }
            Hologram hologram = new Hologram(
                holo == null || holo.getBoolean("enabled", true),
                holo == null ? 1.35D : holo.getDouble("height", 1.35D),
                cycleTicks,
                lines(holo, "step", List.of("#ICON: <required-item>", "&aAdd &7<amount>x &6<item>")),
                lines(holo, "done", List.of("&6Done!", "&7Pick up the pie and bake it!")));

            return new Pies(true, crust, crustModel, List.copyOf(steps),
                Collections.unmodifiableMap(types), eating, cooling, hologram);
        }

        /** Material#isBlock needs the server's registries; without them (tests) accept the name. */
        private static boolean isBlock(Material material) {
            try {
                return material.isBlock();
            } catch (LinkageError | RuntimeException e) {
                return true;
            }
        }

        private List<ItemRef> models(ConfigurationSection t, String key, String pattern,
                                     String state, String id, String base) {
            List<String> raw = t.isList(key) ? t.getStringList(key) : null;
            List<ItemRef> models = new ArrayList<>();
            for (int slice = 0; slice < Pies.SLICES; slice++) {
                String ref = raw != null && slice < raw.size() ? raw.get(slice) : fill(pattern, state, id, slice);
                models.add(item(ref, base + "." + key + "[" + slice + "]"));
            }
            return models.contains(null) ? null : List.copyOf(models);
        }

        private static List<String> lines(ConfigurationSection section, String key, List<String> fallback) {
            if (section == null || !section.isList(key)) return fallback;
            List<String> lines = section.getStringList(key);
            return lines.isEmpty() ? fallback : List.copyOf(lines);
        }

        private static String fill(String pattern, String state, String type, int slice) {
            return pattern.replace("{state}", state).replace("{type}", type)
                .replace("{slice}", slice == 0 ? "" : "_slice" + slice);
        }

        private ItemRef item(String raw, String path) {
            if (raw == null || raw.isBlank()) {
                error(path, "Missing item reference");
                return null;
            }
            ItemRef ref;
            try {
                ref = ItemRef.parse(raw);
            } catch (IllegalArgumentException e) {
                error(path, e.getMessage());
                return null;
            }
            if (!knownNamespace.test(ref.namespace())) {
                error(path, "Item namespace '" + ref.namespace() + "' has no resolver — is the "
                    + ref.namespace() + " plugin installed?");
                return null;
            }
            if (!itemExists.test(ref)) {
                error(path, (ref.isVanilla() ? "Material" : "Nexo item") + " '" + ref.id() + "' does not exist");
                return null;
            }
            return ref;
        }

        private String displayName(ItemRef ref) {
            if (!ref.isVanilla()) {
                Optional<ItemStack> stack = create.apply(ref);
                if (stack.isPresent()) {
                    try {
                        String name = PlainTextComponentSerializer.plainText()
                            .serialize(stack.get().effectiveName()).trim();
                        if (!name.isEmpty()) return name;
                    } catch (RuntimeException | LinkageError ignored) {
                        // Fall through to the id.
                    }
                }
            }
            StringBuilder out = new StringBuilder();
            for (String word : ref.id().split("_")) {
                if (word.isEmpty()) continue;
                if (!out.isEmpty()) out.append(' ');
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            return out.toString();
        }

        private int errorCount() {
            return (int) issues.stream().filter(ValidationIssue::isError).count();
        }

        private void error(String path, String message) {
            issues.add(ValidationIssue.error(FILE, path, message));
        }

        private void warning(String path, String message) {
            issues.add(ValidationIssue.warning(FILE, path, message));
        }
    }
}
