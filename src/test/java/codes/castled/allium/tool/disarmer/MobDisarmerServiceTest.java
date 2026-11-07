package codes.castled.allium.tool.disarmer;

import codes.castled.allium.item.AlliumItemDefinition;
import codes.castled.allium.item.ItemHmacSigner;
import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.MockItemStacks;
import codes.castled.allium.message.Messages;
import codes.castled.allium.protection.AlliumProtectionCheckEvent;
import codes.castled.allium.protection.ProtectionDecision;
import codes.castled.allium.protection.ProtectionHook;
import codes.castled.allium.protection.ProtectionService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Cancellable;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SuppressWarnings("deprecation")
class MobDisarmerServiceTest {

    private static final DisarmerRules RULES = new DisarmerRules(3, 120_000L, 0.0D, 0.0D);

    private ItemService items;
    private AtomicLong clock;
    private AtomicLong nanos;
    private List<Messages> sent;
    private List<String> diagnostics;
    private List<ItemStack> drops;
    private AtomicReference<ProtectionDecision> hookDecision;
    private AtomicReference<Runnable> hookSideEffect;
    private AtomicBoolean cancelCheckEvent;
    private World world;
    private Player player;
    private AtomicReference<ItemStack> mainHand;
    private MobDisarmerService service;

    @BeforeEach
    void setUp() {
        items = new ItemService(MockItemStacks::create, ItemHmacSigner.randomEphemeral(), msg -> {});
        items.registerAlliumItem(new AlliumItemDefinition(
                "mob_disarmer", Material.GOLDEN_HOE, MobDisarmerService.BEHAVIOR_ID, false, 1002, "Mob Disarmer", Map.of(), true));
        clock = new AtomicLong(1_000_000L);
        nanos = new AtomicLong(0L);
        sent = new ArrayList<>();
        diagnostics = new ArrayList<>();
        drops = new ArrayList<>();
        hookDecision = new AtomicReference<>(ProtectionDecision.allow());
        hookSideEffect = new AtomicReference<>(() -> {});
        cancelCheckEvent = new AtomicBoolean(false);

        world = mock(World.class);
        when(world.dropItemNaturally(any(Location.class), any(ItemStack.class))).thenAnswer(inv -> {
            drops.add(inv.getArgument(1));
            return null;
        });

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
        when(player.hasPermission(MobDisarmerService.PERMISSION_USE)).thenReturn(true);
        when(player.getLocation()).thenAnswer(inv -> new Location(world, 0, 64, 0));

        service = newService(settings(RULES));
        mainHand.set(newDisarmer());
    }

    private MobDisarmerService newService(MobDisarmerSettings settings) {
        ProtectionHook hook = new ProtectionHook() {
            public String name() { return "TestClaims"; }
            public ProtectionDecision checkEntityInteraction(Player p, org.bukkit.entity.Entity target, org.bukkit.event.Event cause) {
                hookSideEffect.get().run();
                return hookDecision.get();
            }
        };
        PluginManager pluginManager = mock(PluginManager.class);
        doAnswer(inv -> {
            if (inv.getArgument(0) instanceof AlliumProtectionCheckEvent check && cancelCheckEvent.get()) {
                check.setCancelled(true);
            }
            return null;
        }).when(pluginManager).callEvent(any());
        ProtectionService protection = new ProtectionService(List.of(hook), pluginManager, diagnostics::add);
        RandomGenerator random = new RandomGenerator() {
            public long nextLong() { return 0L; }
            public double nextDouble() { return 0.5D; }
            public float nextFloat() { return 0.5F; }
        };
        return new MobDisarmerService(items, protection, (p, m, r) -> sent.add(m), diagnostics::add,
                clock::get, nanos::get, random, settings);
    }

    private static MobDisarmerSettings settings(DisarmerRules rules) {
        MobDisarmerSettings d = MobDisarmerSettings.defaults();
        return new MobDisarmerSettings(true, d.appearance(), List.of(), List.of(), 6.0D, rules, DisarmDropRule.SLOT_CHANCE, d.targetPolicy(),
                120_000L, 200L, 1_000L, "", List.of());
    }

