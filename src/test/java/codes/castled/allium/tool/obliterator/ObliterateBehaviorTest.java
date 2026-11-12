package codes.castled.allium.tool.obliterator;

import codes.castled.allium.item.AlliumItemDefinition;
import codes.castled.allium.item.ItemHmacSigner;
import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.MockItemStacks;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.message.LangMessage;
import codes.castled.allium.protection.ProtectionDecision;
import codes.castled.allium.protection.ProtectionHook;
import codes.castled.allium.protection.ProtectionService;
import codes.castled.allium.tool.definition.ToolDefinition;
import codes.castled.allium.tool.disarmer.DisarmerCharges;
import codes.castled.allium.tool.disarmer.DisarmerRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;

@SuppressWarnings("deprecation")
class ObliterateBehaviorTest {

    private static final String ID = ObliterateBehavior.PHANTOM_OBLITERATOR_ID;

    private ItemService items;
    private World world;
    private Player player;
    private AtomicReference<ItemStack> mainHand;
    private final List<Entity> nearby = new ArrayList<>();
    private final List<String> sent = new ArrayList<>();
    private final List<String> diagnostics = new ArrayList<>();
    private final AtomicReference<ProtectionDecision> decision = new AtomicReference<>(ProtectionDecision.allow());
    private final AtomicReference<Runnable> protectionSideEffect = new AtomicReference<>(() -> { });
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final AtomicLong nanos = new AtomicLong(0L);
    private ObliterateBehavior behavior;

