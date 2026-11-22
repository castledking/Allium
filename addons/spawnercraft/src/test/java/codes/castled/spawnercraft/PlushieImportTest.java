package codes.castled.spawnercraft;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlushieImportTest {
    @TempDir Path directory;

    @Test
    void importsSfMobCatalogAndKeepsEditsOnRepeat() throws Exception {
        Path catalog = directory.resolve("plugins/Nexo/items/sf/plushies/mobs.yml");
        Files.createDirectories(catalog.getParent());
        Files.writeString(catalog, "fox_plushie: {}\nenderdragon_plushie: {}\n");
        List<String> mobs = List.of("fox", "ender_dragon", "cat");
        var source = PlushieImport.discover(directory, mobs);

        var yaml = new YamlConfiguration();
        assertEquals(2, PlushieImport.merge(yaml, mobs, source));
        assertEquals(List.of("WWW", "WHW", "WWW"), yaml.getStringList("recipes.plushie_fox.pattern"));
        assertEquals("nexo:enderdragon_plushie", yaml.getString("recipes.plushie_ender_dragon.result.item"));

        yaml.set("recipes.plushie_fox.result.item", "nexo:custom_fox");
        Path output = directory.resolve("imported-recipes.yml");
        PlushieImport.save(yaml, output);
        var reloaded = PlushieImport.load(output);
        assertEquals(0, PlushieImport.merge(reloaded, mobs, source));
        assertEquals("nexo:custom_fox", reloaded.getString("recipes.plushie_fox.result.item"));
    }

    @Test
    void readsTheLegacyRuleWithItsPlushieAliases() throws Exception {
        Path config = directory.resolve("src/main/resources/config.yml");
        Path rule = directory.resolve("src/main/java/codes/castled/allium/spawnercraft/PlushieCraftListener.java");
        Files.createDirectories(config.getParent());
        Files.createDirectories(rule.getParent());
        Files.writeString(config, "spawnercraft:\n  plushies: true\n");
        Files.createFile(rule);
        List<String> mobs = List.of("copper_golem", "zombie_nautilus");
        var yaml = new YamlConfiguration();
        assertEquals(2, PlushieImport.merge(yaml, mobs, PlushieImport.discover(directory, mobs)));
        assertEquals("nexo:copper_golem_unoxidized_plushie", yaml.getString("recipes.plushie_copper_golem.result.item"));
        assertEquals("nexo:zombie_nautilus_temperate_plushie", yaml.getString("recipes.plushie_zombie_nautilus.result.item"));
    }
}
