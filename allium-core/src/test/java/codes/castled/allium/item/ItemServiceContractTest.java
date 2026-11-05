package codes.castled.allium.item;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ItemServiceContractTest {

    private ItemService itemService;

    @BeforeEach
    void setUp() {
        itemService = new ItemService(ItemServiceContractTest::createMockBackedStack);
        itemService.registerAlliumItem(new AlliumItemDefinition(
                "mob_disarmer",
                Material.GOLDEN_HOE,
                "allium:mob_disarmer",
                1002,
                "§6Mob Disarmer",
                Map.of("charges", "3")
        ));
    }

    @Test
    void namespaceParsingSplitsAtFirstColonAndRejectsRetiredPrefixes() {
        // Bare name -> strictly vanilla
        assertEquals(new ProviderKey("minecraft", "stone"), ProviderKey.parse("stone"));
        assertEquals(new ProviderKey("minecraft", "mob_disarmer"), ProviderKey.parse("mob_disarmer"));

        // Explicit namespaces
        assertEquals(new ProviderKey("minecraft", "diamond_sword"), ProviderKey.parse("minecraft:diamond_sword"));
        assertEquals(new ProviderKey("allium", "mob_disarmer"), ProviderKey.parse("allium:mob_disarmer"));
        assertEquals(new ProviderKey("nexo", "mob_disarmer"), ProviderKey.parse("nexo:mob_disarmer"));
        assertEquals(new ProviderKey("oraxen", "item_renamer"), ProviderKey.parse("oraxen:item_renamer"));

        // First-colon split preserves CraftEngine's internal namespace
        ProviderKey ceKey = ProviderKey.parse("craftengine:sf_ores:silver_ingot");
        assertEquals("craftengine", ceKey.provider());
        assertEquals("sf_ores:silver_ingot", ceKey.id());
        assertEquals("craftengine:sf_ores:silver_ingot", ceKey.asString());

        // CraftEngine without internal namespace is rejected
        assertThrows(IllegalArgumentException.class, () -> ProviderKey.parse("craftengine:blaster"));

        // Retired prefixes ci:, custom:, vanilla: are rejected
        assertThrows(IllegalArgumentException.class, () -> ProviderKey.parse("ci:mob_disarmer"));
        assertThrows(IllegalArgumentException.class, () -> ProviderKey.parse("custom:mob_disarmer"));
        assertThrows(IllegalArgumentException.class, () -> ProviderKey.parse("vanilla:stone"));

        // ce: is rejected in strict mode, but converts with warning when deprecated mode is enabled
        assertThrows(IllegalArgumentException.class, () -> ProviderKey.parse("ce:sf_ores:silver_ingot"));
        List<String> warnings = new ArrayList<>();
        ProviderKey convertedCe = ProviderKey.parse("ce:sf_ores:silver_ingot", true, warnings::add);
        assertEquals(new ProviderKey("craftengine", "sf_ores:silver_ingot"), convertedCe);
        assertEquals(1, warnings.size());
    }

    @Test
    void bareNamesResolveToVanillaOnlyAndNeverFallbackToAlliumOrAir() {
        // Bare "mob_disarmer" is treated as minecraft:mob_disarmer, NOT allium:mob_disarmer
        ItemRef bareDisarmer = ItemRef.parse("mob_disarmer");
        assertEquals(ProviderKey.minecraft("mob_disarmer"), bareDisarmer.appearance());
        assertTrue(bareDisarmer.behaviorId().isEmpty());

        ResolutionResult bareResult = itemService.create(bareDisarmer);
        assertEquals(ResolutionStatus.UNKNOWN_ID, bareResult.status());
        assertTrue(bareResult.itemStack().isEmpty());

        // AIR is never produced as an item
        ResolutionResult airResult = itemService.create(ItemRef.parse("air"));
        assertEquals(ResolutionStatus.UNKNOWN_ID, airResult.status());
        assertTrue(airResult.itemStack().isEmpty());

        ResolutionResult mcAirResult = itemService.create(ItemRef.parse("minecraft:air"));
        assertEquals(ResolutionStatus.UNKNOWN_ID, mcAirResult.status());
        assertTrue(mcAirResult.itemStack().isEmpty());

        // Unknown Allium item never falls back to carrier or AIR
        ResolutionResult unknownAllium = itemService.create(ItemRef.parse("allium:nonexistent_tool"));
        assertEquals(ResolutionStatus.UNKNOWN_ID, unknownAllium.status());
        assertTrue(unknownAllium.itemStack().isEmpty());
    }

    @Test
    void providerReadinessStatesAreDistinctFromUnknownId() {
        ItemRef nexoRef = ItemRef.parse("nexo:mob_disarmer");

        // 1. Unregistered provider -> PROVIDER_ABSENT
        assertEquals(ResolutionStatus.PROVIDER_ABSENT, itemService.create(nexoRef).status());

        // 2. Loading provider (e.g. Nexo async startup/reload) -> PROVIDER_LOADING, never UNKNOWN_ID
        AtomicReference<ProviderStatus> nexoState = new AtomicReference<>(ProviderStatus.LOADING);
        itemService.registerProvider(new ItemProviderAdapter() {
            @Override
            public String providerName() {
                return "nexo";
            }

            @Override
            public ProviderStatus status() {
                return nexoState.get();
            }

            @Override
            public ResolutionResult createItem(String id) {
                if ("mob_disarmer".equals(id)) {
                    return ResolutionResult.resolved(ProviderKey.of("nexo", id), createMockBackedStack(Material.GOLDEN_HOE));
                }
                return ResolutionResult.unknownId(ProviderKey.of("nexo", id), "Not in Nexo registry");
            }

            @Override
            public Optional<String> identifyItemId(ItemStack stack) {
                return Optional.empty();
            }
        });

        assertEquals(ResolutionStatus.PROVIDER_LOADING, itemService.create(nexoRef).status());

        // 3. Failed provider -> PROVIDER_FAILED
        nexoState.set(ProviderStatus.FAILED);
        assertEquals(ResolutionStatus.PROVIDER_FAILED, itemService.create(nexoRef).status());

        // 4. Ready provider -> RESOLVED for known ID, UNKNOWN_ID for missing ID
        nexoState.set(ProviderStatus.READY);
        assertEquals(ResolutionStatus.RESOLVED, itemService.create(nexoRef).status());
        assertEquals(ResolutionStatus.UNKNOWN_ID, itemService.create(ItemRef.parse("nexo:missing_item")).status());
    }

    @Test
    void behaviorIdentityIsSharedAcrossAlliumAndProviderAppearancesAndPreservesState() {
        itemService.registerProvider(new ItemProviderAdapter() {
            @Override
            public String providerName() {
                return "nexo";
            }

            @Override
            public ProviderStatus status() {
                return ProviderStatus.READY;
            }

            @Override
            public ResolutionResult createItem(String id) {
                if ("mob_disarmer".equals(id)) {
                    return ResolutionResult.resolved(ProviderKey.of("nexo", id), createMockBackedStack(Material.GOLDEN_HOE));
                }
                return ResolutionResult.unknownId(ProviderKey.of("nexo", id), "Unknown Nexo item");
            }

            @Override
            public Optional<String> identifyItemId(ItemStack stack) {
                return Optional.empty();
            }
        });

        // Bind nexo:mob_disarmer to the canonical allium:mob_disarmer behavior
        ProviderKey nexoAppearance = ProviderKey.of("nexo", "mob_disarmer");
        itemService.bindAppearanceToBehavior(nexoAppearance, "allium:mob_disarmer");

        ItemStack alliumStack = itemService.create(itemService.parseRef("allium:mob_disarmer")).itemStack().orElseThrow();
        ItemStack nexoStack = itemService.create(itemService.parseRef("nexo:mob_disarmer")).itemStack().orElseThrow();

        // Both stacks share the exact same behavior identity and default charges ("3")
        assertEquals(Optional.of("allium:mob_disarmer"), itemService.identifyBehavior(alliumStack));
        assertEquals(Optional.of("allium:mob_disarmer"), itemService.identifyBehavior(nexoStack));
        assertTrue(itemService.matchesBehavior(alliumStack, "allium:mob_disarmer"));
        assertTrue(itemService.matchesBehavior(nexoStack, "allium:mob_disarmer"));
        assertEquals(Optional.of("3"), itemService.getState(alliumStack, "charges"));
        assertEquals(Optional.of("3"), itemService.getState(nexoStack, "charges"));

        // Yet their appearance identities remain distinct
        assertEquals(ProviderKey.allium("mob_disarmer"), itemService.identify(alliumStack).orElseThrow().appearance());
        assertEquals(nexoAppearance, itemService.identify(nexoStack).orElseThrow().appearance());

        // Decrement charges on alliumStack and swap appearance to nexo:mob_disarmer -> state is preserved
        itemService.setState(alliumStack, "charges", "1");
        ItemStack swapped = itemService.swapOrRefreshAppearance(alliumStack, nexoAppearance).itemStack().orElseThrow();
        assertEquals(nexoAppearance, itemService.identify(swapped).orElseThrow().appearance());
        assertEquals(Optional.of("allium:mob_disarmer"), itemService.identifyBehavior(swapped));
        assertEquals(Optional.of("1"), itemService.getState(swapped, "charges"));
    }

    @Test
    void strictProviderCarrierNonMatchingAndAntiSpoofing() {
        ItemStack disarmerStack = itemService.create(itemService.parseRef("allium:mob_disarmer")).itemStack().orElseThrow();
        assertEquals(Material.GOLDEN_HOE, disarmerStack.getType());

        // 1. Custom item NEVER matches as its vanilla carrier material
        assertFalse(itemService.matches(disarmerStack, ItemRef.parse("golden_hoe")));
        assertFalse(itemService.matches(disarmerStack, ItemRef.parse("minecraft:golden_hoe")));
        assertTrue(itemService.matches(disarmerStack, ItemRef.parse("allium:mob_disarmer")));

        // 2. Vanilla carrier item NEVER matches as the custom item, even with spoofed name & CustomModelData
        ItemStack spoofedVanilla = itemService.create(ItemRef.parse("minecraft:golden_hoe")).itemStack().orElseThrow();
        ItemMeta meta = spoofedVanilla.getItemMeta();
        meta.setDisplayName("§6Mob Disarmer");
        meta.setCustomModelData(1002);
        spoofedVanilla.setItemMeta(meta);

        assertFalse(itemService.matches(spoofedVanilla, ItemRef.parse("allium:mob_disarmer")));
        assertFalse(itemService.matchesBehavior(spoofedVanilla, "allium:mob_disarmer"));
        assertTrue(itemService.identifyBehavior(spoofedVanilla).isEmpty());
        assertTrue(itemService.matches(spoofedVanilla, ItemRef.parse("minecraft:golden_hoe")));
    }

    @Test
    void cloneAndSerializationRoundTripPreserveMetadata() {
        ItemStack original = itemService.create(
                itemService.parseRef("allium:mob_disarmer").withState("charges", "2")
        ).itemStack().orElseThrow();

        ItemStack cloned = itemService.cloneItem(original, 4);
        assertEquals(4, cloned.getAmount());
        ItemRef identifiedClone = itemService.identify(cloned).orElseThrow();
        assertEquals(ProviderKey.allium("mob_disarmer"), identifiedClone.appearance());
        assertEquals(Optional.of("allium:mob_disarmer"), identifiedClone.behaviorId());
        assertEquals("2", identifiedClone.state("charges").orElseThrow());

        // Map serialization round-trip for both Allium and multi-colon CraftEngine references
        Map<String, Object> serializedDisarmer = identifiedClone.serialize();
        ItemRef deserializedDisarmer = ItemRef.deserialize(serializedDisarmer);
        assertEquals(identifiedClone, deserializedDisarmer);

        ItemRef ceRef = new ItemRef(
                ProviderKey.parse("craftengine:sf_ores:silver_ingot"),
                null,
                16,
                Map.of("purity", "99")
        );
        ItemRef roundTrippedCe = ItemRef.deserialize(ceRef.serialize());
        assertEquals(ceRef, roundTrippedCe);
        assertEquals("craftengine", roundTrippedCe.appearance().provider());
        assertEquals("sf_ores:silver_ingot", roundTrippedCe.appearance().id());
    }

    /**
     * Creates an {@link ItemStack} backed by an in-memory {@link ItemMeta} and {@link PersistentDataContainer}
     * so PDC stamping, cloning, and identification work without a live CraftServer.
     */
    private static ItemStack createMockBackedStack(Material material) {
        return createMockBackedStack(material, 1, new HashMap<>(), new AtomicReference<>(null), new AtomicReference<>(null));
    }

    private static ItemStack createMockBackedStack(
            Material material,
            int initialAmount,
            Map<NamespacedKey, String> pdcMap,
            AtomicReference<String> displayNameRef,
            AtomicReference<Integer> cmdRef
    ) {
        AtomicReference<Integer> amountRef = new AtomicReference<>(initialAmount);

        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        doAnswer(inv -> {
            pdcMap.put(inv.getArgument(0), inv.getArgument(2));
            return null;
        }).when(pdc).set(any(NamespacedKey.class), any(), any());
        when(pdc.get(any(NamespacedKey.class), any())).thenAnswer(inv -> pdcMap.get(inv.getArgument(0)));
        when(pdc.has(any(NamespacedKey.class), any())).thenAnswer(inv -> pdcMap.containsKey(inv.getArgument(0)));
        when(pdc.getKeys()).thenAnswer(inv -> pdcMap.keySet());

        ItemMeta meta = mock(ItemMeta.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        doAnswer(inv -> {
            displayNameRef.set(inv.getArgument(0));
            return null;
        }).when(meta).setDisplayName(any());
        when(meta.getDisplayName()).thenAnswer(inv -> displayNameRef.get());
        doAnswer(inv -> {
            cmdRef.set(inv.getArgument(0));
            return null;
        }).when(meta).setCustomModelData(any());
        when(meta.getCustomModelData()).thenAnswer(inv -> cmdRef.get() != null ? cmdRef.get() : 0);

        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenAnswer(inv -> amountRef.get());
        doAnswer(inv -> {
            amountRef.set(inv.getArgument(0));
            return null;
        }).when(stack).setAmount(anyInt());
        when(stack.getItemMeta()).thenReturn(meta);
        when(stack.setItemMeta(any())).thenReturn(true);
        when(stack.clone()).thenAnswer(inv -> createMockBackedStack(
                material,
                amountRef.get(),
                new HashMap<>(pdcMap),
                new AtomicReference<>(displayNameRef.get()),
                new AtomicReference<>(cmdRef.get())
        ));
        return stack;
    }
}
