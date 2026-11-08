package codes.castled.allium.tool.disarmer;

import codes.castled.allium.item.AlliumItemDefinition;
import codes.castled.allium.item.ItemHmacSigner;
import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.MockItemStacks;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.message.Messages;
import codes.castled.allium.protection.AlliumProtectionCheckEvent;
import codes.castled.allium.protection.ProtectionDecision;
import codes.castled.allium.protection.ProtectionHook;
import codes.castled.allium.protection.ProtectionService;
import codes.castled.allium.tool.definition.ToolDefinition;
import codes.castled.allium.tool.definition.ToolSound;
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
import java.util.Collection;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SuppressWarnings("deprecation")
class DisarmBehaviorTest {

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
    private DisarmBehavior service;

    @BeforeEach
    void setUp() {
        items = new ItemService(MockItemStacks::create, ItemHmacSigner.randomEphemeral(), msg -> {});
        items.registerAlliumItem(new AlliumItemDefinition(
                "mob_disarmer", Material.GOLDEN_HOE, DisarmBehavior.MOB_DISARMER_ID, false, 1002, "Mob Disarmer", Map.of(), true));
        items.registerSignedBehavior(DisarmBehavior.MOB_DISARMER_ID);
        items.setPreferredAppearanceForBehavior(DisarmBehavior.MOB_DISARMER_ID, ProviderKey.allium("mob_disarmer"));
        items.bindAppearanceToBehavior(ProviderKey.allium("mob_disarmer"), DisarmBehavior.MOB_DISARMER_ID);

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
        when(player.hasPermission("allium.mobdisarmer.use")).thenReturn(true);
        when(player.getLocation()).thenAnswer(inv -> new Location(world, 0, 64, 0));

        service = newService(RULES, DisarmDropRule.SLOT_CHANCE);
        mainHand.set(newDisarmer());
    }

    private DisarmTool createTool(String id, DisarmerRules rules, DisarmDropRule dropRule, double radius) {
        DisarmParameters params = new DisarmParameters(
                rules,
                List.of(),
                radius,
                new DisarmTargetPolicy(DisarmTargetPolicy.DEFAULT_EXCLUDED_TYPES, true),
                dropRule,
                120_000L,
                200L,
                1_000L
        );
        Map<String, String> perms = Map.of(
                DisarmBehaviorType.PERMISSION_USE, "allium.mobdisarmer.use",
                DisarmBehaviorType.PERMISSION_BYPASS, "allium.mobdisarmer.bypass"
        );
        Map<String, String> msgs = new DisarmBehaviorType().messageSlots();
        Map<String, ToolSound> sounds = Map.of(DisarmBehaviorType.SOUND_DISARM, DisarmBehaviorType.DEFAULT_DISARM_SOUND);
        String localId = id.substring(id.indexOf(':') + 1);
        ToolDefinition def = new ToolDefinition(
                "tools/" + localId + ".yml",
                id,
                "Mob Disarmer",
                DisarmBehaviorType.ID,
                true,
                null,
                ProviderKey.allium(localId),
                List.of(),
                perms,
                msgs,
                sounds,
                params
        );
        return new DisarmTool(def, nanos::get);
    }

    private DisarmBehavior newService(DisarmerRules rules, DisarmDropRule dropRule) {
        return newService(List.of(createTool(DisarmBehavior.MOB_DISARMER_ID, rules, dropRule, 6.0D)));
    }

    private DisarmBehavior newService(Collection<DisarmTool> tools) {
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
        return new DisarmBehavior(items, protection, (p, m, r) -> sent.add(m.fallback()), diagnostics::add,
                clock::get, random, tools);
    }

    private ItemStack newDisarmer() {
        return items.createForBehavior(DisarmBehavior.MOB_DISARMER_ID, 1).itemStack().orElseThrow();
    }

    private DisarmerCharges toolState() {
        ItemRef ref = items.identify(mainHand.get()).orElseThrow();
        assertEquals(Optional.of(DisarmBehavior.MOB_DISARMER_ID), ref.behaviorId(), "tool must still verify");
        return DisarmerCharges.fromState(ref.stateData(), 3);
    }

    private DisarmBehavior.Outcome click(Object target, EquipmentSlot hand, TestEvent event) {
        return service.handleEntityInteraction(player, (org.bukkit.entity.Entity) target, hand, event, null);
    }

