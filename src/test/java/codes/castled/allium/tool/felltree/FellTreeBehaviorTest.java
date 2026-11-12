package codes.castled.allium.tool.felltree;

import codes.castled.allium.item.AlliumItemDefinition;
import codes.castled.allium.item.ItemHmacSigner;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.MockItemStacks;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.protection.ProtectionDecision;
import codes.castled.allium.protection.ProtectionHook;
import codes.castled.allium.protection.ProtectionService;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.tool.definition.ToolDefinition;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@SuppressWarnings("deprecation")
class FellTreeBehaviorTest {

    private static final String ID = "allium:lazy_axe";

    private ItemService items;
    private TestWorld testWorld;
    private Player player;
    private AtomicReference<ItemStack> mainHand;
    private TestScheduler scheduler;
    private final List<String> sent = new ArrayList<>();
    private final List<String> diagnostics = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final Set<TreeScanner.Pos> protectedPositions = new HashSet<>();
    private final List<org.bukkit.event.Event> events = new ArrayList<>();
    /** When set, every log the axe breaks fires a real BlockBreakEvent back into this behavior, as Player#breakBlock does. */
    private final AtomicReference<FellTreeBehavior> listening = new AtomicReference<>();
    private int reentrantEvents;

    static final class TestScheduler implements TaskScheduler {
        final List<BooleanSupplier> steps = new ArrayList<>();

        @Override
        public TaskHandle repeatOnServer(BooleanSupplier step, long delayMillis, long periodMillis) {
            steps.add(step);
            return new TaskHandle() {
                @Override public void cancel() { steps.remove(step); }
                @Override public boolean isCancelled() { return !steps.contains(step); }
            };
        }

        void tick() {
            List<BooleanSupplier> current = new ArrayList<>(steps);
            for (BooleanSupplier step : current) {
                if (!step.getAsBoolean()) {
                    steps.remove(step);
                }
            }
        }

        @Override public boolean isServerThread() { return true; }
        @Override public TaskHandle runOnServer(Runnable task) { task.run(); return TaskHandle.NONE; }
        @Override public TaskHandle runOnServerLater(Runnable task, long delayMillis) { task.run(); return TaskHandle.NONE; }
        @Override public TaskHandle runAsyncLater(Runnable task, long delayMillis) { return TaskHandle.NONE; }
        @Override public java.util.concurrent.Executor serverExecutor() { return Runnable::run; }
        @Override public java.util.concurrent.ThreadPoolExecutor boundedPool(String name, int threads, int queueCapacity) {
            return (java.util.concurrent.ThreadPoolExecutor) java.util.concurrent.Executors.newFixedThreadPool(1);
        }
    }

    static final class TestWorld {
        final Map<TreeScanner.Pos, Material> materials = new HashMap<>();
        final Set<TreeScanner.Pos> persistentLeaves = new HashSet<>();
        final Set<Long> unloadedChunks = new HashSet<>();
        final List<TreeScanner.Pos> brokenBlocks = new ArrayList<>();
        final World mockWorld = mock(World.class);

        TestWorld() {
            when(mockWorld.getMinHeight()).thenReturn(-64);
            when(mockWorld.getMaxHeight()).thenReturn(320);
            when(mockWorld.isChunkLoaded(anyInt(), anyInt())).thenAnswer(inv -> {
                int cx = inv.getArgument(0);
                int cz = inv.getArgument(1);
                return !unloadedChunks.contains(FellTreeBehavior.chunkKey(cx, cz));
            });
            when(mockWorld.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
                int x = inv.getArgument(0);
                int y = inv.getArgument(1);
                int z = inv.getArgument(2);
                TreeScanner.Pos pos = new TreeScanner.Pos(x, y, z);
                Block block = mock(Block.class);
                when(block.getX()).thenReturn(x);
                when(block.getY()).thenReturn(y);
                when(block.getZ()).thenReturn(z);
                when(block.getWorld()).thenReturn(mockWorld);
                when(block.getLocation()).thenReturn(new Location(mockWorld, x, y, z));
                when(block.getType()).thenAnswer(i -> materials.getOrDefault(pos, y <= 0 ? Material.GRASS_BLOCK : Material.AIR));
                BlockData blockData;
                Material mat = materials.getOrDefault(pos, y <= 0 ? Material.GRASS_BLOCK : Material.AIR);
                if (mat.name().endsWith("_LEAVES")) {
                    Leaves leaves = mock(Leaves.class);
                    when(leaves.isPersistent()).thenReturn(persistentLeaves.contains(pos));
                    when(leaves.getDistance()).thenReturn(1);
                    blockData = leaves;
                } else {
                    blockData = mock(BlockData.class);
                }
                when(block.getBlockData()).thenReturn(blockData);
                return block;
            });
        }

