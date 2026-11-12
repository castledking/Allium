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
        assertEquals(Material.PHANTOM_MEMBRANE, byId.get("phantom_obliterator").carrierMaterial(), "moved off blaze_rod, which SF's Nexo pack overrides");
        assertEquals(Map.of("charges", "3"), byId.get("phantom_obliterator").defaultState());
        assertEquals(Material.NAME_TAG, byId.get("item_renamer").carrierMaterial());
        assertEquals(Material.NETHERITE_AXE, byId.get("lazy_axe").carrierMaterial());
        assertEquals(2577050, byId.get("lazy_axe").customModelData());
        assertTrue(byId.get("lazy_axe").signed());
        assertFalse(byId.get("lazy_axe").privileged());
        // A core tool item binds to its bundled definition's id (tools/<id>.yml), never to the
        // behavior type: the loader otherwise sees the item as owned by another definition and
        // rejects the shipped file once it is enabled.
        assertEquals("allium:mob_disarmer", byId.get("mob_disarmer").behaviorId());
        assertEquals("allium:phantom_obliterator", byId.get("phantom_obliterator").behaviorId());
        assertEquals("allium:lazy_axe", byId.get("lazy_axe").behaviorId());
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
    void resourcePackSettingsDefaultToNoneAndParseEachOwner() throws Exception {
        assertEquals(ResourcePackSettings.Owner.NONE, ResourcePackSettings.parse(null).owner());
        YamlConfiguration shipped = new YamlConfiguration();
        shipped.load(new java.io.InputStreamReader(getClass().getResourceAsStream("/config.yml"), java.nio.charset.StandardCharsets.UTF_8));
        ResourcePackSettings settings = ResourcePackSettings.parse(shipped.getConfigurationSection("resource-pack"));
        assertEquals(ResourcePackSettings.Owner.NONE, settings.owner());
        assertEquals(List.of(), settings.warnings());
        assertFalse(settings.required());
        assertTrue(settings.verifyHostedFile());
        assertEquals("ResourcePackManager/mixer", settings.mixerDirectory());
        assertEquals("Allium_core_resource_pack.zip", settings.mixerFileName());
        assertFalse(settings.sendsUrl());

        YamlConfiguration standalone = new YamlConfiguration();
        standalone.loadFromString("owner: standalone\nstandalone:\n  url: https://cdn.example/pack.zip\nrequired: true\nprompt: '&aHi'\n");
        ResourcePackSettings s = ResourcePackSettings.parse(standalone);
        assertEquals(ResourcePackSettings.Owner.STANDALONE, s.owner());
        assertTrue(s.sendsUrl() && s.required());
        assertEquals("&aHi", s.prompt());
        assertEquals(List.of(), s.warnings());

        YamlConfiguration noUrl = new YamlConfiguration();
        noUrl.loadFromString("owner: standalone\n");
        assertFalse(ResourcePackSettings.parse(noUrl).sendsUrl());
        assertTrue(ResourcePackSettings.parse(noUrl).warnings().get(0).contains("standalone.url"));

        YamlConfiguration rpm = new YamlConfiguration();
        rpm.loadFromString("owner: rpm_export\nrpm-export:\n  file-name: ../../evil.zip\n  dry-run: true\n");
        ResourcePackSettings r = ResourcePackSettings.parse(rpm);
        assertEquals(ResourcePackSettings.Owner.RPM_EXPORT, r.owner());
        assertTrue(r.dryRun());
        assertEquals("Allium_core_resource_pack.zip", r.mixerFileName(), "a path in file-name is refused");
        assertTrue(r.warnings().get(0).contains("plain .zip file name"));

        YamlConfiguration bad = new YamlConfiguration();
        bad.loadFromString("owner: everyone\n");
        assertEquals(ResourcePackSettings.Owner.NONE, ResourcePackSettings.parse(bad).owner());
    }
}
