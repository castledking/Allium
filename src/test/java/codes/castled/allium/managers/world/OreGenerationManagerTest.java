package codes.castled.allium.managers.world;

import static org.junit.jupiter.api.Assertions.*;
import codes.castled.allium.harvest.crop.def.BlockRef;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class OreGenerationManagerTest {
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }
    private final Map<NamespacedKey,Object> data = new HashMap<>();
    private final Map<String,Material> terrain = new HashMap<>();
    private boolean available = true, failPlacement;
    private int placed;
    private final YamlConfiguration config = new YamlConfiguration();
    private final Plugin plugin = proxy(Plugin.class,(object,method,args) -> switch(method.getName()) {
        case "getName" -> "Allium";
        case "namespace" -> "allium";
        case "getLogger" -> Logger.getLogger("OreGenerationTest");
        default -> throw new UnsupportedOperationException(method.getName());
    });
    private final OrePlacement placement = new OrePlacement() {
        public boolean exists(BlockRef ref) { return available; }
        public boolean place(Block block,BlockRef ref) {
            if (failPlacement) return false;
            block.setType(Material.NOTE_BLOCK); placed++; return true;
        }
        public String idOf(Block block) { return null; }
        public ItemStack itemOf(String id) { return null; }
    };
    private Chunk chunk() {
        var pdc = proxy(PersistentDataContainer.class,(object,method,args) -> switch(method.getName()) {
            case "has" -> data.containsKey(args[0]);
            case "set" -> { data.put((NamespacedKey)args[0],args[2]); yield null; }
            default -> throw new UnsupportedOperationException(method.getName());
        });
        var world = proxy(World.class,(object,method,args) -> switch(method.getName()) {
            case "getMinHeight" -> -64;
            case "getMaxHeight" -> 320;
            case "getSeed" -> 9L;
            case "getName" -> "Resource";
            default -> throw new UnsupportedOperationException(method.getName());
        });
        return proxy(Chunk.class,(object,method,args) -> switch(method.getName()) {
            case "isLoaded" -> true;
            case "getPersistentDataContainer" -> pdc;
            case "getWorld" -> world;
            case "getX", "getZ" -> 0;
            case "getBlock" -> {
                String key = args[0] + ":" + args[1] + ":" + args[2];
                yield proxy(Block.class,(b,m,a) -> switch(m.getName()) {
                    case "getType" -> terrain.getOrDefault(key,Material.STONE);
                    case "setType" -> { terrain.put(key,(Material)a[0]); yield null; }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
    private OreGenerationManager manager() throws Exception {
        config.loadFromString("""
            ore-generation:
              enabled: true
              worlds: [Resource]
              ores:
                silver:
                  block: sf:silver_ore
                  deepslate-block: sf:deepslate_silver_ore
                  min-height: 0
                  max-height: 16
                  chance-per-chunk: 1
            """);
        return new OreGenerationManager(plugin,placement,() -> config,chunk -> false);
    }
    @Test void successfulChunksArePersistentlyMarkedAndNeverRegenerated() throws Exception {
        var manager = manager();
        var chunk = chunk();
        assertTrue(manager.generateOresInChunk(chunk) > 0);
        int initial = placed;
        assertTrue(data.containsKey(new NamespacedKey("allium","ore_generated_silver")));
        assertEquals(0,manager.generateOresInChunk(chunk));
        assertEquals(initial,placed);
    }
    @Test void missingProviderDefinitionsDoNotConsumeTheChunk() throws Exception {
        var manager = manager();
        var chunk = chunk();
        available = false;
        assertEquals(0,manager.generateOresInChunk(chunk));
        assertTrue(data.isEmpty());
        available = true;
        assertTrue(manager.generateOresInChunk(chunk) > 0);
    }
    @Test void failedPlacementsLeaveOriginalStoneAndNoMarker() throws Exception {
        var manager = manager();
        failPlacement = true;
        assertEquals(0,manager.generateOresInChunk(chunk()));
        assertTrue(data.isEmpty());
        assertTrue(terrain.isEmpty());
    }
    @Test void oldMarkersDisableDuplicateLegacyGeneration() throws Exception {
        var manager = manager();
        data.put(new NamespacedKey("allium","allium_ore_generated"),(byte)1);
        assertEquals(0,manager.generateOresInChunk(chunk()));
        assertEquals(0,placed);
    }
    @Test void disabledOrClosedGenerationNeverWritesBlocks() throws Exception {
        var manager = manager();
        config.set("ore-generation.enabled",false);
        manager.reload();
        assertEquals(0,manager.generateOresInChunk(chunk()));
        config.set("ore-generation.enabled",true);
        manager.reload();
        manager.close();
        assertEquals(0,manager.generateOresInChunk(chunk()));
        assertTrue(data.isEmpty());
    }
}
