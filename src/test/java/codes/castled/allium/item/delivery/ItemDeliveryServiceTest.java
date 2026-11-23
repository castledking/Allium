package codes.castled.allium.item.delivery;

import codes.castled.allium.item.MockItemStacks;
import codes.castled.allium.item.library.ItemLibrary;
import codes.castled.allium.item.library.SkullProfiles;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.PluginManager;
import org.bukkit.profile.PlayerProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ItemDeliveryServiceTest {

    private ItemStack[] storage;
    private List<ItemStack> dropped;
    private AtomicReference<Player> online;
    private AtomicBoolean cancel;
    private Player player;
    private PlayerInventory inventory;
    private ItemDeliveryService service;
    private final Map<EquipmentSlot, ItemStack> armor = new HashMap<>();

    @BeforeEach
    void setUp() {
        storage = new ItemStack[4];
        dropped = new ArrayList<>();
        cancel = new AtomicBoolean(false);
        inventory = mock(PlayerInventory.class);
        when(inventory.getStorageContents()).thenAnswer(inv -> storage.clone());
        when(inventory.addItem(any(ItemStack[].class))).thenAnswer(inv -> {
            Map<Integer, ItemStack> rest = new HashMap<>();
            Object[] stacks = inv.getArguments();
            for (int n = 0; n < stacks.length; n++) {
                ItemStack stack = (ItemStack) stacks[n];
                int slot = java.util.Arrays.asList(storage).indexOf(null);
                if (slot < 0) rest.put(n, stack); else storage[slot] = stack;
            }
            return rest;
        });
        when(inventory.getItem(any(EquipmentSlot.class))).thenAnswer(inv -> armor.get((EquipmentSlot) inv.getArgument(0)));
        doAnswer(inv -> armor.put(inv.getArgument(0), inv.getArgument(1))).when(inventory).setItem(any(EquipmentSlot.class), any());
        World world = mock(World.class);
        when(world.dropItemNaturally(any(Location.class), any(ItemStack.class))).thenAnswer(inv -> {
            dropped.add(inv.getArgument(1));
            return null;
        });
        player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        online = new AtomicReference<>(player);
        PluginManager events = mock(PluginManager.class);
        doAnswer(inv -> {
            if (cancel.get()) ((ItemGiveEvent) inv.getArgument(0)).setCancelled(true);
            return null;
        }).when(events).callEvent(any());
        service = new ItemDeliveryService(Runnable::run, () -> true, uuid -> online.get(), events);
    }

    private static ItemStack stack(Material material, int amount) {
        ItemStack stack = MockItemStacks.create(material);
        stack.setAmount(amount);
        when(stack.getMaxStackSize()).thenReturn(64);
        return stack;
    }

    private static ItemLibrary.Prepared prepared(ItemStack... stacks) {
        List<ItemLibrary.PreparedItem> items = new ArrayList<>();
        for (ItemStack s : stacks) items.add(new ItemLibrary.PreparedItem(s, null));
        return new ItemLibrary.Prepared(items, List.of());
    }

    private DeliveryOutcome give(ItemLibrary.Prepared prepared, boolean equip, OverflowPolicy policy) {
        return service.deliver(mock(CommandSender.class), player, prepared, equip, policy).join();
    }

    private void fill(int freeSlots) {
        for (int i = 0; i < storage.length - freeSlots; i++) storage[i] = stack(Material.DIRT, 64);
    }

    @Test
    void refuseGivesNothingWhenItWouldNotAllFit() {
        fill(1);
        DeliveryOutcome outcome = give(prepared(stack(Material.DIAMOND, 5), stack(Material.EMERALD, 3)), false, OverflowPolicy.REFUSE);
        assertEquals(DeliveryOutcome.Status.REFUSED_FULL, outcome.status());
        assertEquals(0, outcome.given());
        verify(inventory, never()).addItem(any(ItemStack[].class));
        assertTrue(dropped.isEmpty());
        assertEquals(DeliveryOutcome.Status.DELIVERED, give(prepared(stack(Material.DIAMOND, 5)), false, OverflowPolicy.REFUSE).status());
    }

    @Test
    void partialGivesWhatFitsAndReportsTheExactLeftover() {
        fill(1);
        DeliveryOutcome outcome = give(prepared(stack(Material.DIAMOND, 5), stack(Material.EMERALD, 3)), false, OverflowPolicy.PARTIAL);
        assertEquals(DeliveryOutcome.Status.PARTIAL, outcome.status());
        assertEquals(8, outcome.requested());
        assertEquals(5, outcome.given());
        assertEquals(3, outcome.leftover());
        assertEquals(0, outcome.dropped());
        assertTrue(dropped.isEmpty());
    }

    @Test
    void dropGivesWhatFitsAndDropsTheRestAtTheTargetsFeet() {
        fill(1);
        DeliveryOutcome outcome = give(prepared(stack(Material.DIAMOND, 5), stack(Material.EMERALD, 3)), false, OverflowPolicy.DROP);
        assertEquals(DeliveryOutcome.Status.DELIVERED, outcome.status());
        assertEquals(5, outcome.given());
        assertEquals(3, outcome.dropped());
        assertEquals(List.of(Material.EMERALD), dropped.stream().map(ItemStack::getType).toList());
    }

    @Test
    void offlineTargetsAndCancelledEventsGetNothing() {
        online.set(null);
        assertEquals(DeliveryOutcome.Status.TARGET_OFFLINE, give(prepared(stack(Material.DIAMOND, 1)), false, OverflowPolicy.DROP).status());
        online.set(player);
        cancel.set(true);
        assertEquals(DeliveryOutcome.Status.CANCELLED_BY_EVENT, give(prepared(stack(Material.DIAMOND, 1)), false, OverflowPolicy.DROP).status());
        verify(inventory, never()).addItem(any(ItemStack[].class));
    }

    @Test
    void aTargetWhoDisconnectsDuringAsyncSkullResolutionGetsNothing() {
        ItemStack head = MockItemStacks.create(Material.PLAYER_HEAD);
        CompletableFuture<SkullProfiles.Resolved> lookup = new CompletableFuture<>();
        ItemLibrary.Prepared prepared = new ItemLibrary.Prepared(List.of(new ItemLibrary.PreparedItem(head, lookup)), List.of());
        CompletableFuture<DeliveryOutcome> pending = service.deliver(mock(CommandSender.class), player, prepared, false, OverflowPolicy.DROP);
        assertFalse(pending.isDone(), "the server thread is not blocked while the profile resolves");
        online.set(null);
        lookup.complete(new SkullProfiles.Resolved(mock(PlayerProfile.class)));
        assertEquals(DeliveryOutcome.Status.TARGET_OFFLINE, pending.join().status());
        verify(inventory, never()).addItem(any(ItemStack[].class));

        online.set(player);
        ItemStack second = MockItemStacks.create(Material.PLAYER_HEAD);
        SkullMeta meta = mock(SkullMeta.class);
        when(second.getItemMeta()).thenReturn(meta);
        CompletableFuture<SkullProfiles.Resolved> slow = new CompletableFuture<>();
        CompletableFuture<DeliveryOutcome> warned = service.deliver(mock(CommandSender.class), player,
                new ItemLibrary.Prepared(List.of(new ItemLibrary.PreparedItem(second, slow)), List.of()), false, OverflowPolicy.DROP);
        PlayerProfile fallback = mock(PlayerProfile.class);
        slow.complete(new SkullProfiles.Resolved(fallback, "the skin for Notch could not be resolved", true));
        DeliveryOutcome outcome = warned.join();
        assertEquals(DeliveryOutcome.Status.DEGRADED, outcome.status(), "an untextured fallback is reported, not hidden");
        assertEquals(1, outcome.given());
        assertEquals(List.of("the skin for Notch could not be resolved"), outcome.warnings());
        verify(meta).setOwnerProfile(fallback);
    }

    @Test
    void refuseFallbackGivesNothingWhenASkinCannotBeResolved() {
        PluginManager events = mock(PluginManager.class);
        ItemDeliveryService refusing = new ItemDeliveryService(Runnable::run, () -> true, uuid -> online.get(), events,
                () -> SkullFallback.REFUSE);
        ItemStack head = MockItemStacks.create(Material.PLAYER_HEAD);
        ItemStack diamond = stack(Material.DIAMOND, 1);
        CompletableFuture<SkullProfiles.Resolved> degraded = CompletableFuture.completedFuture(
                new SkullProfiles.Resolved(mock(PlayerProfile.class), "the skin for Notch could not be resolved (timed out)", true));
        DeliveryOutcome outcome = refusing.deliver(mock(CommandSender.class), player, new ItemLibrary.Prepared(List.of(
                new ItemLibrary.PreparedItem(head, degraded), new ItemLibrary.PreparedItem(diamond, null)), List.of()),
                false, OverflowPolicy.DROP).join();
        assertEquals(DeliveryOutcome.Status.REFUSED_UNRESOLVED, outcome.status());
        assertEquals(0, outcome.given());
        assertEquals(2, outcome.leftover());
        verify(inventory, never()).addItem(any(ItemStack[].class));
        verify(events, never()).callEvent(any());

        CompletableFuture<SkullProfiles.Resolved> fine = CompletableFuture.completedFuture(new SkullProfiles.Resolved(mock(PlayerProfile.class)));
        assertEquals(DeliveryOutcome.Status.DELIVERED, refusing.deliver(mock(CommandSender.class), player, new ItemLibrary.Prepared(List.of(
                new ItemLibrary.PreparedItem(MockItemStacks.create(Material.PLAYER_HEAD), fine)), List.of()), false, OverflowPolicy.DROP)
                .join().status(), "a resolved skin is unaffected by the refuse fallback");
    }

    @Test
    void equipFillsEmptyArmorSlotsAndNeverOverwrites() {
        ItemStack helmet = stack(Material.IRON_HELMET, 1);
        ItemStack boots = stack(Material.IRON_BOOTS, 1);
        ItemStack worn = stack(Material.LEATHER_BOOTS, 1);
        armor.put(EquipmentSlot.FEET, worn);
        DeliveryOutcome outcome = give(prepared(helmet, boots), true, OverflowPolicy.DROP);
        assertEquals(2, outcome.given());
        assertSame(helmet, armor.get(EquipmentSlot.HEAD));
        assertSame(worn, armor.get(EquipmentSlot.FEET), "occupied slots are never overwritten");
        assertTrue(java.util.Arrays.asList(storage).contains(boots), "the second pair goes to the inventory");
    }

    @Test
    void crossRegionDeliveryRunsEventEquipInventoryAndDropsOnTargetRegion() {
        fill(1);
        AtomicBoolean onTargetRegion = new AtomicBoolean(false);
        List<Runnable> queued = new ArrayList<>();
        PluginManager events = mock(PluginManager.class);
        doAnswer(inv -> {
            assertTrue(onTargetRegion.get(), "ItemGiveEvent must run on the target's owning region");
            return null;
        }).when(events).callEvent(any());
        World world = player.getWorld();
        when(world.dropItemNaturally(any(Location.class), any(ItemStack.class))).thenAnswer(inv -> {
            assertTrue(onTargetRegion.get(), "overflow drop must run on the target's owning region");
            dropped.add(inv.getArgument(1));
            return null;
        });
        ItemDeliveryService regional = new ItemDeliveryService(new ItemDeliveryService.TargetThread() {
            @Override public boolean owns(Player target) { return onTargetRegion.get(); }
            @Override public void execute(Player target, Runnable task, Runnable retired) {
                assertSame(player, target);
                queued.add(task);
            }
        }, uuid -> online.get(), events, () -> SkullFallback.UNTEXTURED);

        ItemStack helmet = stack(Material.IRON_HELMET, 1);
        CompletableFuture<DeliveryOutcome> future = regional.deliver(mock(CommandSender.class), player,
                prepared(helmet, stack(Material.DIAMOND, 5), stack(Material.EMERALD, 3)), true, OverflowPolicy.DROP);

        assertFalse(future.isDone(), "delivery waits for the target's region scheduler");
        assertEquals(1, queued.size());
        verify(events, never()).callEvent(any());
        verify(inventory, never()).addItem(any(ItemStack[].class));
        assertTrue(dropped.isEmpty());

        onTargetRegion.set(true);
        queued.get(0).run();

        DeliveryOutcome outcome = future.join();
        assertEquals(DeliveryOutcome.Status.DELIVERED, outcome.status());
        assertEquals(6, outcome.given());
        assertEquals(3, outcome.dropped());
        assertSame(helmet, armor.get(EquipmentSlot.HEAD));
        assertEquals(List.of(Material.EMERALD), dropped.stream().map(ItemStack::getType).toList());
    }

    @Test
    void retiredTargetCompletesOfflineWithoutTouchingInventory() {
        PluginManager events = mock(PluginManager.class);
        List<Runnable> retiredCallbacks = new ArrayList<>();
        ItemDeliveryService regional = new ItemDeliveryService(new ItemDeliveryService.TargetThread() {
            @Override public boolean owns(Player target) { return false; }
            @Override public void execute(Player target, Runnable task, Runnable retired) {
                retiredCallbacks.add(retired);
            }
        }, uuid -> online.get(), events, () -> SkullFallback.UNTEXTURED);

        CompletableFuture<DeliveryOutcome> future = regional.deliver(mock(CommandSender.class), player,
                prepared(stack(Material.DIAMOND, 4)), false, OverflowPolicy.DROP);
        assertFalse(future.isDone());
        retiredCallbacks.get(0).run();

        DeliveryOutcome outcome = future.join();
        assertEquals(DeliveryOutcome.Status.TARGET_OFFLINE, outcome.status());
        assertEquals(0, outcome.given());
        assertEquals(4, outcome.leftover());
        verify(events, never()).callEvent(any());
        verify(inventory, never()).addItem(any(ItemStack[].class));
    }

    @Test
    void queuedDeliveryDoesNotMutateReplacementConnection() {
        PluginManager events = mock(PluginManager.class);
        List<Runnable> queued = new ArrayList<>();
        ItemDeliveryService regional = new ItemDeliveryService(new ItemDeliveryService.TargetThread() {
            @Override public boolean owns(Player target) { return false; }
            @Override public void execute(Player target, Runnable task, Runnable retired) {
                queued.add(task);
            }
        }, uuid -> online.get(), events, () -> SkullFallback.UNTEXTURED);

        CompletableFuture<DeliveryOutcome> future = regional.deliver(mock(CommandSender.class), player,
                prepared(stack(Material.DIAMOND, 2)), false, OverflowPolicy.DROP);

        UUID id = player.getUniqueId();
        Player replacement = mock(Player.class);
        PlayerInventory replacementInventory = mock(PlayerInventory.class);
        when(replacement.getUniqueId()).thenReturn(id);
        when(replacement.isOnline()).thenReturn(true);
        when(replacement.getInventory()).thenReturn(replacementInventory);
        online.set(replacement);

        queued.get(0).run();

        DeliveryOutcome outcome = future.join();
        assertEquals(DeliveryOutcome.Status.TARGET_OFFLINE, outcome.status());
        verify(events, never()).callEvent(any());
        verify(inventory, never()).addItem(any(ItemStack[].class));
        verify(replacementInventory, never()).addItem(any(ItemStack[].class));
    }

    @Test
    void mailPlanAndApplyRequireTargetOwnership() {
        AtomicBoolean owned = new AtomicBoolean(false);
        ItemDeliveryService regional = new ItemDeliveryService(new ItemDeliveryService.TargetThread() {
            @Override public boolean owns(Player target) { return owned.get(); }
            @Override public void execute(Player target, Runnable task, Runnable retired) { task.run(); }
        }, uuid -> online.get(), mock(PluginManager.class), () -> SkullFallback.UNTEXTURED);

        assertThrows(IllegalStateException.class,
                () -> regional.planMail(mock(CommandSender.class), player, List.of(stack(Material.DIAMOND, 1))));
        owned.set(true);
        ItemDeliveryService.MailPlan plan = regional.planMail(mock(CommandSender.class), player, List.of(stack(Material.DIAMOND, 1)));
        assertNotNull(plan);
        owned.set(false);
        assertThrows(IllegalStateException.class, () -> regional.applyMail(player, plan));
    }
}
