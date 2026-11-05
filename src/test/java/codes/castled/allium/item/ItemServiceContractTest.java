package codes.castled.allium.item;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("deprecation")
class ItemServiceContractTest {

    private ItemService itemService;
    private ItemHmacSigner signer;
    private List<String> diagnostics;

    @BeforeEach
    void setUp() {
        ProviderKey.resetDeprecationWarnings();
        signer = ItemHmacSigner.randomEphemeral();
        diagnostics = new ArrayList<>();
        itemService = new ItemService(ItemServiceContractTest::createMockBackedStack, signer, diagnostics::add);

        // Privileged tools: handcuffs and phantom_obliterator
        itemService.registerAlliumItem(new AlliumItemDefinition(
                "handcuffs",
                Material.IRON_SHOVEL,
                "allium:handcuffs",
                true,
                1001,
                "§fHandcuffs",
                Map.of()
        ));
        itemService.registerAlliumItem(new AlliumItemDefinition(
                "phantom_obliterator",
                Material.BLAZE_ROD,
                "allium:phantom_obliterator",
                true,
                1003,
                "§bPhantom Obliterator",
                Map.of("charges", "3")
        ));

        // Unprivileged tool: mob_disarmer
        itemService.registerAlliumItem(new AlliumItemDefinition(
                "mob_disarmer",
                Material.GOLDEN_HOE,
                "allium:mob_disarmer",
                false,
                1002,
                "§6Mob Disarmer",
                Map.of("charges", "3")
        ));
    }

    @Test
    void signedBehaviorIdentityVerifiesValidCloneAndAppearanceSwap() {
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
                if ("handcuffs".equals(id)) {
                    return ResolutionResult.resolved(ProviderKey.of("nexo", id), createMockBackedStack(Material.IRON_SHOVEL));
                }
                return ResolutionResult.unknownId(ProviderKey.of("nexo", id), "Not found");
            }

