package codes.castled.allium.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.naming.PlayerNameService;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InspectionFoliaTest {

    private static ItemStack item(Material type, int amount) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(type);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.hasItemMeta()).thenReturn(false);
        when(stack.clone()).thenAnswer(inv -> item(type, amount));
        when(stack.isSimilar(any())).thenAnswer(inv -> {
            ItemStack other = inv.getArgument(0);
            return other != null && other.getType() == type;
        });
        return stack;
    }

    private static Inventory fakeInventory(InventoryHolder holder, int size) {
        ItemStack[] slots = new ItemStack[size];
        Inventory inv = mock(Inventory.class);
        when(inv.getHolder()).thenReturn(holder);
        when(inv.getSize()).thenReturn(size);
        when(inv.getItem(anyInt())).thenAnswer(invCall -> {
            int i = invCall.getArgument(0);
            return i >= 0 && i < size ? slots[i] : null;
        });
        doAnswer(invCall -> {
            int i = invCall.getArgument(0);
            if (i >= 0 && i < size) slots[i] = invCall.getArgument(1);
            return null;
        }).when(inv).setItem(anyInt(), any());
        return inv;
    }

    private static final class FoliaHarness {
        final PluginStart plugin = mock(PluginStart.class);
        final Server server = mock(Server.class);
        final TaskScheduler scheduler = mock(TaskScheduler.class);
        final MessageManager messages = mock(MessageManager.class);
        final ModerationRepository repository = mock(ModerationRepository.class);
        final PlayerNameService names = mock(PlayerNameService.class);
        final AtomicReference<ServerThread> currentRegion = new AtomicReference<>();
        final Map<Player, ServerThread> playerRegions = new HashMap<>();
        final Map<ServerThread, Queue<Runnable>> regionQueues = new HashMap<>();
        final Queue<Runnable> globalQueue = new ArrayDeque<>();
        final Queue<Runnable> ioQueue = new ArrayDeque<>();
        final Map<UUID, Player> online = new HashMap<>();
        final Map<String, NameCache.Entry> directory = new HashMap<>();
        final Map<Player, Set<String>> perms = new HashMap<>();
        final Map<Player, Inventory> openTop = new HashMap<>();
        final Map<Player, ItemStack[]> invSlots = new HashMap<>();
        BooleanSupplier refreshTick;

        FoliaHarness() {
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getScheduler()).thenReturn(scheduler);
            when(plugin.getMessageManager()).thenReturn(messages);
            when(plugin.getPlayerNames()).thenReturn(names);
            when(plugin.logger()).thenReturn(mock(AlliumLogger.class));

            when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
            when(server.getOnlinePlayers()).thenAnswer(inv -> List.copyOf(online.values()));
            when(server.getPlayer(any(UUID.class))).thenAnswer(inv -> online.get(inv.getArgument(0)));
            when(server.getPlayerExact(anyString())).thenAnswer(inv -> {
                String n = inv.getArgument(0);
                return online.values().stream().filter(p -> p.getName().equalsIgnoreCase(n)).findFirst().orElse(null);
            });
            when(server.createInventory(any(InventoryHolder.class), anyInt(), anyString()))
                    .thenAnswer(inv -> fakeInventory(inv.getArgument(0), inv.getArgument(1)));

            when(names.resolve(anyString())).thenAnswer(inv -> CompletableFuture.completedFuture(
                    Optional.ofNullable(directory.get(inv.<String>getArgument(0).toLowerCase()))));

            when(scheduler.runOnServer(any(Runnable.class))).thenAnswer(inv -> {
                globalQueue.add(inv.getArgument(0));
                return TaskHandle.NONE;
            });
            when(scheduler.repeatOnServer(any(BooleanSupplier.class), anyLong(), anyLong())).thenAnswer(inv -> {
                refreshTick = inv.getArgument(0);
                return TaskHandle.NONE;
            });
            when(scheduler.runOn(any(ServerThread.class), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(inv -> {
                ServerThread region = inv.getArgument(0);
                Runnable task = inv.getArgument(1);
                regionQueues.computeIfAbsent(region, ignored -> new ArrayDeque<>()).add(task);
                return TaskHandle.NONE;
            });
        }

        Player player(String name) {
            Player p = mock(Player.class);
            UUID id = UUID.randomUUID();
            ServerThread region = mock(ServerThread.class, name + "-region");
            when(region.isServerThread()).thenAnswer(inv -> currentRegion.get() == region);
            playerRegions.put(p, region);
            regionQueues.put(region, new ArrayDeque<>());
            online.put(id, p);
            directory.put(name.toLowerCase(), new NameCache.Entry(id, name, 1_000L));

            ItemStack[] slots = new ItemStack[41];
            invSlots.put(p, slots);
            PlayerInventory inv = mock(PlayerInventory.class);
            AtomicInteger held = new AtomicInteger(0);
            when(inv.getHeldItemSlot()).thenAnswer(call -> {
                assertEquals(region, currentRegion.get(), name + ".getInventory().getHeldItemSlot() off owning region");
                return held.get();
            });
            when(inv.getItem(anyInt())).thenAnswer(call -> {
                assertEquals(region, currentRegion.get(), name + ".getInventory().getItem() off owning region");
                return slots[call.<Integer>getArgument(0)];
            });
            doAnswer(call -> {
                assertEquals(region, currentRegion.get(), name + ".getInventory().setItem() off owning region");
                slots[call.<Integer>getArgument(0)] = call.getArgument(1);
                return null;
            }).when(inv).setItem(anyInt(), any());
            when(inv.getBoots()).thenAnswer(call -> slots[36]);
            when(inv.getLeggings()).thenAnswer(call -> slots[37]);
            when(inv.getChestplate()).thenAnswer(call -> slots[38]);
            when(inv.getHelmet()).thenAnswer(call -> slots[39]);
            when(inv.getItemInOffHand()).thenAnswer(call -> slots[40]);

            when(p.getUniqueId()).thenReturn(id);
            when(p.getName()).thenReturn(name);
            when(p.isOnline()).thenReturn(true);
            when(p.getMetadata("vanished")).thenReturn(List.of());
            when(p.isDead()).thenAnswer(call -> {
                assertEquals(region, currentRegion.get(), name + ".isDead() called off owning region");
                return false;
            });
            when(p.getInventory()).thenAnswer(call -> {
                assertEquals(region, currentRegion.get(), name + ".getInventory() called off owning region");
                return inv;
            });
            when(p.openInventory(any(Inventory.class))).thenAnswer(call -> {
                assertEquals(region, currentRegion.get(), name + ".openInventory() called off owning region");
                Inventory gui = call.getArgument(0);
                openTop.put(p, gui);
                InventoryView view = mock(InventoryView.class);
                when(view.getTopInventory()).thenReturn(gui);
                return view;
            });
            doAnswer(call -> {
                assertEquals(region, currentRegion.get(), name + ".closeInventory() called off owning region");
                openTop.remove(p);
                return null;
            }).when(p).closeInventory();
            doAnswer(call -> {
                assertEquals(region, currentRegion.get(), name + ".updateInventory() called off owning region");
                return null;
            }).when(p).updateInventory();
            when(p.getOpenInventory()).thenAnswer(call -> {
                assertEquals(region, currentRegion.get(), name + ".getOpenInventory() called off owning region");
                InventoryView view = mock(InventoryView.class);
                when(view.getTopInventory()).thenReturn(openTop.get(p));
                return view;
            });

            Set<String> set = new HashSet<>();
            perms.put(p, set);
            when(p.hasPermission(anyString())).thenAnswer(call -> set.contains(call.getArgument(0)));
            return p;
        }

        ServerThread regionOf(Player p) {
            return playerRegions.get(p);
        }

        void runInRegion(Player p, Runnable body) {
            ServerThread prev = currentRegion.getAndSet(regionOf(p));
            try {
                body.run();
            } finally {
                currentRegion.set(prev);
            }
        }

        int drainRegion(Player p) {
            ServerThread region = regionOf(p);
            Queue<Runnable> queue = regionQueues.get(region);
            int count = 0;
            ServerThread prev = currentRegion.getAndSet(region);
            try {
                while (!queue.isEmpty()) {
                    queue.poll().run();
                    count++;
                }
            } finally {
                currentRegion.set(prev);
            }
            return count;
        }

        MockedStatic<BukkitServerThread> mockFolia() {
            MockedStatic<BukkitServerThread> mocked = mockStatic(BukkitServerThread.class, Answers.CALLS_REAL_METHODS);
            mocked.when(BukkitServerThread::isFolia).thenReturn(true);
            mocked.when(() -> BukkitServerThread.entity(eq(plugin), any(Player.class)))
                    .thenAnswer(inv -> playerRegions.get(inv.getArgument(1)));
            return mocked;
        }
    }

    @Test
    void crossRegionOnlineInvseeOpensEditsAndRefreshesOnOwningRegions() {
        FoliaHarness h = new FoliaHarness();
        Player viewer = h.player("Staff");
        Player target = h.player("Bob");
        h.perms.get(viewer).addAll(Set.of(
                InspectionService.PERMISSION_INVSEE,
                InspectionService.PERMISSION_INVSEE_EDIT,
                InspectionService.PERMISSION_BYPASS));
        h.invSlots.get(target)[1] = item(Material.DIAMOND, 3);

        try (MockedStatic<BukkitServerThread> ignored = h.mockFolia()) {
            InspectionService service = new InspectionService(
                    h.plugin, h.repository, null, h.ioQueue::add,
                    (s, u) -> true, p -> false, u -> false, () -> h.names);
            service.enable();

            // 1. Viewer invokes /invsee Bob on Viewer's region -> resolves on Viewer's region -> reads Bob on Bob's region -> opens GUI on Viewer's region.
            h.runInRegion(viewer, () -> service.dispatchInvsee(viewer, new String[]{"Bob"}));
            h.drainRegion(viewer); // resolve callback
            assertNull(h.openTop.get(viewer), "GUI must wait for Bob's region snapshot");

            h.drainRegion(target); // snapshot Bob's inventory on Bob's region
            h.drainRegion(viewer); // open GUI on Viewer's region
            Inventory gui = h.openTop.get(viewer);
            assertNotNull(gui, "Viewer should now have inspection GUI open");
            assertEquals(Material.DIAMOND, gui.getItem(InspectionLayout.toGuiSlot(InspectionLayout.InspectionType.INVSEE, 1, 0)).getType());

            // 2. Viewer clicks slot 1 to take the diamonds on Viewer's region.
            int guiSlot = InspectionLayout.toGuiSlot(InspectionLayout.InspectionType.INVSEE, 1, 0);
            InventoryView view = mock(InventoryView.class);
            when(view.getTopInventory()).thenReturn(gui);
            InventoryClickEvent click = mock(InventoryClickEvent.class);
            when(click.getView()).thenReturn(view);
            when(click.getWhoClicked()).thenReturn(viewer);
            when(click.getRawSlot()).thenReturn(guiSlot);
            when(click.getAction()).thenReturn(InventoryAction.PICKUP_ALL);
            when(click.getClick()).thenReturn(ClickType.LEFT);

            h.runInRegion(viewer, () -> {
                service.onInventoryClick(click);
                gui.setItem(guiSlot, null); // simulate Bukkit completing the click
            });
            verify(click, org.mockito.Mockito.never()).setCancelled(true);

            // Flush runs on Viewer's region, hops to Bob's region to apply, then back to Viewer's region.
            h.drainRegion(viewer);
            assertNotNull(h.invSlots.get(target)[1], "Bob's inventory must not be mutated off Bob's region");

            h.drainRegion(target);
            assertNull(h.invSlots.get(target)[1], "Bob's slot 1 cleared on Bob's region");
            h.drainRegion(viewer);

            // 3. Bob picks up an emerald on Bob's region; periodic global refresh reads Bob on Bob's region and updates Viewer on Viewer's region.
            h.invSlots.get(target)[2] = item(Material.EMERALD, 5);
            h.currentRegion.set(null);
            h.refreshTick.getAsBoolean();
            h.refreshTick.getAsBoolean(); // coalesced while refreshInFlight
            assertEquals(1, h.regionQueues.get(h.regionOf(target)).size(), "in-flight refresh must coalesce");

            h.drainRegion(target);
            h.drainRegion(viewer);
            int slot2Gui = InspectionLayout.toGuiSlot(InspectionLayout.InspectionType.INVSEE, 2, 0);
            assertEquals(Material.EMERALD, gui.getItem(slot2Gui).getType());

            service.disable();
        }
    }

    @Test
    void crossRegionConflictProtectsTargetSlotAndNotifiesViewer() {
        FoliaHarness h = new FoliaHarness();
        Player viewer = h.player("Staff");
        Player target = h.player("Bob");
        h.perms.get(viewer).addAll(Set.of(
                InspectionService.PERMISSION_INVSEE,
                InspectionService.PERMISSION_INVSEE_EDIT,
                InspectionService.PERMISSION_BYPASS));
        h.invSlots.get(target)[1] = item(Material.DIAMOND, 3);

        try (MockedStatic<BukkitServerThread> ignored = h.mockFolia()) {
            InspectionService service = new InspectionService(
                    h.plugin, h.repository, null, h.ioQueue::add,
                    (s, u) -> true, p -> false, u -> false, () -> h.names);
            service.enable();

            h.runInRegion(viewer, () -> service.dispatchInvsee(viewer, new String[]{"Bob"}));
            h.drainRegion(viewer);
            h.drainRegion(target);
            h.drainRegion(viewer);
            Inventory gui = h.openTop.get(viewer);
            assertNotNull(gui);

            // Viewer clicks slot 1, while Bob's slot 1 concurrently changes to EMERALD before flush lands on Bob's region.
            int guiSlot = InspectionLayout.toGuiSlot(InspectionLayout.InspectionType.INVSEE, 1, 0);
            InventoryView view = mock(InventoryView.class);
            when(view.getTopInventory()).thenReturn(gui);
            InventoryClickEvent click = mock(InventoryClickEvent.class);
            when(click.getView()).thenReturn(view);
            when(click.getWhoClicked()).thenReturn(viewer);
            when(click.getRawSlot()).thenReturn(guiSlot);
            when(click.getAction()).thenReturn(InventoryAction.PICKUP_ALL);
            when(click.getClick()).thenReturn(ClickType.LEFT);

            h.runInRegion(viewer, () -> {
                service.onInventoryClick(click);
                gui.setItem(guiSlot, null);
            });
            h.invSlots.get(target)[1] = item(Material.EMERALD, 1);

            h.drainRegion(viewer); // capture delta and hop to Bob
            h.drainRegion(target); // detect conflict on Bob's region -> do NOT overwrite emerald
            assertEquals(Material.EMERALD, h.invSlots.get(target)[1].getType(), "conflict must preserve Bob's live slot");

            h.drainRegion(viewer); // resync viewer GUI + send INSPECT_CONFLICT
            assertEquals(Material.EMERALD, gui.getItem(guiSlot).getType());
            verify(h.messages).send(viewer, Messages.INSPECT_CONFLICT, "{player}", "Bob");

            service.disable();
        }
    }

    @Test
    void targetQuitAbortsAndClosesViewerInventoryOnViewersRegion() {
        FoliaHarness h = new FoliaHarness();
        Player viewer = h.player("Staff");
        Player target = h.player("Bob");
        h.perms.get(viewer).addAll(Set.of(
                InspectionService.PERMISSION_INVSEE,
                InspectionService.PERMISSION_BYPASS));

        AtomicBoolean offlineNoticeOnViewerRegion = new AtomicBoolean(false);
        doAnswer(inv -> {
            assertEquals(h.regionOf(viewer), h.currentRegion.get(), "offline abort notice must run on Viewer's region");
            offlineNoticeOnViewerRegion.set(true);
            return null;
        }).when(h.messages).send(eq(viewer), eq(Messages.INSPECT_TARGET_OFFLINE), eq("{player}"), eq("Bob"));

        try (MockedStatic<BukkitServerThread> ignored = h.mockFolia()) {
            InspectionService service = new InspectionService(
                    h.plugin, h.repository, null, h.ioQueue::add,
                    (s, u) -> true, p -> false, u -> false, () -> h.names);
            service.enable();

            h.runInRegion(viewer, () -> service.dispatchInvsee(viewer, new String[]{"Bob"}));
            h.drainRegion(viewer);
            h.drainRegion(target);
            h.drainRegion(viewer);
            assertNotNull(h.openTop.get(viewer));

            // Bob quits on Bob's region -> closeInventory & notice scheduled onto Viewer's region.
            h.runInRegion(target, () -> service.onPlayerQuit(new PlayerQuitEvent(target, null)));
            assertFalse(offlineNoticeOnViewerRegion.get());

            h.drainRegion(viewer);
            assertTrue(offlineNoticeOnViewerRegion.get());
            verify(viewer).closeInventory();

            service.disable();
        }
    }
}
