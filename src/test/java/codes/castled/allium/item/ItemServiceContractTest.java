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
    void stateMutationCannotLaunderInvalidIdentityOrAlterRejectedMetadata() {
        for (String invalid : List.of("missing", "forged", "unknown", "tampered", "missing-uuid", "legacy")) {
            ItemStack stack = itemService.create(ItemRef.parse("allium:phantom_obliterator")).itemStack().orElseThrow();
            PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
            switch (invalid) {
                case "missing" -> pdc.remove(ItemService.KEY_SIGNATURE);
                case "forged" -> pdc.set(ItemService.KEY_SIGNATURE, PersistentDataType.STRING, "v2:k1:" + "00".repeat(32));
                case "unknown" -> pdc.set(ItemService.KEY_SIGNATURE, PersistentDataType.STRING, "v2:foreign:" + "00".repeat(32));
                case "tampered" -> pdc.set(new NamespacedKey("allium", "state_tier"), PersistentDataType.STRING, "999");
                case "missing-uuid" -> pdc.remove(ItemService.KEY_INSTANCE_UUID);
                case "legacy" -> pdc.set(ItemService.KEY_SIGNATURE, PersistentDataType.STRING, "k1:" + "00".repeat(32));
            }
            String beforeSignature = pdc.get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING);
            String beforeUuid = pdc.get(ItemService.KEY_INSTANCE_UUID, PersistentDataType.STRING);
            clearInvocations(stack);
            itemService.setState(stack, "charges", "999");
            assertEquals(Optional.of("3"), itemService.getState(stack, "charges"), invalid);
            assertEquals(beforeSignature, pdc.get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING), invalid);
            assertEquals(beforeUuid, pdc.get(ItemService.KEY_INSTANCE_UUID, PersistentDataType.STRING), invalid);
            verify(stack, never()).setItemMeta(any());
            assertTrue(itemService.identifyBehavior(stack).isEmpty(), invalid);
        }
    }

    @Test
    void validRetiredSignatureCanMutateAndResignsWithActiveKey() {
        ItemStack stack = itemService.create(ItemRef.parse("allium:phantom_obliterator")).itemStack().orElseThrow();
        byte[] nextSecret = new byte[32];
        new java.security.SecureRandom().nextBytes(nextSecret);
        ItemService rotated = new ItemService(ItemServiceContractTest::createMockBackedStack,
                signer.rotateKey("k2", nextSecret), diagnostics::add);
        rotated.registerPrivilegedBehavior("allium:phantom_obliterator");
        assertEquals(Optional.of("allium:phantom_obliterator"), rotated.identifyBehavior(stack));
        rotated.setState(stack, "charges", "2");
        assertEquals(Optional.of("2"), rotated.getState(stack, "charges"));
        assertTrue(stack.getItemMeta().getPersistentDataContainer().get(ItemService.KEY_SIGNATURE,
                PersistentDataType.STRING).startsWith("v2:k2:"));
        assertEquals(Optional.of("allium:phantom_obliterator"), rotated.identifyBehavior(stack));
    }

    @Test
    void threeReadyProvidersRouteIndependentlyAndResolveConversionTagsDeterministically() {
        Map<String, AtomicReference<ProviderStatus>> states = new HashMap<>();
        for (String provider : List.of("nexo", "craftengine", "oraxen")) {
            AtomicReference<ProviderStatus> status = new AtomicReference<>(ProviderStatus.READY);
            states.put(provider, status);
            NamespacedKey nativeKey = new NamespacedKey(provider, "native_id");
            itemService.registerProvider(new ItemProviderAdapter() {
                public String providerName() { return provider; }
                public ProviderStatus status() { return status.get(); }
                public boolean hasAuthoritativeNativeIdCheck() { return true; }
                public ResolutionResult createItem(String id) {
                    ItemStack stack = createMockBackedStack(Material.PAPER);
                    stack.getItemMeta().getPersistentDataContainer().set(nativeKey, PersistentDataType.STRING, id);
                    return ResolutionResult.resolved(ProviderKey.of(provider,id), stack);
                }
                public Optional<String> identifyItemId(ItemStack stack) {
                    return Optional.ofNullable(stack.getItemMeta().getPersistentDataContainer().get(nativeKey, PersistentDataType.STRING));
                }
            });
        }
        for (String provider : states.keySet()) {
            ProviderKey appearance = ProviderKey.of(provider, provider.equals("craftengine") ? "sf_ores:silver_ingot" : "MixedCase");
            ItemRef ref = new ItemRef(appearance, "allium:phantom_obliterator", 1, Map.of("charges","2"));
            ItemStack stack = itemService.create(ref).itemStack().orElseThrow();
            String signature = stack.getItemMeta().getPersistentDataContainer().get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING);
            assertEquals(ref, itemService.identify(stack).orElseThrow());
            assertFalse(itemService.matches(stack, ItemRef.parse("paper")));
            // An old converter can leave another provider's ID on this physical stack.
            for (String other : states.keySet()) if (!other.equals(provider)) {
                stack.getItemMeta().getPersistentDataContainer().set(new NamespacedKey(other,"native_id"), PersistentDataType.STRING,"OtherItem");
            }
            assertEquals(ref, itemService.identify(stack).orElseThrow(), "native confirmation of stamp wins, never hash-map iteration");
            assertEquals(signature, stack.getItemMeta().getPersistentDataContainer().get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING));
            // Remove only Allium's appearance stamp: multiple native tags now have no reliable identity.
            stack.getItemMeta().getPersistentDataContainer().remove(ItemService.KEY_PROVIDER);
            stack.getItemMeta().getPersistentDataContainer().remove(ItemService.KEY_ITEM_ID);
            assertTrue(itemService.identify(stack).isEmpty());
            states.get(provider).set(ProviderStatus.LOADING);
            assertEquals(ResolutionStatus.PROVIDER_LOADING, itemService.create(ref).status());
            states.get(provider).set(ProviderStatus.READY);
        }
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
        assertTrue(originalSig.startsWith("v2:k1:"));

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
    void signedNonPrivilegedBehaviorKeepsMacThroughStateWritesAndSwapsButNeverMintsPrivilegedBehavior() {
        itemService.registerProvider(new ItemProviderAdapter() {
            public String providerName() { return "nexo"; }
            public ProviderStatus status() { return ProviderStatus.READY; }
            public ResolutionResult createItem(String id) {
                return ResolutionResult.resolved(ProviderKey.of("nexo", id), createMockBackedStack(Material.IRON_SHOVEL));
            }
            public Optional<String> identifyItemId(ItemStack stack) { return Optional.empty(); }
        });
        itemService.registerAlliumItem(new AlliumItemDefinition(
                "signed_tool", Material.GOLDEN_HOE, "allium:signed_tool", false, null, null, Map.of(), true));
        assertTrue(itemService.isSignedBehavior("allium:signed_tool"));
        assertFalse(itemService.isPrivilegedBehavior("allium:signed_tool"));

        ItemStack tool = itemService.create(ItemRef.parse("allium:signed_tool")).itemStack().orElseThrow();
        PersistentDataContainer pdc = tool.getItemMeta().getPersistentDataContainer();
        String uuid = pdc.get(ItemService.KEY_INSTANCE_UUID, PersistentDataType.STRING);
        assertNotNull(uuid);
        assertNotNull(pdc.get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING));

        // One verified write applies every value and re-signs once.
        assertTrue(itemService.updateState(tool, Map.of("charges", "2", "Recharge_At", "0")));
        assertEquals(Optional.of("2"), itemService.getState(tool, "charges"));
        assertEquals(Optional.of("0"), itemService.getState(tool, "recharge_at"));
        assertEquals(Optional.of("allium:signed_tool"), itemService.identifyBehavior(tool));

        // Appearance swaps keep state, instance UUID and a valid MAC, even for a behavior that
        // does not require one: a second service without the signed registration still re-signs.
        ItemService unaware = new ItemService(ItemServiceContractTest::createMockBackedStack, signer, diagnostics::add);
        unaware.registerProvider(new ItemProviderAdapter() {
            public String providerName() { return "nexo"; }
            public ProviderStatus status() { return ProviderStatus.READY; }
            public ResolutionResult createItem(String id) {
                return ResolutionResult.resolved(ProviderKey.of("nexo", id), createMockBackedStack(Material.IRON_SHOVEL));
            }
            public Optional<String> identifyItemId(ItemStack stack) { return Optional.empty(); }
        });
        ItemStack swapped = unaware.swapOrRefreshAppearance(tool, ProviderKey.of("nexo", "tool_2-3")).itemStack().orElseThrow();
        PersistentDataContainer swappedPdc = swapped.getItemMeta().getPersistentDataContainer();
        assertEquals(uuid, swappedPdc.get(ItemService.KEY_INSTANCE_UUID, PersistentDataType.STRING));
        assertNotNull(swappedPdc.get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING));
        ItemRef swappedRef = itemService.identify(swapped).orElseThrow();
        assertEquals(ProviderKey.of("nexo", "tool_2-3"), swappedRef.appearance());
        assertEquals(Optional.of("allium:signed_tool"), swappedRef.behaviorId());
        assertEquals("2", swappedRef.stateData().get("charges"));

        // Edited signed state is rejected; a rejected write changes nothing.
        swappedPdc.set(new NamespacedKey("allium", "state_charges"), PersistentDataType.STRING, "99");
        assertTrue(itemService.identifyBehavior(swapped).isEmpty());
        assertFalse(itemService.updateState(swapped, Map.of("charges", "1")));
        assertEquals(Optional.of("99"), itemService.getState(swapped, "charges"));
        assertEquals(ResolutionStatus.REJECTED,
                itemService.swapOrRefreshAppearance(swapped, ProviderKey.of("nexo", "tool")).status());

        // Removing the MAC leaves an unsigned stack of a non-privileged behavior, which is accepted.
        swappedPdc.remove(ItemService.KEY_SIGNATURE);
        assertEquals(Optional.of("allium:signed_tool"), itemService.identifyBehavior(swapped));

        // A refresh never attaches privileged behavior the source stack lacked.
        itemService.bindAppearanceToBehavior(ProviderKey.of("nexo", "handcuffs"), "allium:handcuffs");
        ItemStack plain = itemService.create(ItemRef.parse("minecraft:iron_shovel")).itemStack().orElseThrow();
        ResolutionResult minted = itemService.swapOrRefreshAppearance(plain, ProviderKey.of("nexo", "handcuffs"));
        assertEquals(ResolutionStatus.RESOLVED, minted.status());
        assertTrue(itemService.identifyBehavior(minted.itemStack().orElseThrow()).isEmpty());
        ItemStack unbound = itemService.create(new ItemRef(ProviderKey.of("nexo", "loose"), null, 1, Map.of())).itemStack().orElseThrow();
        assertEquals(ResolutionStatus.REJECTED,
                itemService.swapOrRefreshAppearance(unbound, ProviderKey.of("nexo", "handcuffs")).status());
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
        return MockItemStacks.create(material);
    }
}