    private DisarmBehavior.Outcome click(Object target) {
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

        assertEquals(DisarmBehavior.Outcome.DISARMED, click(kit.mob, EquipmentSlot.HAND, event));

        assertTrue(event.cancelled);
        assertTrue(kit.items.isEmpty());
        assertEquals(List.of(Material.IRON_SWORD, Material.IRON_CHESTPLATE), drops.stream().map(ItemStack::getType).toList());
        for (EquipmentSlot slot : DisarmBehavior.DISARM_SLOTS) {
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
        assertEquals(DisarmBehavior.Outcome.NOTHING_TO_REMOVE, click(kit.mob, EquipmentSlot.HAND, event));
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
        assertEquals(DisarmBehavior.Outcome.DENIED, click(kit.mob));
        assertEquals(List.of(Messages.TOOL_DISARMER_PROTECTED_REASON), sent);

        hookDecision.set(ProtectionDecision.allow());
        cancelCheckEvent.set(true);
        nanos.addAndGet(1_000_000_000L);
        assertEquals(DisarmBehavior.Outcome.DENIED, click(kit.mob));

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

        assertEquals(DisarmBehavior.Outcome.STALE, click(kit.mob));
        assertNotNull(kit.items.get(EquipmentSlot.HAND));
        assertEquals(DisarmerCharges.full(3), DisarmerCharges.fromState(items.identify(original).orElseThrow().stateData(), 3));
        assertEquals(DisarmerCharges.full(3), toolState());

        // Same physical tool, but its state changed while protection ran.
        mainHand.set(original);
        hookSideEffect.set(() -> items.updateState(original, Map.of("charges", "1")));
        nanos.addAndGet(1_000_000_000L);
        assertEquals(DisarmBehavior.Outcome.STALE, click(kit.mob));
        assertNotNull(kit.items.get(EquipmentSlot.HAND));
        assertEquals(1, toolState().charges());
    }

    @Test
    void offHandEventsNeverActAndDuplicateClicksInTheGuardWindowSpendNothing() {
        Kit kit = zombie();
        kit.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 1.0F);
        assertEquals(DisarmBehavior.Outcome.IGNORED, click(kit.mob, EquipmentSlot.OFF_HAND, new TestEvent()));
        assertNotNull(kit.items.get(EquipmentSlot.HAND));

        assertEquals(DisarmBehavior.Outcome.DISARMED, click(kit.mob));
        kit.equip(EquipmentSlot.HAND, Material.GOLDEN_SWORD, 1.0F);
        TestEvent duplicate = new TestEvent();
        assertEquals(DisarmBehavior.Outcome.GUARDED, click(kit.mob, EquipmentSlot.HAND, duplicate));
        assertTrue(duplicate.cancelled);
        assertEquals(Material.GOLDEN_SWORD, kit.items.get(EquipmentSlot.HAND).getType());
        assertEquals(2, toolState().charges());

        nanos.addAndGet(200_000_000L);
        assertEquals(DisarmBehavior.Outcome.DISARMED, click(kit.mob));
        assertEquals(1, toolState().charges());
    }

    @Test
    void lastChargeStartsTheRechargeRechargingRefusesWithoutStrippingAndRefillCompletes() {
        Kit kit = zombie();
        for (int expected = 2; expected >= 0; expected--) {
            kit.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 0.0F);
            nanos.addAndGet(1_000_000_000L);
            assertEquals(DisarmBehavior.Outcome.DISARMED, click(kit.mob));
            assertEquals(expected, toolState().charges());
        }
        assertEquals(clock.get() + 120_000L, toolState().rechargeAt());
        assertTrue(sent.contains(Messages.TOOL_DISARMER_LAST_CHARGE));