    private ItemStack newDisarmer() {
        return items.createForBehavior(MobDisarmerService.BEHAVIOR_ID, 1).itemStack().orElseThrow();
    }

    private DisarmerCharges toolState() {
        ItemRef ref = items.identify(mainHand.get()).orElseThrow();
        assertEquals(Optional.of(MobDisarmerService.BEHAVIOR_ID), ref.behaviorId(), "tool must still verify");
        return DisarmerCharges.fromState(ref.stateData(), 3);
    }

    private MobDisarmerService.Outcome click(Object target, EquipmentSlot hand, TestEvent event) {
        return service.handleEntityInteraction(player, (org.bukkit.entity.Entity) target, hand, event, null);
    }

    private MobDisarmerService.Outcome click(Object target) {
        return click(target, EquipmentSlot.HAND, new TestEvent());
    }

    @Test
    void authorizedDisarmTakesEveryPieceBeforeDroppingSpendsOneChargeAndZeroesSlotChances() {
        Kit kit = zombie();
        kit.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 1.0F);
        kit.equip(EquipmentSlot.HEAD, Material.IRON_HELMET, 0.0F);
        kit.equip(EquipmentSlot.CHEST, Material.IRON_CHESTPLATE, 2.0F);
        kit.equip(EquipmentSlot.FEET, Material.IRON_BOOTS, 0.4F);
        String uuidBefore = pdcString(mainHand.get(), ItemService.KEY_INSTANCE_UUID);
        // Every drop happens only after all slots are already empty.
        when(world.dropItemNaturally(any(Location.class), any(ItemStack.class))).thenAnswer(inv -> {
            assertTrue(kit.items.isEmpty(), "piece dropped while equipment was still on the mob");
            drops.add(inv.getArgument(1));
            return null;
        });
        TestEvent event = new TestEvent();

        assertEquals(MobDisarmerService.Outcome.DISARMED, click(kit.mob, EquipmentSlot.HAND, event));

        assertTrue(event.cancelled);
        assertTrue(kit.items.isEmpty());
        assertEquals(List.of(Material.IRON_SWORD, Material.IRON_CHESTPLATE), drops.stream().map(ItemStack::getType).toList());
        for (EquipmentSlot slot : MobDisarmerService.DISARM_SLOTS) {
            assertEquals(0.0F, kit.chances.getOrDefault(slot, 0.0F), slot.name());
        }
        assertEquals(new DisarmerCharges(2, 0L, false, 1L), toolState());
        assertEquals(uuidBefore, pdcString(mainHand.get(), ItemService.KEY_INSTANCE_UUID));
        assertNotNull(pdcString(mainHand.get(), ItemService.KEY_SIGNATURE));
        assertTrue(kit.pickupBlocked.get() > clock.get());
        assertEquals(List.of(Messages.TOOL_DISARMER_DISARMED), sent);

