package codes.castled.allium.pack;

import codes.castled.allium.item.AlliumItemDefinition;
import codes.castled.allium.item.ItemHmacSigner;
import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.MockItemStacks;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.tool.disarmer.DisarmerCharges;
import codes.castled.allium.tool.disarmer.DisarmerVisualState;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("deprecation")
class CoreItemsTest {

    @Test
    void bundledManifestDefinesEveryCoreItemWithItsCarrierAndDefaultModelData() throws IOException {
        AssetManifest manifest = CoreItems.loadManifest(getClass().getClassLoader());
        List<AlliumItemDefinition> definitions = CoreItems.definitions(manifest);
        Map<String, AlliumItemDefinition> byId = new HashMap<>();
        definitions.forEach(d -> byId.put(d.id(), d));
        assertEquals(Material.GOLDEN_HOE, byId.get("mob_disarmer").carrierMaterial());
        assertEquals(2577020, byId.get("mob_disarmer").customModelData());
        assertTrue(byId.get("mob_disarmer").signed());
        assertFalse(byId.get("mob_disarmer").privileged());
        assertEquals(Material.IRON_SHOVEL, byId.get("handcuffs").carrierMaterial());
        assertTrue(byId.get("handcuffs").privileged());
        assertEquals(Material.BLAZE_ROD, byId.get("phantom_obliterator").carrierMaterial());
        assertEquals(Map.of("charges", "3"), byId.get("phantom_obliterator").defaultState());
        assertEquals(Material.NAME_TAG, byId.get("item_renamer").carrierMaterial());
        assertEquals("§6Mob Disarmer", byId.get("mob_disarmer").displayName());
        assertNotNull(getClass().getClassLoader().getResource(CoreItems.PACK_RESOURCE), "the generated pack is bundled");
        assertNotNull(getClass().getClassLoader().getResource(CoreItems.PACK_INFO_RESOURCE));
    }

    @Test
    void stateWritesSelectTheMatchingModelOnTheSameStack() throws IOException {
        AssetManifest manifest = CoreItems.loadManifest(getClass().getClassLoader());
        ItemService items = new ItemService(MockItemStacks::create, ItemHmacSigner.randomEphemeral(), msg -> { });
        CoreItems.definitions(manifest).forEach(items::registerAlliumItem);
        Map<String, Integer> byState = new HashMap<>();
        manifest.tool("mob_disarmer").orElseThrow().states().forEach(s -> byState.put(s.key(), s.customModelData()));
        items.setModelSelector("mob_disarmer", state -> byState.get(DisarmerVisualState.of(DisarmerCharges.fromState(state, 3), 3).key()));

        ItemStack tool = items.createForBehavior("allium:mob_disarmer", 1).itemStack().orElseThrow();
        assertEquals(2577020, tool.getItemMeta().getCustomModelData());
        String uuid = items.identify(tool).orElseThrow().stateData().toString();
        assertNotNull(uuid);

        assertTrue(items.updateState(tool, new DisarmerCharges(2, 0L, false, 1L).toState()));
        assertEquals(2577021, tool.getItemMeta().getCustomModelData());
        assertEquals(Material.GOLDEN_HOE, tool.getType(), "never an item swap");
        assertTrue(items.updateState(tool, new DisarmerCharges(0, 5L, false, 3L).toState()));
        assertEquals(2577023, tool.getItemMeta().getCustomModelData());
        assertTrue(items.updateState(tool, new DisarmerCharges(0, 5L, true, 4L).toState()));
        assertEquals(2577024, tool.getItemMeta().getCustomModelData());
        assertEquals(java.util.Optional.of("allium:mob_disarmer"), items.identify(tool).orElseThrow().behaviorId(), "still verifies");

        ItemStack created = items.create(new ItemRef(ProviderKey.allium("mob_disarmer"), "allium:mob_disarmer", 1,
                new DisarmerCharges(1, 0L, false, 2L).toState())).itemStack().orElseThrow();
        assertEquals(2577022, created.getItemMeta().getCustomModelData(), "created with state selects its model too");

        items.setModelSelector("mob_disarmer", null);
        assertTrue(items.updateState(created, DisarmerCharges.full(3).toState()));
        assertEquals(2577022, created.getItemMeta().getCustomModelData(), "no selector leaves model data alone");
    }

    @Test
    void resourcePackOwnerDefaultsToNoneAndReservedOwnersWarn() throws Exception {
        assertEquals(ResourcePackSettings.Owner.NONE, ResourcePackSettings.parse(null).owner());
        YamlConfiguration shipped = new YamlConfiguration();
        shipped.load(new java.io.InputStreamReader(getClass().getResourceAsStream("/config.yml"), java.nio.charset.StandardCharsets.UTF_8));
        ResourcePackSettings settings = ResourcePackSettings.parse(shipped.getConfigurationSection("resource-pack"));
        assertEquals(ResourcePackSettings.Owner.NONE, settings.owner());
        assertEquals(List.of(), settings.warnings());
        YamlConfiguration rpm = new YamlConfiguration();
        rpm.loadFromString("owner: rpm-export\n");
        assertEquals(ResourcePackSettings.Owner.RPM_EXPORT, ResourcePackSettings.parse(rpm).owner());
        assertTrue(ResourcePackSettings.parse(rpm).warnings().get(0).contains("not implemented"));
        YamlConfiguration bad = new YamlConfiguration();
        bad.loadFromString("owner: everyone\n");
        assertEquals(ResourcePackSettings.Owner.NONE, ResourcePackSettings.parse(bad).owner());
    }
}