        kit.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 0.0F);
        nanos.addAndGet(1_000_000_000L);
        clock.addAndGet(119_999L);
        assertEquals(DisarmBehavior.Outcome.REFUSED, click(kit.mob));
        assertNotNull(kit.items.get(EquipmentSlot.HAND));
        assertEquals(0, toolState().charges());

        clock.addAndGet(1L);
        nanos.addAndGet(1_000_000_000L);
        assertEquals(DisarmBehavior.Outcome.DISARMED, click(kit.mob));
        assertEquals(new DisarmerCharges(2, 0L, false, 4L), toolState());
    }

    @Test
    void rejectedStateWriteRestoresEquipmentAndDropsNothing() {
        ItemService spy = spy(items);
        doReturn(false).when(spy).updateState(any(), any());
        items = spy;
        service = newService(RULES, DisarmDropRule.SLOT_CHANCE);
        Kit kit = zombie();
        kit.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 0.7F);
        kit.equip(EquipmentSlot.LEGS, Material.IRON_LEGGINGS, 1.0F);

        assertEquals(DisarmBehavior.Outcome.WRITE_FAILED, click(kit.mob));
        assertEquals(Material.IRON_SWORD, kit.items.get(EquipmentSlot.HAND).getType());
        assertEquals(Material.IRON_LEGGINGS, kit.items.get(EquipmentSlot.LEGS).getType());
        assertEquals(0.7F, kit.chances.get(EquipmentSlot.HAND));
        assertEquals(1.0F, kit.chances.get(EquipmentSlot.LEGS));
        assertTrue(drops.isEmpty());
        assertEquals(DisarmerCharges.full(3), toolState());
    }

    @Test
    void alwaysRuleDropsEveryPieceAndVillagerGearIsGuaranteedUnderSlotChance() {
        service = newService(RULES, DisarmDropRule.ALWAYS);
        Kit zombie = zombie();
        zombie.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 0.0F);
        zombie.equip(EquipmentSlot.FEET, Material.IRON_BOOTS, 0.4F);
        assertEquals(DisarmBehavior.Outcome.DISARMED, click(zombie.mob));
        assertEquals(List.of(Material.IRON_SWORD, Material.IRON_BOOTS), drops.stream().map(ItemStack::getType).toList());
        assertEquals(0.0F, zombie.chances.get(EquipmentSlot.FEET));

        // Default slot-chance: the 0.5 roll keeps a 0.4 zombie piece, but villager gear always drops.
        drops.clear();
        service = newService(RULES, DisarmDropRule.SLOT_CHANCE);
        Kit sameRoll = zombie();
        sameRoll.equip(EquipmentSlot.FEET, Material.IRON_BOOTS, 0.4F);
        assertEquals(DisarmBehavior.Outcome.DISARMED, click(sameRoll.mob));
        assertTrue(drops.isEmpty());
        nanos.addAndGet(1_000_000_000L);
        Kit villager = kit(mock(org.bukkit.entity.Villager.class), EntityType.VILLAGER, new Location(world, 2, 64, 0));
        villager.equip(EquipmentSlot.HAND, Material.EMERALD, 0.0F);
        assertEquals(DisarmBehavior.Outcome.DISARMED, click(villager.mob));
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
        when(player.hasPermission("allium.mobdisarmer.bypass")).thenReturn(true);
        assertEquals(DisarmBehavior.Outcome.DISARMED, click(kit.mob));
        assertEquals(new DisarmerCharges(3, 0L, false, 1L), toolState());

        when(player.hasPermission("allium.mobdisarmer.bypass")).thenReturn(false);
        service = newService(new DisarmerRules(3, 120_000L, 0.6D, 0.6D), DisarmDropRule.SLOT_CHANCE);
        kit.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 0.0F);
        assertEquals(DisarmBehavior.Outcome.BROKE, click(kit.mob));
        assertEquals(new DisarmerCharges(0, 0L, true, 2L), toolState());
        kit.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 0.0F);
        nanos.addAndGet(1_000_000_000L);
        assertEquals(DisarmBehavior.Outcome.REFUSED, click(kit.mob));
        assertNotNull(kit.items.get(EquipmentSlot.HAND));
    }

    @Test
    void playersArmorStandsBossesDistantMobsAndStacksAreRefusedWithoutSpending() {
        Player other = mock(Player.class);
        when(other.getType()).thenReturn(EntityType.PLAYER);
        TestEvent playerEvent = new TestEvent();
        assertEquals(DisarmBehavior.Outcome.REFUSED, click(other, EquipmentSlot.HAND, playerEvent));
        assertTrue(playerEvent.cancelled);

        ArmorStand stand = mock(ArmorStand.class);
        when(stand.getType()).thenReturn(EntityType.ARMOR_STAND);
        assertEquals(DisarmBehavior.Outcome.IGNORED, click(stand));

        Kit dragon = kit(mock(EnderDragon.class), EntityType.ENDER_DRAGON, new Location(world, 1, 64, 0));
        dragon.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 1.0F);
        assertEquals(DisarmBehavior.Outcome.REFUSED, click(dragon.mob));
        assertNotNull(dragon.items.get(EquipmentSlot.HAND));

        Kit far = kit(mock(Zombie.class), EntityType.ZOMBIE, new Location(world, 7, 64, 0));
        far.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 1.0F);
        assertEquals(DisarmBehavior.Outcome.REFUSED, click(far.mob));

        Kit near = zombie();
        near.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 1.0F);
        mainHand.get().setAmount(2);
        assertEquals(DisarmBehavior.Outcome.REFUSED, click(near.mob));
        mainHand.get().setAmount(1);
        assertEquals(DisarmerCharges.full(3), toolState());
        assertNotNull(near.items.get(EquipmentSlot.HAND));
        assertTrue(drops.isEmpty());
    }

    @Test
    void legacyToolsImportChargesTimestampAndUsesBeforeActing() {
        Map<NamespacedKey, Object> legacy = new HashMap<>();
        ItemStack old = MockItemStacks.create(Material.DIAMOND_SWORD, 1, legacy, new AtomicReference<>(), new AtomicReference<>());
        legacy.put(DisarmBehavior.LEGACY_ITEM_ID, "mob_disarmer_0-3");
        legacy.put(DisarmBehavior.LEGACY_CHARGES, 0);
        legacy.put(DisarmBehavior.LEGACY_RECHARGE_AT, clock.get() + 5_000L);
        legacy.put(DisarmBehavior.LEGACY_USES, 7L);
        mainHand.set(old);

        service.handleAirUse(player, EquipmentSlot.HAND);
        assertNotSame(old, mainHand.get());
        assertEquals(new DisarmerCharges(0, clock.get() + 5_000L, false, 7L), toolState());
        assertEquals(List.of(Messages.TOOL_DISARMER_RECHARGING), sent);

        clock.addAndGet(5_000L);
        Kit kit = zombie();
        kit.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 0.0F);
        assertEquals(DisarmBehavior.Outcome.DISARMED, click(kit.mob));
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

    @Test
    void multipleDefinitionsBindToSameBehaviorWithSeparateStateAndRadius() {
        String wandId = "allium:wand_disarmer";
        items.registerAlliumItem(new AlliumItemDefinition(
                "wand_disarmer", Material.STICK, wandId, false, 2001, "Disarm Wand", Map.of(), true));
        items.registerSignedBehavior(wandId);
        items.setPreferredAppearanceForBehavior(wandId, ProviderKey.allium("wand_disarmer"));
        items.bindAppearanceToBehavior(ProviderKey.allium("wand_disarmer"), wandId);

        DisarmTool mobDisarmer = createTool(DisarmBehavior.MOB_DISARMER_ID, RULES, DisarmDropRule.SLOT_CHANCE, 6.0D);
        DisarmTool disarmWand = createTool(wandId, new DisarmerRules(5, 60_000L, 0.0D, 0.0D), DisarmDropRule.ALWAYS, 10.0D);

        service = newService(List.of(mobDisarmer, disarmWand));

        // Mob at distance 8 (player at 0, mob at 8)
        Kit farMob = kit(mock(Zombie.class), EntityType.ZOMBIE, new Location(world, 8, 64, 0));
        farMob.equip(EquipmentSlot.HAND, Material.IRON_SWORD, 1.0F);

        // Standard disarmer (radius 6.0) is too far
        mainHand.set(newDisarmer());
        assertEquals(DisarmBehavior.Outcome.REFUSED, click(farMob.mob));

        // Wand disarmer (radius 10.0) reaches and disarms!
        ItemStack wand = items.createForBehavior(wandId, 1).itemStack().orElseThrow();
        mainHand.set(wand);
        assertEquals(DisarmBehavior.Outcome.DISARMED, click(farMob.mob));
        ItemRef ref = items.identify(mainHand.get()).orElseThrow();
        assertEquals(Optional.of(wandId), ref.behaviorId());
        DisarmerCharges wandCharges = DisarmerCharges.fromState(ref.stateData(), 5);
        assertEquals(4, wandCharges.charges());
    }

    private static String pdcString(ItemStack item, NamespacedKey key) {
        return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private Kit zombie() {
        return kit(mock(Zombie.class), EntityType.ZOMBIE, new Location(world, 1, 64, 0));
    }

    private Kit kit(Mob mob, EntityType type, Location location) {
        Kit kit = new Kit(mob);
        when(mob.getType()).thenReturn(type);
        when(mob.isValid()).thenReturn(true);
        when(mob.getLocation()).thenReturn(location);
        when(mob.getWorld()).thenReturn(world);

        EntityEquipment eq = mock(EntityEquipment.class);
        when(mob.getEquipment()).thenReturn(eq);
        when(eq.getItem(any(EquipmentSlot.class))).thenAnswer(inv -> kit.items.get(inv.getArgument(0)));
        when(eq.getItemInMainHandDropChance()).thenAnswer(inv -> kit.chances.getOrDefault(EquipmentSlot.HAND, 0.0F));
        when(eq.getItemInOffHandDropChance()).thenAnswer(inv -> kit.chances.getOrDefault(EquipmentSlot.OFF_HAND, 0.0F));
        when(eq.getHelmetDropChance()).thenAnswer(inv -> kit.chances.getOrDefault(EquipmentSlot.HEAD, 0.0F));
        when(eq.getChestplateDropChance()).thenAnswer(inv -> kit.chances.getOrDefault(EquipmentSlot.CHEST, 0.0F));
        when(eq.getLeggingsDropChance()).thenAnswer(inv -> kit.chances.getOrDefault(EquipmentSlot.LEGS, 0.0F));
        when(eq.getBootsDropChance()).thenAnswer(inv -> kit.chances.getOrDefault(EquipmentSlot.FEET, 0.0F));

        doAnswer(inv -> {
            EquipmentSlot slot = inv.getArgument(0);
            ItemStack stack = inv.getArgument(1);
            if (stack == null || stack.getType() == Material.AIR) {
                kit.items.remove(slot);
            } else {
                kit.items.put(slot, stack);
            }
            return null;
        }).when(eq).setItem(any(EquipmentSlot.class), any());

        doAnswer(inv -> { kit.chances.put(EquipmentSlot.HAND, inv.getArgument(0)); return null; }).when(eq).setItemInMainHandDropChance(anyFloat());
        doAnswer(inv -> { kit.chances.put(EquipmentSlot.OFF_HAND, inv.getArgument(0)); return null; }).when(eq).setItemInOffHandDropChance(anyFloat());
        doAnswer(inv -> { kit.chances.put(EquipmentSlot.HEAD, inv.getArgument(0)); return null; }).when(eq).setHelmetDropChance(anyFloat());
        doAnswer(inv -> { kit.chances.put(EquipmentSlot.CHEST, inv.getArgument(0)); return null; }).when(eq).setChestplateDropChance(anyFloat());
        doAnswer(inv -> { kit.chances.put(EquipmentSlot.LEGS, inv.getArgument(0)); return null; }).when(eq).setLeggingsDropChance(anyFloat());
        doAnswer(inv -> { kit.chances.put(EquipmentSlot.FEET, inv.getArgument(0)); return null; }).when(eq).setBootsDropChance(anyFloat());

        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(pdc.get(any(), eq(PersistentDataType.LONG))).thenAnswer(inv -> kit.pickupBlocked.get() == 0L ? null : kit.pickupBlocked.get());
        doAnswer(inv -> {
            kit.pickupBlocked.set(inv.getArgument(2));
            return null;
        }).when(pdc).set(any(), eq(PersistentDataType.LONG), any());
        when(mob.getPersistentDataContainer()).thenReturn(pdc);

        return kit;
    }

    private static final class Kit {
        final Mob mob;
        final Map<EquipmentSlot, ItemStack> items = new EnumMap<>(EquipmentSlot.class);
        final Map<EquipmentSlot, Float> chances = new EnumMap<>(EquipmentSlot.class);
        final AtomicLong pickupBlocked = new AtomicLong(0L);

        Kit(Mob mob) {
            this.mob = mob;
        }

        void equip(EquipmentSlot slot, Material material, float dropChance) {
            ItemStack stack = MockItemStacks.create(material);
            items.put(slot, stack);
            chances.put(slot, dropChance);
        }
    }

    private static final class TestEvent implements Cancellable {
        private boolean cancelled;
        public boolean isCancelled() { return cancelled; }
        public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    }
}