        // Dying afterwards can only drop what is still equipped: nothing, at chance zero.
        assertTrue(kit.items.isEmpty());
    }

    @Test
    void emptyTargetsSpendNothingAndLetTheClickThrough() {
        Kit kit = zombie();
        TestEvent event = new TestEvent();
        assertEquals(MobDisarmerService.Outcome.NOTHING_TO_REMOVE, click(kit.mob, EquipmentSlot.HAND, event));
        assertFalse(event.cancelled);
        assertEquals(DisarmerCharges.full(3), toolState());
        assertTrue(drops.isEmpty());
        assertTrue(sent.isEmpty());
    }

    @Test
    void protectionDenialOrCancelledCheckEventSpendsNothingAndKeepsEquipment() {
        Kit kit = zombie();
        kit.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 1.0F);

        hookDecision.set(ProtectionDecision.deny("TestClaims", "Not your claim"));
        assertEquals(MobDisarmerService.Outcome.DENIED, click(kit.mob));
        assertEquals(List.of(Messages.TOOL_DISARMER_PROTECTED_REASON), sent);

        hookDecision.set(ProtectionDecision.allow());
        cancelCheckEvent.set(true);
        nanos.addAndGet(1_000_000_000L);
        assertEquals(MobDisarmerService.Outcome.DENIED, click(kit.mob));

        assertEquals(Material.IRON_SWORD, kit.items.get(EquipmentSlot.HAND).getType());
        assertEquals(1.0F, kit.chances.get(EquipmentSlot.HAND));
        assertEquals(DisarmerCharges.full(3), toolState());
        assertTrue(drops.isEmpty());
    }

    @Test
    void heldStackChangedDuringProtectionChecksCommitsNothing() {
        Kit kit = zombie();
        kit.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 1.0F);
        ItemStack original = mainHand.get();
        ItemStack other = newDisarmer();
        hookSideEffect.set(() -> mainHand.set(other));

        assertEquals(MobDisarmerService.Outcome.STALE, click(kit.mob));
        assertNotNull(kit.items.get(EquipmentSlot.HAND));
        assertEquals(DisarmerCharges.full(3), DisarmerCharges.fromState(items.identify(original).orElseThrow().stateData(), 3));
        assertEquals(DisarmerCharges.full(3), toolState());

        // Same physical tool, but its state changed while protection ran.
        mainHand.set(original);
        hookSideEffect.set(() -> items.updateState(original, Map.of("charges", "1")));
        nanos.addAndGet(1_000_000_000L);
        assertEquals(MobDisarmerService.Outcome.STALE, click(kit.mob));
        assertNotNull(kit.items.get(EquipmentSlot.HAND));
        assertEquals(1, toolState().charges());
    }

    @Test
    void offHandEventsNeverActAndDuplicateClicksInTheGuardWindowSpendNothing() {
        Kit kit = zombie();
        kit.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 1.0F);
        assertEquals(MobDisarmerService.Outcome.IGNORED, click(kit.mob, EquipmentSlot.OFF_HAND, new TestEvent()));
        assertNotNull(kit.items.get(EquipmentSlot.HAND));

        assertEquals(MobDisarmerService.Outcome.DISARMED, click(kit.mob));
        kit.equip(EquipmentSlot.HAND, Material.GOLDEN_SWORD, 1.0F);
        TestEvent duplicate = new TestEvent();
        assertEquals(MobDisarmerService.Outcome.GUARDED, click(kit.mob, EquipmentSlot.HAND, duplicate));
        assertTrue(duplicate.cancelled);
        assertEquals(Material.GOLDEN_SWORD, kit.items.get(EquipmentSlot.HAND).getType());
        assertEquals(2, toolState().charges());

        nanos.addAndGet(200_000_000L);
        assertEquals(MobDisarmerService.Outcome.DISARMED, click(kit.mob));
        assertEquals(1, toolState().charges());
    }

    @Test
    void lastChargeStartsTheRechargeRechargingRefusesWithoutStrippingAndRefillCompletes() {
        Kit kit = zombie();
        for (int expected = 2; expected >= 0; expected--) {
            kit.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 0.0F);
            nanos.addAndGet(1_000_000_000L);
            assertEquals(MobDisarmerService.Outcome.DISARMED, click(kit.mob));
            assertEquals(expected, toolState().charges());
        }
        assertEquals(clock.get() + 120_000L, toolState().rechargeAt());
        assertTrue(sent.contains(Messages.TOOL_DISARMER_LAST_CHARGE));

        kit.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 0.0F);
        nanos.addAndGet(1_000_000_000L);
        clock.addAndGet(119_999L);
        assertEquals(MobDisarmerService.Outcome.REFUSED, click(kit.mob));
        assertNotNull(kit.items.get(EquipmentSlot.HAND));
        assertEquals(0, toolState().charges());

        clock.addAndGet(1L);
        nanos.addAndGet(1_000_000_000L);
        assertEquals(MobDisarmerService.Outcome.DISARMED, click(kit.mob));
        assertEquals(new DisarmerCharges(2, 0L, false, 4L), toolState());
    }

    @Test
    void rejectedStateWriteRestoresEquipmentAndDropsNothing() {
        ItemService spy = spy(items);
        doReturn(false).when(spy).updateState(any(), any());
        items = spy;
        service = newService(settings(RULES));
        Kit kit = zombie();
        kit.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 0.7F);
        kit.equip(EquipmentSlot.LEGS, Material.IRON_LEGGINGS, 1.0F);

        assertEquals(MobDisarmerService.Outcome.WRITE_FAILED, click(kit.mob));
        assertEquals(Material.IRON_SWORD, kit.items.get(EquipmentSlot.HAND).getType());
        assertEquals(Material.IRON_LEGGINGS, kit.items.get(EquipmentSlot.LEGS).getType());
        assertEquals(0.7F, kit.chances.get(EquipmentSlot.HAND));
        assertEquals(1.0F, kit.chances.get(EquipmentSlot.LEGS));
        assertTrue(drops.isEmpty());
        assertEquals(DisarmerCharges.full(3), toolState());
    }

    @Test
    void alwaysRuleDropsEveryPieceAndVillagerGearIsGuaranteedUnderSlotChance() {
        MobDisarmerSettings d = settings(RULES);
        service = newService(new MobDisarmerSettings(true, d.appearance(), List.of(), List.of(), 6.0D, RULES,
                DisarmDropRule.ALWAYS, d.targetPolicy(), 120_000L, 200L, 1_000L, "", List.of()));
        Kit zombie = zombie();
        zombie.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 0.0F);
        zombie.equip(EquipmentSlot.FEET, Material.IRON_BOOTS, 0.4F);
        assertEquals(MobDisarmerService.Outcome.DISARMED, click(zombie.mob));
        assertEquals(List.of(Material.IRON_SWORD, Material.IRON_BOOTS), drops.stream().map(ItemStack::getType).toList());
        assertEquals(0.0F, zombie.chances.get(EquipmentSlot.FEET));

        // Default slot-chance: the 0.5 roll keeps a 0.4 zombie piece, but villager gear always drops.
        drops.clear();
        service = newService(settings(RULES));
        Kit sameRoll = zombie();
        sameRoll.equip(EquipmentSlot.FEET, Material.IRON_BOOTS, 0.4F);
        assertEquals(MobDisarmerService.Outcome.DISARMED, click(sameRoll.mob));
        assertTrue(drops.isEmpty());
        nanos.addAndGet(1_000_000_000L);
        Kit villager = kit(mock(org.bukkit.entity.Villager.class), EntityType.VILLAGER, new Location(world, 2, 64, 0));
        villager.equip(EquipmentSlot.HAND, Material.EMERALD, 0.0F);
        assertEquals(MobDisarmerService.Outcome.DISARMED, click(villager.mob));
        assertEquals(List.of(Material.EMERALD), drops.stream().map(ItemStack::getType).toList());
        assertEquals(new DisarmerCharges(0, clock.get() + 120_000L, false, 3L), toolState());
    }

    @Test
    void finishedRechargeRefreshesOnlyWhenAnEventAsksAndThenStaysPut() {
        Map<String, Integer> models = Map.of("full", 2577020, "two_thirds", 2577021, "one_third", 2577022,
                "empty", 2577023, "broken", 2577024);
        items.setModelSelector("mob_disarmer", state -> models.get(DisarmerVisualState.of(DisarmerCharges.fromState(state, 3), 3).key()));
        ItemStack tool = mainHand.get();
        assertTrue(items.updateState(tool, new DisarmerCharges(0, clock.get() + 1_000L, false, 3L).toState()));
        assertEquals(2577023, tool.getItemMeta().getCustomModelData());
        String uuid = pdcString(tool, ItemService.KEY_INSTANCE_UUID);

        assertTrue(service.refreshVisual(tool).isEmpty(), "still recharging: nothing changes");
        clock.addAndGet(1_000L);
        assertSame(tool, service.refreshVisual(tool).orElseThrow(), "the same stack is updated");
        assertEquals(2577020, tool.getItemMeta().getCustomModelData());
        assertEquals(new DisarmerCharges(3, 0L, false, 3L), toolState());
        assertEquals(uuid, pdcString(tool, ItemService.KEY_INSTANCE_UUID));
        assertTrue(service.refreshVisual(tool).isEmpty(), "a second refresh does nothing");

        tool.getItemMeta().setCustomModelData(1002);
        assertTrue(service.refreshVisual(tool).isPresent(), "a stale model is restamped without a state write");
        assertEquals(2577020, tool.getItemMeta().getCustomModelData());
        assertEquals(new DisarmerCharges(3, 0L, false, 3L), toolState());

        assertTrue(service.refreshVisual(null).isEmpty());
        assertTrue(service.refreshVisual(MockItemStacks.create(Material.GOLDEN_HOE)).isEmpty());
        ItemStack tampered = newDisarmer();
        assertTrue(items.updateState(tampered, new DisarmerCharges(0, clock.get() - 1L, false, 3L).toState()));
        tampered.getItemMeta().getPersistentDataContainer().set(new NamespacedKey("allium", "state_uses"), PersistentDataType.STRING, "0");
        assertTrue(service.refreshVisual(tampered).isEmpty(), "an edited signed stack is never refreshed");
        assertEquals(2577023, tampered.getItemMeta().getCustomModelData());

        ItemStack recharged = newDisarmer();
        assertTrue(items.updateState(recharged, new DisarmerCharges(0, clock.get() - 1L, false, 3L).toState()));
        PlayerInventory inventory = player.getInventory();
        ItemStack stone = MockItemStacks.create(Material.STONE);
        when(inventory.getContents()).thenReturn(new ItemStack[]{null, stone, recharged, tool});
        assertEquals(1, service.refreshInventory(player));
        verify(inventory).setItem(2, recharged);
        verify(inventory, never()).setItem(eq(3), any());
        assertEquals(2577020, recharged.getItemMeta().getCustomModelData());
    }

    @Test
    void bypassRecordsUseWithoutSpendingAndBreakingSpendsTheChargeOnce() {
        Kit kit = zombie();
        kit.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 0.0F);
        when(player.hasPermission(MobDisarmerService.PERMISSION_BYPASS)).thenReturn(true);
        assertEquals(MobDisarmerService.Outcome.DISARMED, click(kit.mob));
        assertEquals(new DisarmerCharges(3, 0L, false, 1L), toolState());

        when(player.hasPermission(MobDisarmerService.PERMISSION_BYPASS)).thenReturn(false);
        service = newService(settings(new DisarmerRules(3, 120_000L, 0.6D, 0.6D)));
        kit.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 0.0F);
        assertEquals(MobDisarmerService.Outcome.BROKE, click(kit.mob));
        assertEquals(new DisarmerCharges(0, 0L, true, 2L), toolState());
        kit.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 0.0F);
        nanos.addAndGet(1_000_000_000L);
        assertEquals(MobDisarmerService.Outcome.REFUSED, click(kit.mob));
        assertNotNull(kit.items.get(EquipmentSlot.HAND));
    }

    @Test
    void playersArmorStandsBossesDistantMobsAndStacksAreRefusedWithoutSpending() {
        Player other = mock(Player.class);
        when(other.getType()).thenReturn(EntityType.PLAYER);
        TestEvent playerEvent = new TestEvent();
        assertEquals(MobDisarmerService.Outcome.REFUSED, click(other, EquipmentSlot.HAND, playerEvent));
        assertTrue(playerEvent.cancelled);

        ArmorStand stand = mock(ArmorStand.class);
        when(stand.getType()).thenReturn(EntityType.ARMOR_STAND);
        assertEquals(MobDisarmerService.Outcome.IGNORED, click(stand));

        Kit dragon = kit(mock(EnderDragon.class), EntityType.ENDER_DRAGON, new Location(world, 1, 64, 0));
        dragon.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 1.0F);
        assertEquals(MobDisarmerService.Outcome.REFUSED, click(dragon.mob));
        assertNotNull(dragon.items.get(EquipmentSlot.HAND));

        Kit far = kit(mock(Zombie.class), EntityType.ZOMBIE, new Location(world, 7, 64, 0));
        far.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 1.0F);
        assertEquals(MobDisarmerService.Outcome.REFUSED, click(far.mob));

        Kit near = zombie();
        near.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 1.0F);
        mainHand.get().setAmount(2);
        assertEquals(MobDisarmerService.Outcome.REFUSED, click(near.mob));
        mainHand.get().setAmount(1);
        assertEquals(DisarmerCharges.full(3), toolState());
        assertNotNull(near.items.get(EquipmentSlot.HAND));
        assertTrue(drops.isEmpty());
    }

    @Test
    void legacyToolsImportChargesTimestampAndUsesBeforeActing() {
        Map<NamespacedKey, Object> legacy = new HashMap<>();
        ItemStack old = MockItemStacks.create(Material.DIAMOND_SWORD, 1, legacy, new AtomicReference<>(), new AtomicReference<>());
        legacy.put(MobDisarmerService.LEGACY_ITEM_ID, "mob_disarmer_0-3");
        legacy.put(MobDisarmerService.LEGACY_CHARGES, 0);
        legacy.put(MobDisarmerService.LEGACY_RECHARGE_AT, clock.get() + 5_000L);
        legacy.put(MobDisarmerService.LEGACY_USES, 7L);
        mainHand.set(old);

        service.handleAirUse(player, EquipmentSlot.HAND);
        assertNotSame(old, mainHand.get());
        assertEquals(new DisarmerCharges(0, clock.get() + 5_000L, false, 7L), toolState());
        assertEquals(List.of(Messages.TOOL_DISARMER_RECHARGING), sent);

        clock.addAndGet(5_000L);
        Kit kit = zombie();
        kit.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 0.0F);
        assertEquals(MobDisarmerService.Outcome.DISARMED, click(kit.mob));
        assertEquals(new DisarmerCharges(2, 0L, false, 8L), toolState());
    }

    @Test
    void rightClickingAirSettlesAFinishedRechargeAndRateLimitsOnlyTheMessage() {
        items.updateState(mainHand.get(), new DisarmerCharges(0, clock.get() + 10L, false, 3L).toState());
        service.handleAirUse(player, EquipmentSlot.HAND);
        assertEquals(0, toolState().charges());
        clock.addAndGet(10L);
        service.handleAirUse(player, EquipmentSlot.HAND);
        assertEquals(new DisarmerCharges(3, 0L, false, 3L), toolState());
        assertEquals(List.of(Messages.TOOL_DISARMER_RECHARGING), sent);
        service.handleAirUse(player, EquipmentSlot.OFF_HAND);
        clock.addAndGet(1_000L);
        service.handleAirUse(player, EquipmentSlot.HAND);
        assertEquals(List.of(Messages.TOOL_DISARMER_RECHARGING, Messages.TOOL_DISARMER_STATUS), sent);
    }

    private Kit zombie() {
        return kit(mock(Zombie.class), EntityType.ZOMBIE, new Location(world, 2, 64, 0));
    }

    private Kit kit(Mob mob, EntityType type, Location at) {
        Kit kit = new Kit(mob);
        when(mob.getType()).thenReturn(type);
        when(mob.isValid()).thenReturn(true);
        when(mob.getWorld()).thenReturn(world);
        when(mob.getLocation()).thenAnswer(inv -> at.clone());
        EntityEquipment equipment = mock(EntityEquipment.class);
        when(mob.getEquipment()).thenReturn(equipment);
        when(equipment.getItem(any(EquipmentSlot.class))).thenAnswer(inv -> kit.items.get((EquipmentSlot) inv.getArgument(0)));
        doAnswer(inv -> {
            EquipmentSlot slot = inv.getArgument(0);
            ItemStack stack = inv.getArgument(1);
            if (stack == null) kit.items.remove(slot); else kit.items.put(slot, stack);
            return null;
        }).when(equipment).setItem(any(EquipmentSlot.class), any());
        chance(equipment, kit, EquipmentSlot.HAND);
        chance(equipment, kit, EquipmentSlot.OFF_HAND);
        chance(equipment, kit, EquipmentSlot.HEAD);
        chance(equipment, kit, EquipmentSlot.CHEST);
        chance(equipment, kit, EquipmentSlot.LEGS);
        chance(equipment, kit, EquipmentSlot.FEET);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        doAnswer(inv -> {
            kit.pickupBlocked.set(inv.getArgument(2));
            return null;
        }).when(pdc).set(any(NamespacedKey.class), any(), any());
        when(mob.getPersistentDataContainer()).thenReturn(pdc);
        return kit;
    }

    private static void chance(EntityEquipment equipment, Kit kit, EquipmentSlot slot) {
        java.util.function.Supplier<Float> get = () -> kit.chances.getOrDefault(slot, 0.0F);
        switch (slot) {
            case HAND -> {
                when(equipment.getItemInMainHandDropChance()).thenAnswer(inv -> get.get());
                doAnswer(inv -> kit.chances.put(slot, inv.getArgument(0))).when(equipment).setItemInMainHandDropChance(anyFloat());
            }
            case OFF_HAND -> {
                when(equipment.getItemInOffHandDropChance()).thenAnswer(inv -> get.get());
                doAnswer(inv -> kit.chances.put(slot, inv.getArgument(0))).when(equipment).setItemInOffHandDropChance(anyFloat());
            }
            case HEAD -> {
                when(equipment.getHelmetDropChance()).thenAnswer(inv -> get.get());
                doAnswer(inv -> kit.chances.put(slot, inv.getArgument(0))).when(equipment).setHelmetDropChance(anyFloat());
            }
            case CHEST -> {
                when(equipment.getChestplateDropChance()).thenAnswer(inv -> get.get());
                doAnswer(inv -> kit.chances.put(slot, inv.getArgument(0))).when(equipment).setChestplateDropChance(anyFloat());
            }
            case LEGS -> {
                when(equipment.getLeggingsDropChance()).thenAnswer(inv -> get.get());
                doAnswer(inv -> kit.chances.put(slot, inv.getArgument(0))).when(equipment).setLeggingsDropChance(anyFloat());
            }
            case FEET -> {
                when(equipment.getBootsDropChance()).thenAnswer(inv -> get.get());
                doAnswer(inv -> kit.chances.put(slot, inv.getArgument(0))).when(equipment).setBootsDropChance(anyFloat());
            }
            default -> { }
        }
    }

    private static String pdcString(ItemStack stack, NamespacedKey key) {
        return stack.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private static final class Kit {
        final Mob mob;
        final Map<EquipmentSlot, ItemStack> items = new EnumMap<>(EquipmentSlot.class);
        final Map<EquipmentSlot, Float> chances = new EnumMap<>(EquipmentSlot.class);
        final AtomicLong pickupBlocked = new AtomicLong(0L);

        Kit(Mob mob) {
            this.mob = mob;
        }

        void equip(EquipmentSlot slot, Material material, float chance) {
            items.put(slot, MockItemStacks.create(material));
            chances.put(slot, chance);
        }
    }

    private static final class TestEvent implements Cancellable {
        boolean cancelled;

        public boolean isCancelled() {
            return cancelled;
        }

        public void setCancelled(boolean cancel) {
            cancelled = cancel;
        }
    }
}