            @Override
            public Optional<String> identifyItemId(ItemStack stack) {
                return Optional.empty();
            }
        });
        ProviderKey nexoHandcuffs = ProviderKey.of("nexo", "handcuffs");
        itemService.bindAppearanceToBehavior(nexoHandcuffs, "allium:handcuffs");

        // 1. Privileged handcuffs created by ItemService carry schema_version, instance_uuid, and signature
        ItemStack cuffs = itemService.create(itemService.parseRef("allium:handcuffs")).itemStack().orElseThrow();
        PersistentDataContainer pdc = cuffs.getItemMeta().getPersistentDataContainer();
        assertEquals(ItemRef.CURRENT_SCHEMA_VERSION, pdc.get(ItemService.KEY_SCHEMA_VERSION, PersistentDataType.INTEGER));
        String originalInstanceUuid = pdc.get(ItemService.KEY_INSTANCE_UUID, PersistentDataType.STRING);
        String originalSig = pdc.get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING);
        assertNotNull(originalInstanceUuid);
        assertNotNull(originalSig);
        assertTrue(originalSig.startsWith("k1:"));

        assertEquals(Optional.of("allium:handcuffs"), itemService.identifyBehavior(cuffs));
        assertTrue(itemService.matchesBehavior(cuffs, "allium:handcuffs"));

        // 2. Signature survives cloneItem
        ItemStack cloned = itemService.cloneItem(cuffs, 2);
        assertEquals(Optional.of("allium:handcuffs"), itemService.identifyBehavior(cloned));
        assertTrue(itemService.matches(cloned, itemService.parseRef("allium:handcuffs")));

        // 3. Signature survives swapOrRefreshAppearance to nexo:handcuffs (preserving instanceUuid and re-signing)
        ItemStack swapped = itemService.swapOrRefreshAppearance(cuffs, nexoHandcuffs).itemStack().orElseThrow();
        assertEquals(nexoHandcuffs, itemService.identify(swapped).orElseThrow().appearance());
        assertEquals(Optional.of("allium:handcuffs"), itemService.identifyBehavior(swapped));
        assertEquals(
                originalInstanceUuid,
                swapped.getItemMeta().getPersistentDataContainer().get(ItemService.KEY_INSTANCE_UUID, PersistentDataType.STRING)
        );
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void privilegedStateMutationReSignsWhileDirectStateTamperingFailsVerification() {
        // Phantom Obliterator is privileged and carries default state charges=3
        ItemStack obliterator = itemService.create(itemService.parseRef("allium:phantom_obliterator")).itemStack().orElseThrow();
        assertEquals(Optional.of("allium:phantom_obliterator"), itemService.identifyBehavior(obliterator));
        assertEquals(Optional.of("3"), itemService.getState(obliterator, "charges"));

        // 1. Mutating state through ItemService#setState re-signs the HMAC and remains valid
        itemService.setState(obliterator, "charges", "2");
        assertEquals(Optional.of("allium:phantom_obliterator"), itemService.identifyBehavior(obliterator));
        assertEquals(Optional.of("2"), itemService.getState(obliterator, "charges"));
        assertTrue(diagnostics.isEmpty());

        // 2. Editing allium:state_charges directly in PDC without re-signing fails verification!
        obliterator.getItemMeta().getPersistentDataContainer().set(
                new NamespacedKey("allium", "state_charges"),
                PersistentDataType.STRING,
                "999"
        );
        ItemRef tamperedStateRef = itemService.identify(obliterator).orElseThrow();
        assertEquals(ProviderKey.minecraft("blaze_rod"), tamperedStateRef.appearance());
        assertTrue(tamperedStateRef.behaviorId().isEmpty());
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).contains("invalid/tampered signature or state"));
    }

    @Test
    void unknownKeyIdLogsLoudDiagnosticAndRejectsItem() {
        ItemHmacSigner otherNetworkKey = ItemHmacSigner.randomEphemeral("prod-2025");
        ItemStack foreignSigned = createMockBackedStack(Material.IRON_SHOVEL);
        PersistentDataContainer pdc = foreignSigned.getItemMeta().getPersistentDataContainer();
        String uuid = UUID.randomUUID().toString();
        pdc.set(ItemService.KEY_PROVIDER, PersistentDataType.STRING, "allium");
        pdc.set(ItemService.KEY_ITEM_ID, PersistentDataType.STRING, "handcuffs");
        pdc.set(ItemService.KEY_BEHAVIOR_ID, PersistentDataType.STRING, "allium:handcuffs");
        pdc.set(ItemService.KEY_INSTANCE_UUID, PersistentDataType.STRING, uuid);
        pdc.set(ItemService.KEY_SIGNATURE, PersistentDataType.STRING,
                otherNetworkKey.sign("allium", "handcuffs", "allium:handcuffs", uuid));

        ItemRef identified = itemService.identify(foreignSigned).orElseThrow();
        assertEquals(ProviderKey.minecraft("iron_shovel"), identified.appearance());
        assertTrue(identified.behaviorId().isEmpty());
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).contains("CRITICAL HMAC KEY MISMATCH"));
        assertTrue(diagnostics.get(0).contains("prod-2025"));
    }

    @Test
    void providerAndPdcDisagreementLogsExplicitDiagnosticAndFollowsDocumentedRule() {
        NamespacedKey nativeNexoKey = new NamespacedKey("nexo", "id");
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
                ItemStack stack = createMockBackedStack(Material.GOLDEN_HOE);
                stack.getItemMeta().getPersistentDataContainer().set(nativeNexoKey, PersistentDataType.STRING, id);
                return ResolutionResult.resolved(ProviderKey.of("nexo", id), stack);
            }

            @Override
            public Optional<String> identifyItemId(ItemStack stack) {
                String id = stack.getItemMeta().getPersistentDataContainer().get(nativeNexoKey, PersistentDataType.STRING);
                return Optional.ofNullable(id);
            }

            @Override
            public boolean hasAuthoritativeNativeIdCheck() {
                return true;
            }
        });

        // Case 1: Provider native API says "nexo:real_disarmer" while PDC stamp says "nexo:stale_disarmer"
        ItemStack stack = createMockBackedStack(Material.GOLDEN_HOE);
        PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
        pdc.set(nativeNexoKey, PersistentDataType.STRING, "real_disarmer");
        pdc.set(ItemService.KEY_PROVIDER, PersistentDataType.STRING, "nexo");
        pdc.set(ItemService.KEY_ITEM_ID, PersistentDataType.STRING, "stale_disarmer");

        ItemRef resolved = itemService.identify(stack).orElseThrow();
        assertEquals(ProviderKey.of("nexo", "real_disarmer"), resolved.appearance());
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).contains("Identity mismatch on item stack"));

        // Case 2: PDC claims "nexo:fake_item" on a plain item that Nexo's native API does not recognize
        ItemStack forgedProviderStack = createMockBackedStack(Material.GOLDEN_HOE);
        PersistentDataContainer forgedPdc = forgedProviderStack.getItemMeta().getPersistentDataContainer();
        forgedPdc.set(ItemService.KEY_PROVIDER, PersistentDataType.STRING, "nexo");
        forgedPdc.set(ItemService.KEY_ITEM_ID, PersistentDataType.STRING, "fake_item");

        ItemRef forgedResolved = itemService.identify(forgedProviderStack).orElseThrow();
        assertEquals(ProviderKey.minecraft("golden_hoe"), forgedResolved.appearance());
        assertEquals(2, diagnostics.size());
    }

    @Test
    void privilegedBehaviorRejectsMissingForgedAndTamperedSignaturesWithBoundedDiagnostics() {
        // 1. Missing signature (creative client or /give component injection setting PDC without HMAC)
        ItemStack missingSig = createMockBackedStack(Material.IRON_SHOVEL);
        PersistentDataContainer missingPdc = missingSig.getItemMeta().getPersistentDataContainer();
        missingPdc.set(ItemService.KEY_PROVIDER, PersistentDataType.STRING, "allium");
        missingPdc.set(ItemService.KEY_ITEM_ID, PersistentDataType.STRING, "handcuffs");
        missingPdc.set(ItemService.KEY_BEHAVIOR_ID, PersistentDataType.STRING, "allium:handcuffs");

        // Resolves as plain minecraft:iron_shovel with no behaviorId
        ItemRef missingRef = itemService.identify(missingSig).orElseThrow();
        assertEquals(ProviderKey.minecraft("iron_shovel"), missingRef.appearance());
        assertTrue(missingRef.behaviorId().isEmpty());
        assertFalse(itemService.matchesBehavior(missingSig, "allium:handcuffs"));
        // Bounded diagnostic: calling identify again does not duplicate the warning
        itemService.identify(missingSig);
        assertEquals(1, diagnostics.size());

        // 2. Forged signature (signed with a different secret key using same keyId k1)
        ItemHmacSigner attackerSigner = ItemHmacSigner.randomEphemeral();
        ItemStack forgedSig = createMockBackedStack(Material.BLAZE_ROD);
        PersistentDataContainer forgedPdc = forgedSig.getItemMeta().getPersistentDataContainer();
        String forgedUuid = UUID.randomUUID().toString();
        forgedPdc.set(ItemService.KEY_PROVIDER, PersistentDataType.STRING, "allium");
        forgedPdc.set(ItemService.KEY_ITEM_ID, PersistentDataType.STRING, "phantom_obliterator");
        forgedPdc.set(ItemService.KEY_BEHAVIOR_ID, PersistentDataType.STRING, "allium:phantom_obliterator");
        forgedPdc.set(ItemService.KEY_INSTANCE_UUID, PersistentDataType.STRING, forgedUuid);
        forgedPdc.set(ItemService.KEY_SIGNATURE, PersistentDataType.STRING,
                attackerSigner.sign("allium", "phantom_obliterator", "allium:phantom_obliterator", forgedUuid));

        ItemRef forgedRef = itemService.identify(forgedSig).orElseThrow();
        assertEquals(ProviderKey.minecraft("blaze_rod"), forgedRef.appearance());
        assertTrue(forgedRef.behaviorId().isEmpty());
        assertFalse(itemService.matchesBehavior(forgedSig, "allium:phantom_obliterator"));
        assertEquals(2, diagnostics.size());

        // 3. Tampered PDC (validly signed handcuffs modified in PDC to claim phantom_obliterator)
        ItemStack tampered = itemService.create(itemService.parseRef("allium:handcuffs")).itemStack().orElseThrow();
        tampered.getItemMeta().getPersistentDataContainer()
                .set(ItemService.KEY_BEHAVIOR_ID, PersistentDataType.STRING, "allium:phantom_obliterator");

        ItemRef tamperedRef = itemService.identify(tampered).orElseThrow();
        assertEquals(ProviderKey.minecraft("iron_shovel"), tamperedRef.appearance());
        assertTrue(tamperedRef.behaviorId().isEmpty());
        assertEquals(3, diagnostics.size());

        // 4. Unprivileged item (mob_disarmer) skips signing and resolves normally
        ItemStack unprivileged = itemService.create(itemService.parseRef("allium:mob_disarmer")).itemStack().orElseThrow();
        assertNull(unprivileged.getItemMeta().getPersistentDataContainer()
                .get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING));
        assertEquals(Optional.of("allium:mob_disarmer"), itemService.identifyBehavior(unprivileged));
    }

    @Test
    void legacyCustomItemIdAndAlliumCardsTagsAreReadOnlyForImporter() {
        ItemStack legacyStack = createMockBackedStack(Material.PAPER);
        PersistentDataContainer pdc = legacyStack.getItemMeta().getPersistentDataContainer();
        pdc.set(new NamespacedKey("allium", "custom_item_id"), PersistentDataType.STRING, "handcuffs");
        pdc.set(new NamespacedKey("alliumcards", "card_id"), PersistentDataType.STRING, "inferno_dragon");
        pdc.set(new NamespacedKey("alliumcards", "tier"), PersistentDataType.STRING, "legendary");

        LegacyItemMetadata legacy = itemService.readLegacyMetadata(legacyStack);
        assertTrue(legacy.hasLegacyData());
        assertEquals(Optional.of("handcuffs"), legacy.legacyCustomItemId());
        assertEquals("inferno_dragon", legacy.alliumCardsTags().get("card_id"));
        assertEquals("legendary", legacy.alliumCardsTags().get("tier"));

        // Legacy tags are read-only for the importer and NEVER grant runtime privileged behavior prior to import
        ItemRef runtimeIdentified = itemService.identify(legacyStack).orElseThrow();
        assertEquals(ProviderKey.minecraft("paper"), runtimeIdentified.appearance());
        assertTrue(runtimeIdentified.behaviorId().isEmpty());
    }

    @Test
    void bareNamesResolveToVanillaOnlyAndNeverFallbackToAlliumOrAir() {
        ItemRef bareDisarmer = ItemRef.parse("mob_disarmer");
        assertEquals(ProviderKey.minecraft("mob_disarmer"), bareDisarmer.appearance());
        assertTrue(bareDisarmer.behaviorId().isEmpty());

        assertEquals(ResolutionStatus.UNKNOWN_ID, itemService.create(bareDisarmer).status());
        assertEquals(ResolutionStatus.UNKNOWN_ID, itemService.create(ItemRef.parse("air")).status());
        assertEquals(ResolutionStatus.UNKNOWN_ID, itemService.create(ItemRef.parse("minecraft:air")).status());
        assertEquals(ResolutionStatus.UNKNOWN_ID, itemService.create(ItemRef.parse("allium:nonexistent_tool")).status());
    }

    @Test
    void strictProviderCarrierNonMatchingAndAntiSpoofing() {
        ItemStack disarmerStack = itemService.create(itemService.parseRef("allium:mob_disarmer")).itemStack().orElseThrow();
        assertEquals(Material.GOLDEN_HOE, disarmerStack.getType());

        assertFalse(itemService.matches(disarmerStack, ItemRef.parse("golden_hoe")));
        assertFalse(itemService.matches(disarmerStack, ItemRef.parse("minecraft:golden_hoe")));
        assertTrue(itemService.matches(disarmerStack, ItemRef.parse("allium:mob_disarmer")));

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

    private static ItemStack createMockBackedStack(Material material) {
        return createMockBackedStack(material, 1, new HashMap<>(), new AtomicReference<>(null), new AtomicReference<>(null));
    }

    private static ItemStack createMockBackedStack(
            Material material,
            int initialAmount,
            Map<NamespacedKey, Object> pdcMap,
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
