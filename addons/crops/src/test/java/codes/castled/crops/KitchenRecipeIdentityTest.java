package codes.castled.crops;

import codes.castled.allium.api.AddonConnection;
import codes.castled.allium.api.AddonItems;
import codes.castled.allium.api.ItemDefinition;
import codes.castled.allium.api.ItemIdentity;
import codes.castled.allium.api.ItemResult;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.FurnaceStartSmeltEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KitchenRecipeIdentityTest {

    private static final NamespacedKey KEY_BEHAVIOR = new NamespacedKey("allium", "behavior_id");
    private static final NamespacedKey KEY_SIGNATURE = new NamespacedKey("allium", "signature");
    private static final NamespacedKey KEY_ITEM = new NamespacedKey("allium", "item_id");

    private static Server previousServer;

    @BeforeAll
    static void installMockServer() throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        previousServer = (Server) serverField.get(null);
        Server server = mock(Server.class);
        ItemFactory itemFactory = mock(ItemFactory.class);
        when(itemFactory.isApplicable(any(ItemMeta.class), any(Material.class))).thenReturn(true);
        when(itemFactory.isApplicable(any(ItemMeta.class), any(ItemStack.class))).thenReturn(true);
        when(itemFactory.asMetaFor(any(ItemMeta.class), any(Material.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemFactory.asMetaFor(any(ItemMeta.class), any(ItemStack.class))).thenAnswer(inv -> inv.getArgument(0));
        when(server.getItemFactory()).thenReturn(itemFactory);
        when(server.getLogger()).thenReturn(Logger.getLogger("KitchenRecipeIdentityTest"));
        when(server.recipeIterator()).thenAnswer(inv -> Collections.emptyIterator());
        serverField.set(null, server);
    }

    @AfterAll
    static void restoreMockServer() throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, previousServer);
    }

    private CropsPlugin plugin;
    private AddonConnection connection;
    private Map<NamespacedKey, Recipe> registeredRecipes;
    private Map<NamespacedKey, Collection<String>> registeredAllowedRefs;
    private String activeKeyId;
    private Set<String> acceptedKeyIds;
    private Kitchen kitchen;

    @BeforeEach
    void setUp() throws Exception {
        registeredRecipes = new LinkedHashMap<>();
        registeredAllowedRefs = new LinkedHashMap<>();
        activeKeyId = "k2";
        acceptedKeyIds = new HashSet<>(Set.of("k1", "k2"));

        plugin = mock(CropsPlugin.class);
        when(plugin.getName()).thenReturn("Crops");
        connection = mock(AddonConnection.class);
        when(plugin.connection()).thenReturn(connection);

        AddonItems addonItems = new AddonItems() {
            @Override
            public void define(ItemDefinition definition) {}

            @Override
            public void registerRecipe(Recipe recipe) {
                registerRecipe(recipe, List.of());
            }

            @Override
            public void registerRecipe(Recipe recipe, Collection<String> allowedIngredientRefs) {
                NamespacedKey key = ((org.bukkit.Keyed) recipe).getKey();
                registeredRecipes.put(key, recipe);
                registeredAllowedRefs.put(key, List.copyOf(allowedIngredientRefs));
            }

            @Override
            public ItemResult create(String reference, int amount) {
                return new ItemResult(ItemResult.Status.CREATED, Optional.of(makeItem(reference, amount, activeKeyId)), "ok");
            }

            @Override
            public Optional<ItemIdentity> identify(ItemStack stack) {
                if (stack == null || stack.getType().isAir()) return Optional.empty();
                ItemMeta meta = stack.getItemMeta();
                if (meta != null && meta.getPersistentDataContainer() != null) {
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    String itemId = pdc.get(KEY_ITEM, PersistentDataType.STRING);
                    String behavior = pdc.get(KEY_BEHAVIOR, PersistentDataType.STRING);
                    String sig = pdc.get(KEY_SIGNATURE, PersistentDataType.STRING);
                    if (behavior != null || sig != null) {
                        if (itemId != null && behavior != null && sig != null && sig.startsWith("v2:")) {
                            String[] parts = sig.split(":");
                            if (parts.length == 3 && acceptedKeyIds.contains(parts[1]) && parts[2].equals("valid-" + itemId)) {
                                return Optional.of(new ItemIdentity("allium:" + itemId, Optional.of(behavior), Map.of()));
                            }
                        }
                        return Optional.of(new ItemIdentity("minecraft:" + stack.getType().name().toLowerCase(Locale.ROOT),
                                Optional.empty(), Map.of()));
                    }
                }
                return Optional.of(new ItemIdentity("minecraft:" + stack.getType().name().toLowerCase(Locale.ROOT),
                        Optional.empty(), Map.of()));
            }
        };
        when(connection.items()).thenReturn(addonItems);
        when(plugin.item(anyString(), anyInt())).thenAnswer(inv ->
                addonItems.create(inv.getArgument(0), inv.getArgument(1)).stack().orElse(null));
        when(plugin.reference(any())).thenAnswer(inv -> addonItems.identify(inv.getArgument(0))
                .map(id -> id.appearance().startsWith("allium:") && id.behavior().isEmpty() ? "" : id.appearance())
                .orElse(""));

        YamlConfiguration kitchenCfg = new YamlConfiguration();
        kitchenCfg.loadFromString("""
                enabled: true
                pies:
                  enabled: true
                  crust:
                    item: allium:pie_crust
                    model: allium:pie_crust_placed
                  models:
                    item: allium:{state}_{type}_pie
                    placed: allium:{state}_{type}_pie_placed{slice}
                    filled: allium:{type}_pie_filled
                  types:
                    apple:
                      filling: minecraft:apple
                crafting:
                  tomato_sauce:
                    ingredients:
                    - - allium:tomato
                      - allium:tomato_silver_star
                    - - allium:tomato
                      - allium:tomato_silver_star
                    - minecraft:bowl
                    result:
                      item: allium:tomato_sauce
                      amount: 1
                """);

        YamlConfiguration externalRecipes = new YamlConfiguration();
        externalRecipes.loadFromString("""
                pie_crust:
                  result:
                    item: allium:pie_crust
                    amount: 1
                  shape:
                  - DDD
                  - D D
                  - DDD
                  keys:
                    D: allium:dough
                tomato_seeds_from_tomato:
                  result:
                    item: allium:tomato_seeds
                    amount: 2
                  ingredients:
                  - allium:tomato
                strawberry_seeds_from_strawberry:
                  result:
                    item: allium:strawberry_seeds
                    amount: 2
                  ingredients:
                  - allium:strawberry
                bake_apple_pie:
                  result:
                    item: allium:baked_apple_pie
                    amount: 1
                  ingredient: allium:cold_apple_pie
                  time: 180
                  experience: 1
                """);

        kitchen = new Kitchen(plugin, mock(ModelWorld.class), kitchenCfg);
        kitchen.registerRecipes(externalRecipes);
    }

    private ItemStack makeItem(String reference, int amount, String keyId) {
        Material mat = reference.startsWith("minecraft:")
                ? Material.valueOf(reference.substring("minecraft:".length()).toUpperCase(Locale.ROOT))
                : Material.PAPER;
        Map<NamespacedKey, Object> pdcMap = new HashMap<>();
        if (reference.startsWith("allium:")) {
            String id = reference.substring("allium:".length());
            pdcMap.put(KEY_ITEM, id);
            pdcMap.put(KEY_BEHAVIOR, "crops:" + id);
            pdcMap.put(KEY_SIGNATURE, "v2:" + keyId + ":valid-" + id);
        }
        return mockStack(mat, amount, pdcMap, new AtomicReference<>(reference));
    }

    private ItemStack mockStack(Material mat, int amount, Map<NamespacedKey, Object> pdcMap, AtomicReference<String> nameRef) {
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(pdc.get(any(NamespacedKey.class), any())).thenAnswer(inv -> pdcMap.get(inv.getArgument(0)));
        when(pdc.has(any(NamespacedKey.class), any())).thenAnswer(inv -> pdcMap.containsKey(inv.getArgument(0)));
        doAnswer(inv -> {
            pdcMap.put(inv.getArgument(0), inv.getArgument(2));
            return null;
        }).when(pdc).set(any(NamespacedKey.class), any(), any());

        ItemMeta meta = mock(ItemMeta.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(meta.getDisplayName()).thenAnswer(inv -> nameRef.get());
        when(meta.hasDisplayName()).thenAnswer(inv -> nameRef.get() != null);
        doAnswer(inv -> {
            nameRef.set(inv.getArgument(0));
            return null;
        }).when(meta).setDisplayName(any());

        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(mat);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.hasItemMeta()).thenReturn(true);
        when(stack.getItemMeta()).thenReturn(meta);
        when(stack.clone()).thenAnswer(inv -> mockStack(mat, amount, new HashMap<>(pdcMap), new AtomicReference<>(nameRef.get())));
        return stack;
    }

    @Test
    void craftingMatchesOldKeyCurrentKeyAndValidVariationsWhileRejectingLookalikesAndTampering() {
        Recipe singlePaperFallback = registeredRecipes.entrySet().stream()
                .filter(e -> e.getKey().getKey().startsWith("identity_craft_")
                        && registeredAllowedRefs.get(e.getKey()).contains("allium:strawberry"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();

        // 1. Old retired key k1 strawberry -> 2x strawberry_seeds
        ItemStack strawberryOldKey = makeItem("allium:strawberry", 1, "k1");
        ItemStack result = prepareCraft(singlePaperFallback, new ItemStack[]{strawberryOldKey, null, null, null});
        assertNotNull(result);
        assertEquals("allium:strawberry_seeds", plugin.reference(result));
        assertEquals(2, result.getAmount());

        // 2. Valid cosmetic variation (renamed old-key tomato) + current-key silver star + bowl -> tomato_sauce
        ItemStack tomatoRenamedOldKey = makeItem("allium:tomato", 1, "k1");
        tomatoRenamedOldKey.getItemMeta().setDisplayName("Heirloom Tomato");
        ItemStack tomatoSilverCurrentKey = makeItem("allium:tomato_silver_star", 1, "k2");
        ItemStack bowl = makeItem("minecraft:bowl", 1, "k2");
        Recipe sauceFallback = registeredRecipes.entrySet().stream()
                .filter(e -> e.getKey().getKey().startsWith("identity_craft_")
                        && registeredAllowedRefs.get(e.getKey()).contains("allium:tomato_silver_star"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
        ItemStack sauceResult = prepareCraft(sauceFallback, new ItemStack[]{
                tomatoRenamedOldKey, tomatoSilverCurrentKey, bowl, null, null, null, null, null, null
        });
        assertNotNull(sauceResult);
        assertEquals("allium:tomato_sauce", plugin.reference(sauceResult));

        // 3. Shaped recipe mixing k1 + k2 dough -> pie_crust
        ItemStack dOld = makeItem("allium:dough", 1, "k1");
        ItemStack dNew = makeItem("allium:dough", 1, "k2");
        Recipe crustFallback = registeredRecipes.entrySet().stream()
                .filter(e -> e.getKey().getKey().startsWith("identity_craft_")
                        && registeredAllowedRefs.get(e.getKey()).contains("allium:dough"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
        ItemStack crustResult = prepareCraft(crustFallback, new ItemStack[]{
                dOld, dNew, dOld,
                dNew, null, dNew,
                dOld, dNew, dOld
        });
        assertNotNull(crustResult);
        assertEquals("allium:pie_crust", plugin.reference(crustResult));

        // 4. Unsigned lookalike PAPER named "Strawberry" -> rejected (result cleared to null)
        ItemStack lookalike = mockStack(Material.PAPER, 1, new HashMap<>(), new AtomicReference<>("Strawberry"));
        assertNull(prepareCraft(singlePaperFallback, new ItemStack[]{lookalike, null, null, null}));

        // 5. Forged PDC without signature -> rejected
        Map<NamespacedKey, Object> forgedPdc = new HashMap<>();
        forgedPdc.put(KEY_ITEM, "strawberry");
        forgedPdc.put(KEY_BEHAVIOR, "crops:strawberry");
        ItemStack forged = mockStack(Material.PAPER, 1, forgedPdc, new AtomicReference<>("Strawberry"));
        assertNull(prepareCraft(singlePaperFallback, new ItemStack[]{forged, null, null, null}));

        // 6. Tampered signature -> rejected & CraftItemEvent cancelled
        ItemStack tampered = makeItem("allium:strawberry", 1, "k2");
        tampered.getItemMeta().getPersistentDataContainer().set(KEY_SIGNATURE, PersistentDataType.STRING, "v2:k2:tampered");
        assertNull(prepareCraft(singlePaperFallback, new ItemStack[]{tampered, null, null, null}));

        CraftingInventory craftInv = mock(CraftingInventory.class);
        when(craftInv.getMatrix()).thenReturn(new ItemStack[]{tampered, null, null, null});
        CraftItemEvent craftEvent = mock(CraftItemEvent.class);
        when(craftEvent.getRecipe()).thenReturn(singlePaperFallback);
        when(craftEvent.getInventory()).thenReturn(craftInv);
        kitchen.craftItem(craftEvent);
        verify(craftEvent).setCancelled(true);
    }

    @Test
    void cookingMatchesOldKeyCurrentKeyAndValidVariationsWhileRejectingLookalikesAndTampering() {
        Block furnaceBlock = mock(Block.class);
        when(furnaceBlock.getType()).thenReturn(Material.FURNACE);
        Furnace furnaceState = mock(Furnace.class);
        FurnaceInventory furnaceInv = mock(FurnaceInventory.class);
        when(furnaceBlock.getState()).thenReturn(furnaceState);
        when(furnaceState.getInventory()).thenReturn(furnaceInv);

        // 1. Old key k1 cold_apple_pie -> burns fuel, sets cook time 180, smelts to baked_apple_pie
        ItemStack coldPieOldKey = makeItem("allium:cold_apple_pie", 1, "k1");
        when(furnaceInv.getSmelting()).thenReturn(coldPieOldKey);
        FurnaceBurnEvent burnOld = new FurnaceBurnEvent(furnaceBlock, makeItem("minecraft:coal", 1, "k2"), 1600);
        kitchen.furnaceBurn(burnOld);
        assertFalse(burnOld.isCancelled());

        FurnaceStartSmeltEvent startOld = mock(FurnaceStartSmeltEvent.class);
        when(startOld.getBlock()).thenReturn(furnaceBlock);
        when(startOld.getSource()).thenReturn(coldPieOldKey);
        kitchen.furnaceStartSmelt(startOld);
        verify(startOld).setTotalCookTime(180);

        FurnaceSmeltEvent smeltOld = new FurnaceSmeltEvent(furnaceBlock, coldPieOldKey, makeItem("minecraft:barrier", 1, "k2"));
        kitchen.furnaceSmelt(smeltOld);
        assertFalse(smeltOld.isCancelled());
        assertEquals("allium:baked_apple_pie", plugin.reference(smeltOld.getResult()));

        // 2. Valid cosmetic variation (renamed cold_apple_pie) -> smelts to baked_apple_pie
        ItemStack coldPieRenamed = makeItem("allium:cold_apple_pie", 1, "k2");
        coldPieRenamed.getItemMeta().setDisplayName("Grandma's Apple Pie");
        FurnaceSmeltEvent smeltRenamed = new FurnaceSmeltEvent(furnaceBlock, coldPieRenamed, makeItem("minecraft:barrier", 1, "k2"));
        kitchen.furnaceSmelt(smeltRenamed);
        assertFalse(smeltRenamed.isCancelled());
        assertEquals("allium:baked_apple_pie", plugin.reference(smeltRenamed.getResult()));

        // 3. Unsigned lookalike PAPER -> burn cancelled AND smelt cancelled
        ItemStack lookalike = mockStack(Material.PAPER, 1, new HashMap<>(), new AtomicReference<>("Cold Apple Pie"));
        when(furnaceInv.getSmelting()).thenReturn(lookalike);
        FurnaceBurnEvent burnLookalike = new FurnaceBurnEvent(furnaceBlock, makeItem("minecraft:coal", 1, "k2"), 1600);
        kitchen.furnaceBurn(burnLookalike);
        assertTrue(burnLookalike.isCancelled());

        FurnaceSmeltEvent smeltLookalike = new FurnaceSmeltEvent(furnaceBlock, lookalike, makeItem("minecraft:barrier", 1, "k2"));
        kitchen.furnaceSmelt(smeltLookalike);
        assertTrue(smeltLookalike.isCancelled());

        // 4. Tampered cold_apple_pie (unknown key k99) -> burn cancelled AND smelt cancelled
        ItemStack tampered = makeItem("allium:cold_apple_pie", 1, "k99");
        when(furnaceInv.getSmelting()).thenReturn(tampered);
        FurnaceBurnEvent burnTampered = new FurnaceBurnEvent(furnaceBlock, makeItem("minecraft:coal", 1, "k2"), 1600);
        kitchen.furnaceBurn(burnTampered);
        assertTrue(burnTampered.isCancelled());

        FurnaceSmeltEvent smeltTampered = new FurnaceSmeltEvent(furnaceBlock, tampered, makeItem("minecraft:barrier", 1, "k2"));
        kitchen.furnaceSmelt(smeltTampered);
        assertTrue(smeltTampered.isCancelled());
    }

    private ItemStack prepareCraft(Recipe matchedBukkitRecipe, ItemStack[] matrix) {
        AtomicReference<ItemStack> resultRef = new AtomicReference<>(makeItem("minecraft:barrier", 1, "k2"));
        CraftingInventory inv = mock(CraftingInventory.class);
        when(inv.getRecipe()).thenReturn(matchedBukkitRecipe);
        when(inv.getMatrix()).thenReturn(matrix);
        when(inv.getResult()).thenAnswer(i -> resultRef.get());
        doAnswer(i -> {
            resultRef.set(i.getArgument(0));
            return null;
        }).when(inv).setResult(any());
        PrepareItemCraftEvent event = new PrepareItemCraftEvent(inv, mock(InventoryView.class), false);
        kitchen.prepareCraft(event);
        return resultRef.get();
    }
}
