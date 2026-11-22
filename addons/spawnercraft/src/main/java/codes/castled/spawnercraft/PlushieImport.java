package codes.castled.spawnercraft;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

final class PlushieImport {
    // Exceptions to legacy's <mob>_plushie naming convention.
    private static final Map<String, String> ALIASES = Map.of(
            "ender_dragon", "enderdragon", "copper_golem", "copper_golem_unoxidized",
            "zombie_nautilus", "zombie_nautilus_temperate");

    private PlushieImport() {}

    static Set<String> discover(Path source, Collection<String> mobs) throws IOException, InvalidConfigurationException {
        if (Files.isRegularFile(source)) return new LinkedHashSet<>(load(source).getKeys(false));
        for (String relative : List.of("plugins/Nexo/items/sf/plushies/mobs.yml", "Nexo/items/sf/plushies/mobs.yml",
                "items/sf/plushies/mobs.yml", "mobs.yml")) {
            Path file = source.resolve(relative);
            if (Files.isRegularFile(file)) return new LinkedHashSet<>(load(file).getKeys(false));
        }
        Path legacyConfig = source.resolve("src/main/resources/config.yml");
        Path legacyRule = source.resolve("src/main/java/codes/castled/allium/spawnercraft/PlushieCraftListener.java");
        if (Files.isRegularFile(legacyConfig) && Files.isRegularFile(legacyRule)) {
            if (!load(legacyConfig).getBoolean("spawnercraft.plushies", true)) return Set.of();
            Set<String> legacyItems = new LinkedHashSet<>();
            mobs.forEach(mob -> legacyItems.add(plushieId(mob)));
            return legacyItems;
        }
        throw new IOException("No Nexo mob plushie catalog or legacy Allium source found at " + source);
    }

    static int merge(YamlConfiguration target, Collection<String> mobs, Set<String> available) {
        int added = 0;
        for (String mob : mobs) {
            String plushie = plushieId(mob);
            if (!available.contains(plushie)) continue;
            String key = "recipes.plushie_" + mob;
            if (target.contains(key)) continue;
            target.set(key + ".mob", mob);
            target.set(key + ".name", Heads.title(mob) + " Plushie");
            target.set(key + ".pattern", List.of("WWW", "WHW", "WWW"));
            target.set(key + ".ingredients.W", "#wool");
            target.set(key + ".result.item", "nexo:" + plushie);
            target.set(key + ".result.preview", "GREEN_DYE");
            added++;
        }
        return added;
    }

    private static String plushieId(String mob) {
        return ALIASES.getOrDefault(mob, mob) + "_plushie";
    }

    static YamlConfiguration load(Path source) throws IOException, InvalidConfigurationException {
        var yaml = new YamlConfiguration();
        yaml.load(source.toFile());
        return yaml;
    }

    static void save(YamlConfiguration yaml, Path target) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "plushie-recipes", ".yml");
        try {
            yaml.save(temporary.toFile());
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