        void setOak(int height, boolean playerPlacedLeaves) {
            for (int y = 1; y <= height; y++) {
                materials.put(new TreeScanner.Pos(0, y, 0), Material.OAK_LOG);
            }
            for (int y = height - 1; y <= height + 1; y++) {
                for (int x = -2; x <= 2; x++) {
                    for (int z = -2; z <= 2; z++) {
                        TreeScanner.Pos pos = new TreeScanner.Pos(x, y, z);
                        if ((x != 0 || z != 0 || y > height) && !materials.containsKey(pos)) {
                            materials.put(pos, Material.OAK_LEAVES);
                            if (playerPlacedLeaves) {
                                persistentLeaves.add(pos);
                            }
                        }
                    }
                }
            }
        }
    }

    @BeforeEach
    void setUp() {
        items = new ItemService(MockItemStacks::create, ItemHmacSigner.randomEphemeral(), msg -> { });
        items.registerAlliumItem(new AlliumItemDefinition("lazy_axe", Material.NETHERITE_AXE, ID, false, 2577050,
                "Lazy Axe", Map.of(), true));
        items.registerSignedBehavior(ID);
        items.setPreferredAppearanceForBehavior(ID, ProviderKey.allium("lazy_axe"));

        testWorld = new TestWorld();
        scheduler = new TestScheduler();
        mainHand = new AtomicReference<>();

        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getItemInMainHand()).thenAnswer(inv -> mainHand.get());
        doAnswer(inv -> {
            mainHand.set(inv.getArgument(0));
            return null;
        }).when(inventory).setItemInMainHand(any());

        player = mock(Player.class);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Chopper");
        when(player.isOnline()).thenReturn(true);
        when(player.isDead()).thenReturn(false);
        when(player.isSneaking()).thenReturn(false);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getWorld()).thenReturn(testWorld.mockWorld);
        when(player.hasPermission("allium.treeaxe.use")).thenReturn(true);

        doAnswer(inv -> {
            Block b = inv.getArgument(0);
            testWorld.brokenBlocks.add(new TreeScanner.Pos(b.getX(), b.getY(), b.getZ()));
            FellTreeBehavior self = listening.get();
            if (self != null) {
                reentrantEvents++;
                self.onBlockBreak(new BlockBreakEvent(b, player));
            }
            testWorld.materials.put(new TreeScanner.Pos(b.getX(), b.getY(), b.getZ()), Material.AIR);
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand != null && hand.getItemMeta() instanceof Damageable dmg) {
                dmg.setDamage(dmg.getDamage() + 1);
                hand.setItemMeta(dmg);
                if (dmg.getDamage() >= hand.getType().getMaxDurability()) {
                    player.getInventory().setItemInMainHand(null);
                }
            }
            return true;
        }).when(player).breakBlock(any(Block.class));

        mainHand.set(items.createForBehavior(ID, 1).itemStack().orElseThrow());
    }

    private FellTreeBehavior newBehavior(int maxBlocks, int blocksPerTick, boolean stopBeforeBreak, String sneak) {
        ProtectionHook hook = new ProtectionHook() {
            @Override public String name() { return "TestClaims"; }
            @Override public ProtectionDecision checkEntityInteraction(Player p, org.bukkit.entity.Entity target, org.bukkit.event.Event cause) {
                return ProtectionDecision.allow();
            }
            @Override public ProtectionDecision checkBlockBreak(Player p, Location loc, org.bukkit.event.Event cause) {
                TreeScanner.Pos pos = new TreeScanner.Pos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                if (protectedPositions.contains(pos)) {
                    return ProtectionDecision.deny("TestClaims", "protected");
                }
                return ProtectionDecision.allow();
            }
        };
        ProtectionService protection = new ProtectionService(List.of(hook), null, diagnostics::add);
        FellTreeParameters parameters = new FellTreeParameters(
                List.of(TreeFamily.ALL.get("overworld_logs")), true, maxBlocks, 8, 48, 4,
                blocksPerTick, true, stopBeforeBreak, sneak, 0L, true
        );
        FellTreeBehaviorType type = new FellTreeBehaviorType();
        ToolDefinition definition = new ToolDefinition(
                "tools/lazy_axe.yml", ID, "Lazy Axe", FellTreeBehaviorType.ID,
                true, null, ProviderKey.allium("lazy_axe"), List.of(),
                Map.of("use", "allium.treeaxe.use"),
                type.messageSlots(), Map.of(), Map.of(), parameters
        );
        RandomGenerator random = new RandomGenerator() {
            public long nextLong() { return 0L; }
            public double nextDouble() { return 0.5D; }
        };
        return new FellTreeBehavior(
                items, protection, scheduler, (p, msg, rep) -> sent.add(msg.key()),
                diagnostics::add, clock::get, random, id -> id.equals(player.getUniqueId()) ? player : null,
                events::add, List.of(new FellTreeTool(definition))
        );
    }

    @Test
    void naturalTreeFellsBottomUp() {
        FellTreeBehavior behavior = newBehavior(256, 2, true, "disables");
        testWorld.setOak(5, false);

        Block start = testWorld.mockWorld.getBlockAt(0, 1, 0);
        BlockBreakEvent event = new BlockBreakEvent(start, player);
        behavior.onBlockBreak(event);

        assertEquals(1, scheduler.steps.size(), "job scheduled");
        scheduler.tick(); // tick 1 breaks y=2, y=3
        assertEquals(List.of(new TreeScanner.Pos(0, 2, 0), new TreeScanner.Pos(0, 3, 0)), testWorld.brokenBlocks);

        scheduler.tick(); // tick 2 breaks y=4, y=5
        assertEquals(List.of(
                new TreeScanner.Pos(0, 2, 0), new TreeScanner.Pos(0, 3, 0),
                new TreeScanner.Pos(0, 4, 0), new TreeScanner.Pos(0, 5, 0)
        ), testWorld.brokenBlocks);

        assertEquals(0, scheduler.steps.size(), "job finished");
    }

    @Test
    void logsBrokenByTheAxeNeverStartAnotherFell() {
        FellTreeBehavior behavior = newBehavior(256, 2, true, "disables");
        listening.set(behavior);
        testWorld.setOak(5, false);
        // Without the guard, each re-entrant event would scan what is left of the tree and start a
        // second fell (or report busy while the first runs).
        behavior.onBlockBreak(new BlockBreakEvent(testWorld.mockWorld.getBlockAt(0, 1, 0), player));
        assertEquals(1, events.size(), "one fell event");
        assertEquals(1, scheduler.steps.size(), "one job");

        scheduler.tick();
        scheduler.tick();
        assertEquals(4, testWorld.brokenBlocks.size(), "the whole tree");
        assertEquals(4, reentrantEvents, "every log broken by the axe fired a BlockBreakEvent into the behavior");
        assertEquals(1, events.size(), "and none of them started a fell");
        assertTrue(sent.isEmpty(), "nor reported busy: " + sent);
        assertFalse(behavior.isFelling(player.getUniqueId()), "the guard is released after each break");
        assertEquals(0, scheduler.steps.size());

        // The guard is per break, not per job: the next tree the player chops fells normally.
        testWorld.setOak(5, false);
        behavior.onBlockBreak(new BlockBreakEvent(testWorld.mockWorld.getBlockAt(0, 1, 0), player));
        assertEquals(2, events.size(), "a later manual break fells again");
    }

    @Test
    void aCancelledFellEventLeavesTheTreeStanding() {
        FellTreeBehavior behavior = new FellTreeBehavior(items, new ProtectionService(List.of(), null, diagnostics::add), scheduler,
                (p, msg, rep) -> sent.add(msg.key()), diagnostics::add, clock::get, RandomGenerator.of("L64X128MixRandom"),
                id -> player, event -> ((LazyAxeFellEvent) event).setCancelled(true),
                newBehavior(256, 2, true, "disables").tools().stream().distinct().toList());
        testWorld.setOak(5, false);
        behavior.onBlockBreak(new BlockBreakEvent(testWorld.mockWorld.getBlockAt(0, 1, 0), player));
        assertEquals(0, scheduler.steps.size());
        assertTrue(testWorld.brokenBlocks.isEmpty());
    }

    @Test
    void stopBeforeBreakTrueStopsAt1DurabilityAndPreservesAxe() {
        FellTreeBehavior behavior = newBehavior(256, 1, true, "disables");
        testWorld.setOak(5, false);

        // Netherite axe max durability 2031. Set damage to 2030 (1 remaining durability).
        ItemStack axe = mainHand.get();
        Damageable dmg = (Damageable) axe.getItemMeta();
        dmg.setDamage(2030);
        axe.setItemMeta(dmg);

        Block start = testWorld.mockWorld.getBlockAt(0, 1, 0);
        behavior.onBlockBreak(new BlockBreakEvent(start, player));

        assertEquals(1, scheduler.steps.size());
        scheduler.tick(); // Checks durability before breaking next log: stops at 1 durability!

        assertTrue(testWorld.brokenBlocks.isEmpty(), "no subsequent logs broken");
        assertEquals(0, scheduler.steps.size(), "job stopped");
        assertEquals(List.of("tool.lazy-axe.stopped-axe"), sent);
        assertNotNull(player.getInventory().getItemInMainHand(), "axe was not destroyed");
        assertEquals(2030, ((Damageable) player.getInventory().getItemInMainHand().getItemMeta()).getDamage());
    }

    @Test
    void stopBeforeBreakFalseAllowsAxeToBreak() {
        FellTreeBehavior behavior = newBehavior(256, 1, false, "disables");
        testWorld.setOak(5, false);

        // Set damage to 2030 (1 durability left). Next break will deal 1 damage and break the axe.
        ItemStack axe = mainHand.get();
        Damageable dmg = (Damageable) axe.getItemMeta();
        dmg.setDamage(2030);
        axe.setItemMeta(dmg);

        Block start = testWorld.mockWorld.getBlockAt(0, 1, 0);
        behavior.onBlockBreak(new BlockBreakEvent(start, player));

        assertEquals(1, scheduler.steps.size());
        scheduler.tick(); // breaks y=2, axe damage reaches 2031 and axe breaks!

        assertEquals(List.of(new TreeScanner.Pos(0, 2, 0)), testWorld.brokenBlocks);
        assertNull(player.getInventory().getItemInMainHand(), "axe broke and was cleared");

        scheduler.tick(); // notices axe is gone, job ends cleanly
        assertEquals(0, scheduler.steps.size());
    }

    @Test
    void chunkUnloadCancelsJobImmediately() {
        FellTreeBehavior behavior = newBehavior(256, 1, true, "disables");
        testWorld.setOak(5, false);

        Block start = testWorld.mockWorld.getBlockAt(0, 1, 0);
        behavior.onBlockBreak(new BlockBreakEvent(start, player));

        assertEquals(1, scheduler.steps.size());

        // Chunk (0, 0) unloads
        behavior.onChunkUnload(testWorld.mockWorld, 0, 0);

        assertEquals(0, scheduler.steps.size(), "job cancelled by chunk unload");
        assertTrue(testWorld.brokenBlocks.isEmpty(), "no subsequent logs broken after chunk unload");
    }

    @Test
    void recheckBeforeBreakingSkipsAlteredBlock() {
        FellTreeBehavior behavior = newBehavior(256, 1, true, "disables");
        testWorld.setOak(5, false);

        Block start = testWorld.mockWorld.getBlockAt(0, 1, 0);
        behavior.onBlockBreak(new BlockBreakEvent(start, player));

        scheduler.tick(); // breaks y=2
        assertEquals(List.of(new TreeScanner.Pos(0, 2, 0)), testWorld.brokenBlocks);

        // An external actor replaces log at y=3 with cobblestone
        testWorld.materials.put(new TreeScanner.Pos(0, 3, 0), Material.COBBLESTONE);

        scheduler.tick(); // examines y=3: not a log of the family, so skips it! Breaks y=4!
        assertEquals(List.of(new TreeScanner.Pos(0, 2, 0), new TreeScanner.Pos(0, 4, 0)), testWorld.brokenBlocks);
        assertEquals(Material.COBBLESTONE, testWorld.materials.get(new TreeScanner.Pos(0, 3, 0)), "cobblestone untouched");
    }

    @Test
    void playerBuiltLogStructureIsNotFelled() {
        FellTreeBehavior behavior = newBehavior(256, 2, true, "disables");
        // Structure with persistent (player-placed) leaves
        testWorld.setOak(5, true);

        Block start = testWorld.mockWorld.getBlockAt(0, 1, 0);
        behavior.onBlockBreak(new BlockBreakEvent(start, player));

        assertEquals(0, scheduler.steps.size(), "no fell job scheduled for player structure");
    }

    @Test
    void structureGuardStopsFellWhenTouchingBuildingBlock() {
        FellTreeBehavior behavior = newBehavior(256, 2, true, "disables");
        testWorld.setOak(5, false);
        // A fence touching the trunk
        testWorld.materials.put(new TreeScanner.Pos(1, 2, 0), Material.OAK_FENCE);

        Block start = testWorld.mockWorld.getBlockAt(0, 1, 0);
        behavior.onBlockBreak(new BlockBreakEvent(start, player));

        assertEquals(0, scheduler.steps.size(), "structure guard stops fell");
    }

    @Test
    void treeOverCapSendsTooLargeMessage() {
        FellTreeBehavior behavior = newBehavior(3, 2, true, "disables"); // cap is 3
        testWorld.setOak(5, false); // 5 logs

        Block start = testWorld.mockWorld.getBlockAt(0, 1, 0);
        behavior.onBlockBreak(new BlockBreakEvent(start, player));

        assertEquals(0, scheduler.steps.size(), "over cap does not fell");
        assertEquals(List.of("tool.lazy-axe.too-large"), sent);
    }

    @Test
    void claimBorderSparesProtectedLogs() {
        FellTreeBehavior behavior = newBehavior(256, 1, true, "disables");
        testWorld.setOak(5, false);
        protectedPositions.add(new TreeScanner.Pos(0, 3, 0)); // y=3 is in a claim without build trust

        Block start = testWorld.mockWorld.getBlockAt(0, 1, 0);
        behavior.onBlockBreak(new BlockBreakEvent(start, player));

        scheduler.tick(); // breaks y=2
        assertEquals(List.of(new TreeScanner.Pos(0, 2, 0)), testWorld.brokenBlocks);

        scheduler.tick(); // tries y=3: claim denies, log is spared, skips to y=4
        assertEquals(List.of(new TreeScanner.Pos(0, 2, 0), new TreeScanner.Pos(0, 4, 0)), testWorld.brokenBlocks);
        assertEquals(Material.OAK_LOG, testWorld.materials.get(new TreeScanner.Pos(0, 3, 0)), "protected log stays intact");
    }

    @Test
    void sneakingDisablesFellingWhenConfigured() {
        FellTreeBehavior behavior = newBehavior(256, 2, true, "disables");
        testWorld.setOak(5, false);
        when(player.isSneaking()).thenReturn(true);

        Block start = testWorld.mockWorld.getBlockAt(0, 1, 0);
        behavior.onBlockBreak(new BlockBreakEvent(start, player));

        assertEquals(0, scheduler.steps.size(), "sneaking disables fell");
    }

    @Test
    void legacyTreeAxeAdoptedInPlaceAtRuntime() {
        FellTreeBehavior behavior = newBehavior(256, 2, true, "disables");
        testWorld.setOak(5, false);

        // Pre-recode tree_axe stack (netherite axe with allium:custom_item_id = "tree_axe")
        ItemStack legacy = MockItemStacks.create(Material.NETHERITE_AXE);
        legacy.getItemMeta().getPersistentDataContainer().set(
                new NamespacedKey("allium", "custom_item_id"), PersistentDataType.STRING, "tree_axe"
        );
        mainHand.set(legacy);

        Block start = testWorld.mockWorld.getBlockAt(0, 1, 0);
        behavior.onBlockBreak(new BlockBreakEvent(start, player));

        ItemStack adopted = mainHand.get();
        assertNotNull(adopted);
        assertTrue(items.identify(adopted).isPresent());
        assertEquals(ID, items.identifyBehavior(adopted).orElse(null));
        assertEquals(1, scheduler.steps.size(), "fell job started after in-place adoption");
        assertTrue(diagnostics.stream().anyMatch(d -> d.contains("Imported legacy Lazy Axe")));
    }
}
