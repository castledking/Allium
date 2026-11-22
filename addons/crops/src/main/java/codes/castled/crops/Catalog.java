package codes.castled.crops;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

final class Catalog {
    record Visual(String item, ItemDisplayTransform transform, float scale, double offset, boolean align) {}
    record Stage(Visual visual, long duration) {}
    record Drop(String item, int min, int max) {}
    record Entry(double weight, double chance, List<Drop> drops) {}
    record Table(String mode, List<Entry> entries) {
        List<Drop> roll() {
            if (mode.equals("WEIGHTED_ONE")) {
                Entry chosen = weighted(entries, Entry::weight);
                return chosen == null ? List.of() : chosen.drops();
            }
            var random = ThreadLocalRandom.current();
            return entries.stream().filter(entry -> !mode.equals("INDEPENDENT") || random.nextDouble() < entry.chance())
                    .flatMap(entry -> entry.drops().stream()).toList();
        }
    }
    record Path(String id, double weight, List<Stage> stages, Table primary, Table additional,
                Table immature, Table mature, int rightRegrowth, int breakRegrowth, String delivery) {}
    record Crop(String id, String seed, Set<Material> soils, boolean water, int light,
                double randomness, int catchUp, boolean right, boolean punch, String progress, Map<String, Path> paths) {
        Path rollPath() { return weighted(paths.values(), Path::weight); }
    }

    static Map<String, Crop> read(ConfigurationSection root) {
        Map<String, Crop> crops = new LinkedHashMap<>();
        for (String id : root.getKeys(false)) {
            if (id.equals("weed")) continue;
            var row = root.getConfigurationSection(id);
            var paths = new LinkedHashMap<String, Path>();
            var pathRows = row.getConfigurationSection("paths.entries");
            for (String name : pathRows.getKeys(false)) {
                var path = pathRows.getConfigurationSection(name);
                List<Stage> stages = new ArrayList<>();
                for (var stage : path.getMapList("stages")) {
                    Map<?, ?> visual = stage.get("visual") instanceof Map<?, ?> map ? map : Map.of();
                    stages.add(new Stage(new Visual(string(stage, "model", ""),
                            ItemDisplayTransform.valueOf(string(visual, "transform", "GROUND")),
                            Float.parseFloat(string(visual, "scale", "1")),
                            Double.parseDouble(string(visual, "y-offset", ".001")),
                            Boolean.parseBoolean(string(visual, "align-to-soil", "true"))),
                            duration(string(stage, "duration", "0"))));
                }
                if (stages.isEmpty()) throw new IllegalArgumentException(id + "/" + name + " has no stages");
                int legacyStage = path.getInt("regrowth.stage", 0);
                int right = path.getBoolean("regrowth.on-right-click.enabled", path.getBoolean("regrowth.enabled"))
                        ? path.getInt("regrowth.on-right-click.stage", legacyStage) : -1;
                int broke = path.getBoolean("regrowth.on-break.enabled") ? path.getInt("regrowth.on-break.stage", 0) : -1;
                paths.put(name, new Path(name, path.getDouble("weight", 1), List.copyOf(stages),
                        table(path.getConfigurationSection("mature-harvest.primary")),
                        table(path.getConfigurationSection("mature-harvest.additional")),
                        table(path.getConfigurationSection("break-drops.immature")),
                        table(path.getConfigurationSection("break-drops.mature")), right, broke,
                        path.getString("mature-harvest.delivery", "DROP_AT_CROP")));
            }
            Set<Material> soils = new HashSet<>();
            for (String material : row.getStringList("placement.valid-soils")) {
                soils.add(Objects.requireNonNull(Material.matchMaterial(material), "Soil " + material));
            }
            crops.put(id, new Crop(id, row.getString("seed.item"), Set.copyOf(soils),
                    row.getBoolean("placement.requires-water"), row.getInt("placement.minimum-light", 9),
                    Math.max(0, Math.min(1, row.getDouble("growth.randomness", .25))),
                    Math.max(1, row.getInt("growth.maximum-catch-up-stages", 10)),
                    row.getBoolean("interaction.right-click-harvest"), row.getBoolean("interaction.break-harvest", true),
                    row.getString("interaction.progress-check", "OFF"), Collections.unmodifiableMap(paths)));
        }
        return crops;
    }

    private static Table table(ConfigurationSection table) {
        var entries = new ArrayList<Entry>();
        var rows = table == null ? null : table.getConfigurationSection("entries");
        if (rows != null) for (String name : rows.getKeys(false)) {
            var row = rows.getConfigurationSection(name);
            List<Drop> drops = new ArrayList<>();
            for (var drop : row.getMapList("drops")) {
                Object amount = drop.get("amount");
                int min = 1, max = 1;
                if (amount instanceof Map<?, ?> limits) {
                    min = Integer.parseInt(string(limits, "min", "1"));
                    max = Integer.parseInt(string(limits, "max", Integer.toString(min)));
                } else if (amount instanceof Number n) {
                    min = max = n.intValue();
                }
                drops.add(new Drop(string(drop, "item", ""), min, max));
            }
            entries.add(new Entry(row.getDouble("weight", 1), row.getDouble("chance", 1), List.copyOf(drops)));
        }
        return new Table(table == null ? "GUARANTEED_ALL" : table.getString("mode", "GUARANTEED_ALL"), List.copyOf(entries));
    }

    static String string(Map<?, ?> row, String field, String fallback) {
        Object value = row.get(field);
        return value == null ? fallback : value.toString();
    }

    static <T> T weighted(Collection<T> entries, java.util.function.ToDoubleFunction<T> value) {
        double total = entries.stream().mapToDouble(entry -> Math.max(0, value.applyAsDouble(entry))).sum();
        if (total <= 0) return null;
        double roll = ThreadLocalRandom.current().nextDouble(total);
        for (T entry : entries) {
            roll -= Math.max(0, value.applyAsDouble(entry));
            if (roll < 0) return entry;
        }
        return null;
    }

    static long duration(String input) {
        var units = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(ms|[dhms]?)").matcher(input.toLowerCase(Locale.ROOT));
        long time = 0;
        int end = 0;
        while (units.find() && units.start() == end) {
            end = units.end();
            time += (long) (Double.parseDouble(units.group(1)) * switch (units.group(2)) {
                case "d" -> 86_400_000;
                case "h" -> 3_600_000;
                case "m" -> 60_000;
                case "ms" -> 1;
                default -> 1_000;
            });
        }
        if (end != input.length()) throw new IllegalArgumentException("Invalid duration: " + input);
        return time;
    }
}
