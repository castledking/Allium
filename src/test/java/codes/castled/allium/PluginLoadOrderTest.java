package codes.castled.allium;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PluginLoadOrderTest {
    @Test void resourcePackAndReliqueIntegrationsDoNotCreateAStartupCycle() throws Exception {
        YamlConfiguration descriptor = new YamlConfiguration();
        try (var stream = getClass().getResourceAsStream("/plugin.yml")) {
            assertNotNull(stream);
            descriptor.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        // Edges point from a plugin to one that must enable before it. These
        // are the installed Paper plugins' published server dependencies.
        Map<String, Set<String>> prerequisites = new HashMap<>();
        prerequisites.put("AbyssalLib", new HashSet<>(Set.of("ResourcePackManager")));
        prerequisites.put("Relique", new HashSet<>(Set.of("AbyssalLib")));
        prerequisites.put("Antiquities", new HashSet<>(Set.of("AbyssalLib", "Relique")));
        Set<String> allium = new HashSet<>(descriptor.getStringList("depend"));
        allium.addAll(descriptor.getStringList("softdepend"));
        prerequisites.put("Allium", allium);
        for (String later : descriptor.getStringList("loadbefore")) {
            prerequisites.computeIfAbsent(later, key -> new HashSet<>()).add("Allium");
        }
        for (String plugin : prerequisites.keySet()) {
            assertFalse(cycle(plugin, prerequisites, new HashSet<>(), new HashSet<>()),
                "Startup cycle through " + plugin + ": " + prerequisites);
        }
    }

    private boolean cycle(String plugin, Map<String, Set<String>> graph,
                          Set<String> visited, Set<String> visiting) {
        if (visiting.contains(plugin)) return true;
        if (!visited.add(plugin)) return false;
        visiting.add(plugin);
        for (String prerequisite : graph.getOrDefault(plugin, Set.of())) {
            if (cycle(prerequisite, graph, visited, visiting)) return true;
        }
        visiting.remove(plugin);
        return false;
    }
}