    @BeforeEach
    void setUp() {
        items = new ItemService(MockItemStacks::create, ItemHmacSigner.randomEphemeral(), msg -> { });
        items.registerAlliumItem(new AlliumItemDefinition("phantom_obliterator", Material.PHANTOM_MEMBRANE, ID, true, 2577030,
                "Phantom Obliterator", Map.of("charges", "3"), true));
        world = mock(World.class);
        when(world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble())).thenAnswer(inv -> new ArrayList<>(nearby));
        mainHand = new AtomicReference<>();
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getItemInMainHand()).thenAnswer(inv -> mainHand.get());
        doAnswer(inv -> {
            mainHand.set(inv.getArgument(0));
            return null;
        }).when(inventory).setItemInMainHand(any());
        when(inventory.getHeldItemSlot()).thenReturn(0);
        player = mock(Player.class);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("GateBot");
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.hasPermission("allium.phantomobliterator.use")).thenReturn(true);
        when(player.getLocation()).thenAnswer(inv -> new Location(world, 0, 100, 0));
        behavior = newBehavior(16.0D, 64, true);
        mainHand.set(newTool());
    }

    private ObliterateBehavior newBehavior(double radius, int maxTargets, boolean importLegacy) {
        ProtectionHook hook = new ProtectionHook() {
            public String name() { return "TestClaims"; }
            public ProtectionDecision checkEntityInteraction(Player p, Entity target, org.bukkit.event.Event cause) {
                protectionSideEffect.get().run();
                return decision.get();
            }
        };
        ProtectionService protection = new ProtectionService(List.of(hook), null, diagnostics::add);
        ObliterateParameters parameters = new ObliterateParameters(new DisarmerRules(3, 120_000L, 0.0D, 0.0D), List.of(), radius,
                Set.of("minecraft:phantom"), true, true, maxTargets, true, 200L, 0L, importLegacy);
        ObliterateBehaviorType type = new ObliterateBehaviorType();
        ToolDefinition definition = new ToolDefinition("tools/phantom_obliterator.yml", ID, "Phantom Obliterator", ObliterateBehaviorType.ID,
                true, null, ProviderKey.allium("phantom_obliterator"), List.of(),
                Map.of("use", "allium.phantomobliterator.use", "bypass", "allium.phantomobliterator.bypass"),
                type.messageSlots(), type.soundSlots(), type.particleSlots(), parameters);
        RandomGenerator random = new RandomGenerator() {
            public long nextLong() { return 0L; }
            public double nextDouble() { return 0.5D; }
        };
        return new ObliterateBehavior(items, protection, (p, message, r) -> sent.add(message.key()), diagnostics::add,
                clock::get, random, List.of(new ObliterateTool(definition, nanos::get)));
    }

    private ItemStack newTool() {
        return items.createForBehavior(ID, 1).itemStack().orElseThrow();
    }

    private DisarmerCharges state() {
        ItemRef ref = items.identify(mainHand.get()).orElseThrow();
        assertEquals(Optional.of(ID), ref.behaviorId(), "the tool still verifies");
        return DisarmerCharges.fromState(ref.stateData(), 3);
    }

    /** A mob that dies to the player's damage unless {@code spared}. */
    private LivingEntity mob(Class<? extends LivingEntity> kind, EntityType type, double x, String name, boolean spared) {
        LivingEntity mob = mock(kind);
        AtomicBoolean dead = new AtomicBoolean(false);
        when(mob.getType()).thenReturn(type);
        when(mob.getLocation()).thenAnswer(inv -> new Location(world, x, 100, 0));
        when(mob.getWorld()).thenReturn(world);
        when(mob.getCustomName()).thenReturn(name);
        when(mob.isValid()).thenAnswer(inv -> !dead.get());
        when(mob.isDead()).thenAnswer(inv -> dead.get());
        when(mob.getHealth()).thenAnswer(inv -> dead.get() ? 0.0D : 20.0D);
        when(mob.getHeight()).thenReturn(0.5D);
        doAnswer(inv -> {
            if (!spared) dead.set(true);
            return null;
        }).when(mob).damage(anyDouble(), any(Entity.class));
        nearby.add(mob);
        return mob;
    }

    private LivingEntity phantom(double x) {
        return mob(Phantom.class, EntityType.PHANTOM, x, null, false);
    }

    @Test
    void killsExactlyTheUnnamedPhantomsInRangeAndSpendsOneChargeAsThePlayer() {
        LivingEntity near = phantom(4);
        LivingEntity far = phantom(10);
        LivingEntity named = mob(Phantom.class, EntityType.PHANTOM, 5, "Pet", false);
        LivingEntity beyond = phantom(20);
        LivingEntity zombie = mob(Zombie.class, EntityType.ZOMBIE, 3, null, false);
        String uuid = pdc(mainHand.get(), ItemService.KEY_INSTANCE_UUID);
        String signature = pdc(mainHand.get(), ItemService.KEY_SIGNATURE);

        assertEquals(ObliterateBehavior.Outcome.OBLITERATED, behavior.handleUse(player, EquipmentSlot.HAND));
        verify(near).damage(anyDouble(), eq(player));
        verify(far).damage(anyDouble(), eq(player));
        verify(named, never()).damage(anyDouble(), any(Entity.class));
        verify(beyond, never()).damage(anyDouble(), any(Entity.class));
        verify(zombie, never()).damage(anyDouble(), any(Entity.class));
        assertEquals(new DisarmerCharges(2, 0L, false, 1L), state());
        assertEquals(uuid, pdc(mainHand.get(), ItemService.KEY_INSTANCE_UUID));
        assertNotEquals(signature, pdc(mainHand.get(), ItemService.KEY_SIGNATURE), "re-signed");
        assertEquals(List.of("tool.phantom-obliterator.obliterated"), sent);
    }

    @Test
    void anEmptyScanOrProtectedTargetsSpendNothing() {
        assertEquals(ObliterateBehavior.Outcome.NOTHING_IN_RANGE, behavior.handleUse(player, EquipmentSlot.HAND));
        assertEquals(DisarmerCharges.full(3), state());
        assertEquals(List.of("tool.phantom-obliterator.nothing-in-range"), sent);

        LivingEntity guarded = phantom(4);
        decision.set(ProtectionDecision.deny("TestClaims", null));
        nanos.addAndGet(1_000_000_000L);
        assertEquals(ObliterateBehavior.Outcome.NOTHING_IN_RANGE, behavior.handleUse(player, EquipmentSlot.HAND));
        verify(guarded, never()).damage(anyDouble(), any(Entity.class));
        assertEquals(DisarmerCharges.full(3), state());
        assertEquals("tool.phantom-obliterator.protected", sent.get(sent.size() - 1));
    }

    @Test
    void whenEveryTargetIsSparedByADamageListenerTheChargeIsRefunded() {
        LivingEntity spared = mob(Phantom.class, EntityType.PHANTOM, 4, null, true);
        assertEquals(ObliterateBehavior.Outcome.REFUNDED, behavior.handleUse(player, EquipmentSlot.HAND));
        verify(spared).damage(anyDouble(), eq(player));
        assertEquals(DisarmerCharges.full(3), state(), "the spent charge was written back");
    }

    @Test
    void whenStackMovesOrIsGoneDuringDamageRefundFailsAndCreditsNothing() {
        LivingEntity spared = mob(Phantom.class, EntityType.PHANTOM, 4, null, true);
        doAnswer(inv -> {
            // Player moved or swapped the held item during the damage event
            mainHand.set(MockItemStacks.create(Material.DIAMOND_SWORD, 1, new HashMap<>(), new AtomicReference<>(), new AtomicReference<>()));
            return null;
        }).when(spared).damage(anyDouble(), any(Entity.class));

        assertEquals(ObliterateBehavior.Outcome.REFUNDED, behavior.handleUse(player, EquipmentSlot.HAND));
        assertTrue(diagnostics.stream().anyMatch(d -> d.contains("refund failed: item with UUID") && d.contains("has moved or is gone")),
                "failed refund was logged");
        assertEquals(Material.DIAMOND_SWORD, mainHand.get().getType(), "main hand was not overwritten with the tool");
    }

    @Test
    void aRejectedWriteKillsNothing() {
        LivingEntity target = phantom(4);
        ItemService spy = spy(items);
        doReturn(false).when(spy).updateState(any(), any());
        items = spy;
        behavior = newBehavior(16.0D, 64, true);
        assertEquals(ObliterateBehavior.Outcome.WRITE_FAILED, behavior.handleUse(player, EquipmentSlot.HAND));
        verify(target, never()).damage(anyDouble(), any(Entity.class));
        assertEquals(DisarmerCharges.full(3), state());
        assertTrue(diagnostics.stream().anyMatch(d -> d.contains("state write was rejected")));
    }

    @Test
    void theHeldStackIsRecheckedAfterProtectionRuns() {
        LivingEntity target = phantom(4);
        ItemStack original = mainHand.get();
        protectionSideEffect.set(() -> mainHand.set(newTool()));
        assertEquals(ObliterateBehavior.Outcome.STALE, behavior.handleUse(player, EquipmentSlot.HAND));
        verify(target, never()).damage(anyDouble(), any(Entity.class));
        mainHand.set(original);
        assertEquals(DisarmerCharges.full(3), state(), "the checked tool was not spent");
    }

    @Test
    void lookAlikesAndTheOffHandDoNothing() {
        LivingEntity target = phantom(4);
        AtomicReference<Integer> model = new AtomicReference<>(2577030);
        mainHand.set(MockItemStacks.create(Material.PHANTOM_MEMBRANE, 1, new HashMap<>(), new AtomicReference<>("Phantom Obliterator"), model));
        assertEquals(ObliterateBehavior.Outcome.IGNORED, behavior.handleUse(player, EquipmentSlot.HAND), "model data and a name are not identity");
        mainHand.set(newTool());
        assertEquals(ObliterateBehavior.Outcome.IGNORED, behavior.handleUse(player, EquipmentSlot.OFF_HAND));
        verify(target, never()).damage(anyDouble(), any(Entity.class));
    }

    @Test
    void theNearestTargetsAreTakenUpToTheCap() {
        behavior = newBehavior(16.0D, 2, true);
        LivingEntity third = phantom(9);
        LivingEntity first = phantom(2);
        LivingEntity second = phantom(5);
        assertEquals(ObliterateBehavior.Outcome.OBLITERATED, behavior.handleUse(player, EquipmentSlot.HAND));
        verify(first).damage(anyDouble(), eq(player));
        verify(second).damage(anyDouble(), eq(player));
        verify(third, never()).damage(anyDouble(), any(Entity.class));
    }

    @Test
    void creativeForgedLegacyToolsAreNotAdoptedWhenWindowIsClosed() {
        Map<NamespacedKey, Object> legacy = new HashMap<>();
        ItemStack forged = MockItemStacks.create(Material.NETHERITE_SWORD, 1, legacy, new AtomicReference<>("§5§lPhantom Obliterator"), new AtomicReference<>(1011));
        legacy.put(ObliterateBehavior.LEGACY_ITEM_ID, "phantom_obliterator_0-3");
        legacy.put(ObliterateBehavior.LEGACY_NEXO_ID, "phantom_obliterator_0-3");
        legacy.put(ObliterateBehavior.LEGACY_CHARGES, 3);
        legacy.put(ObliterateBehavior.LEGACY_RECHARGE_AT, 0L);
        legacy.put(ObliterateBehavior.LEGACY_USES, 0L);
        mainHand.set(forged);

        assertEquals(ObliterateBehavior.Outcome.IGNORED, behavior.handleUse(player, EquipmentSlot.HAND), "adoption window is closed by default");
        assertFalse(legacy.containsKey(ItemService.KEY_BEHAVIOR_ID), "no behavior stamped");
        assertFalse(legacy.containsKey(ItemService.KEY_SIGNATURE), "no signature minted");
    }

    @Test
    void legacyToolsImportInPlaceWithTheirChargesTimestampAndUses() {
        items.enablePrivilegedAdoptionWindow(60_000L);
        Map<NamespacedKey, Object> legacy = new HashMap<>();
        ItemStack old = MockItemStacks.create(Material.NETHERITE_SWORD, 1, legacy, new AtomicReference<>("§5§lPhantom Obliterator"), new AtomicReference<>(1011));
        legacy.put(ObliterateBehavior.LEGACY_ITEM_ID, "phantom_obliterator_0-3");
        legacy.put(ObliterateBehavior.LEGACY_NEXO_ID, "phantom_obliterator_0-3");
        legacy.put(ObliterateBehavior.LEGACY_CHARGES, 0);
        legacy.put(ObliterateBehavior.LEGACY_RECHARGE_AT, clock.get() + 5_000L);
        legacy.put(ObliterateBehavior.LEGACY_USES, 7L);
        mainHand.set(old);
        assertEquals(ObliterateBehavior.Outcome.REFUSED, behavior.handleUse(player, EquipmentSlot.HAND), "still recharging");
        assertSame(old, mainHand.get(), "adopted in place, not replaced");
        assertEquals(new DisarmerCharges(0, clock.get() + 5_000L, false, 7L), state());
        assertEquals("§5§lPhantom Obliterator", old.getItemMeta().getDisplayName(), "its name is kept");

        Map<NamespacedKey, Object> halfTagged = new HashMap<>();
        ItemStack fake = MockItemStacks.create(Material.NETHERITE_SWORD, 1, halfTagged, new AtomicReference<>(), new AtomicReference<>(1008));
        halfTagged.put(ObliterateBehavior.LEGACY_ITEM_ID, "phantom_obliterator");
        mainHand.set(fake);
        assertEquals(ObliterateBehavior.Outcome.IGNORED, behavior.handleUse(player, EquipmentSlot.HAND),
                "without a matching nexo:id it is never imported");
        assertFalse(halfTagged.containsKey(ItemService.KEY_BEHAVIOR_ID));
    }

    private static String pdc(ItemStack stack, NamespacedKey key) {
        return stack.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
