package codes.castled.allium.tool;

import codes.castled.allium.creative.manage.PermissionBackend;
import codes.castled.allium.item.AlliumItemDefinition;
import codes.castled.allium.item.ItemHmacSigner;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.MockItemStacks;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.message.Messages;
import codes.castled.allium.protection.ProtectionDecision;
import codes.castled.allium.protection.ProtectionService;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.tool.definition.ToolDefinition;
import codes.castled.allium.tool.definition.ToolEnvironment;
import codes.castled.allium.tool.felltree.FellTreeBehavior;
import codes.castled.allium.tool.felltree.FellTreeBehaviorType;
import codes.castled.allium.tool.felltree.FellTreeParameters;
import codes.castled.allium.tool.felltree.FellTreeTool;
import codes.castled.allium.tool.felltree.TreeFamily;
import codes.castled.allium.tool.powertool.PermissionGrantAction;
import codes.castled.allium.tool.powertool.PowerToolAction;
import codes.castled.allium.tool.powertool.PowerToolBehavior;
import codes.castled.allium.tool.powertool.PowerToolBinding;
import codes.castled.allium.tool.powertool.PowerToolContext;
import codes.castled.allium.tool.powertool.PowerToolCooldownScope;
import codes.castled.allium.tool.powertool.PowerToolParameters;
import codes.castled.allium.tool.powertool.PowerToolSettings;
import codes.castled.allium.tool.powertool.PowerToolTrigger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("deprecation")
class ToolsFoliaTest {

    @TempDir Path tempDir;

    private static final ToolEnvironment ENV = new ToolEnvironment() {
        @Override public boolean isCoreItem(String localId) { return false; }
        @Override public Optional<String> coreItemBehavior(String localId) { return Optional.empty(); }
        @Override public boolean isItemMaterial(String material) { return true; }
        @Override public boolean isEntityType(String type) { return true; }
        @Override public boolean isParticle(String particle) { return true; }
        @Override public boolean hasLangKey(String key) { return true; }
    };

    private final ArrayDeque<Runnable> playerTasks = new ArrayDeque<>();
    private final ArrayDeque<Runnable> playerRetires = new ArrayDeque<>();
    private final ArrayDeque<Runnable> globalTasks = new ArrayDeque<>();

    private Plugin plugin;
    private TaskScheduler scheduler;
    private ProtectionService protection;
    private ItemService items;
    private Player player;
    private PlayerInventory inventory;
    private World world;
    private ServerThread playerOwner;
    private ServerThread chunkRegion;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        scheduler = mock(TaskScheduler.class);
        protection = mock(ProtectionService.class);
        items = new ItemService(MockItemStacks::create, ItemHmacSigner.randomEphemeral(), msg -> {});
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);

        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Alice");
        when(player.isOnline()).thenReturn(true);
        when(player.isDead()).thenReturn(false);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());

        when(protection.checkBlockBreak(any(), any(), anyString(), any())).thenReturn(ProtectionDecision.allow());
        when(scheduler.isServerThread()).thenReturn(false);
        when(scheduler.runOnServer(any(Runnable.class))).thenAnswer(c -> {
            globalTasks.add(c.getArgument(0));
            return mock(TaskHandle.class);
        });

        playerOwner = mock(ServerThread.class);
        chunkRegion = mock(ServerThread.class);
        when(playerOwner.isServerThread()).thenReturn(true);
        when(chunkRegion.isServerThread()).thenReturn(true);
        when(scheduler.runOn(eq(playerOwner), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(c -> {
            playerTasks.add(c.getArgument(1));
            playerRetires.add(c.getArgument(2));
            return mock(TaskHandle.class);
        });
    }

    @Test
    void permissionGrantActionRoutesSettlementAndRefundOntoPlayerEntityRegion() {
        PermissionBackend backend = mock(PermissionBackend.class);
        when(backend.canWrite()).thenReturn(true);
        when(backend.holds(any(), eq("example.vip"))).thenReturn(false);
        CompletableFuture<Void> write = new CompletableFuture<>();
        when(backend.writeNode(any(), eq("example.vip"), eq(true), any())).thenReturn(write);

        ItemStack refundCopy = mock(ItemStack.class);
        PermissionGrantAction.Notifier notifier = mock(PermissionGrantAction.Notifier.class);
        PermissionGrantAction grant = new PermissionGrantAction(
                plugin, () -> backend, scheduler, notifier, m -> {}, m -> {}, new Random(1), id -> refundCopy);

        PowerToolContext ctx = new PowerToolContext(player, "allium:vip_voucher", null,
                mock(ItemStack.class), PowerToolBinding.HAND_MAIN, PowerToolTrigger.RIGHT, null, null, null);
        PowerToolAction action = new PowerToolAction(PowerToolAction.TYPE_GRANT, "example.vip", true, 0L,
                PowerToolCooldownScope.PLAYER, null, 0L, null, Map.of());

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, player)).thenReturn(playerOwner);

            assertTrue(grant.execute(ctx, action));
            write.completeExceptionally(new IllegalStateException("lp db down"));

            assertEquals(1, playerTasks.size(), "grant settlement/refund must run on player's entity region");
            assertTrue(globalTasks.isEmpty());
            verify(inventory, never()).addItem(any(ItemStack.class));

            playerTasks.remove().run();
            verify(inventory).addItem(refundCopy);
            verify(notifier).send(eq(player), eq(Messages.POWERTOOL_GRANT_REFUNDED), eq("{permission}"), eq("example.vip"));
            verify(scheduler, never()).runOnServer(any());
        }
    }

    @Test
    void powerToolDelayedActionRunsOnPlayerEntityRegionAndConsoleActionUsesGlobalScheduler() {
        PowerToolSettings cfg = new PowerToolSettings(true, List.of("say "), true);
        PowerToolBehavior behavior = new PowerToolBehavior(
                plugin, items, protection, scheduler, (p, m, r) -> {}, m -> {}, System::currentTimeMillis,
                new Random(1), tempDir.toFile(), () -> ENV, cfg);

        AtomicBoolean delayedRan = new AtomicBoolean();
        behavior.actions().register("custom_delayed", (c, a) -> {
            delayedRan.set(true);
            return true;
        });

        PowerToolAction delayed = new PowerToolAction("custom_delayed", null, false, 0L,
                PowerToolCooldownScope.PLAYER, null, 5L, null, Map.of());
        PowerToolAction consoleAction = new PowerToolAction(PowerToolAction.TYPE_CONSOLE, "say hi {player}", false, 0L,
                PowerToolCooldownScope.PLAYER, null, 0L, null, Map.of());

        PowerToolBinding binding = PowerToolBinding.empty(PowerToolTrigger.RIGHT).withActionAdded(delayed);
        ItemStack stick = MockItemStacks.create(Material.STICK);
        assertTrue(behavior.writeInline(stick, PowerToolParameters.EMPTY.withBinding(PowerToolTrigger.RIGHT, binding), null));

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS);
             var bukkit = mockStatic(Bukkit.class)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, player)).thenReturn(playerOwner);
            ConsoleCommandSender console = mock(ConsoleCommandSender.class);
            bukkit.when(Bukkit::getConsoleSender).thenReturn(console);

            assertTrue(behavior.handleTrigger(player, stick, PowerToolBinding.HAND_MAIN, PowerToolTrigger.RIGHT, null, null, null, null));
            assertEquals(1, playerTasks.size(), "delayed powertool action must schedule on player's entity region");
            assertFalse(delayedRan.get());
            verify(scheduler, never()).runOnServerLater(any(), anyLong());

            playerTasks.remove().run();
            assertTrue(delayedRan.get());

            // Console command action must route to global scheduler on Folia
            PowerToolContext ctx = new PowerToolContext(player, "allium:inline", null, stick, PowerToolBinding.HAND_MAIN,
                    PowerToolTrigger.RIGHT, null, null, null);
            assertTrue(behavior.actions().get(PowerToolAction.TYPE_CONSOLE).execute(ctx, consoleAction));
            assertEquals(1, globalTasks.size(), "console powertool command must dispatch on global scheduler");
            globalTasks.remove().run();
            bukkit.verify(() -> Bukkit.dispatchCommand(console, "say hi Alice"));
        }
    }

    @Test
    void fellTreeBehaviorSchedulesPerTickStepsOnPlayerEntityRegion() {
        String id = "allium:lazy_axe";
        items.registerAlliumItem(new AlliumItemDefinition("lazy_axe", Material.NETHERITE_AXE, id, false, 2577050,
                "Lazy Axe", Map.of(), true));
        items.registerSignedBehavior(id);
        items.setPreferredAppearanceForBehavior(id, ProviderKey.allium("lazy_axe"));

        FellTreeParameters params = new FellTreeParameters(
                List.of(TreeFamily.ALL.get("overworld_logs")), true, 16, 4, 16, 1,
                2, true, true, "disables", 0L, true);
        FellTreeBehaviorType type = new FellTreeBehaviorType();
        ToolDefinition def = new ToolDefinition(
                "tools/lazy_axe.yml", id, "Lazy Axe", FellTreeBehaviorType.ID,
                true, null, ProviderKey.allium("lazy_axe"), List.of(),
                Map.of("use", "allium.treeaxe.use"),
                type.messageSlots(), Map.of(), Map.of(), params);
        FellTreeTool tool = new FellTreeTool(def);

        ItemStack minted = items.createForBehavior(id, 1).itemStack().orElseThrow();
        when(inventory.getItemInMainHand()).thenReturn(minted);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);

        Block dirt = block(0, 63, 0, Material.DIRT);
        Block base = block(0, 64, 0, Material.OAK_LOG);
        Block log1 = block(0, 65, 0, Material.OAK_LOG);
        Block log2 = block(0, 66, 0, Material.OAK_LOG);
        Block leaf = block(0, 67, 0, Material.OAK_LEAVES);
        Leaves leavesData = mock(Leaves.class);
        when(leavesData.isPersistent()).thenReturn(false);
        when(leavesData.getDistance()).thenReturn(1);
        when(leaf.getBlockData()).thenReturn(leavesData);
        Block air = block(0, 0, 0, Material.AIR);

        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(c -> {
            int x = c.getArgument(0), y = c.getArgument(1), z = c.getArgument(2);
            if (x == 0 && z == 0 && y == 63) return dirt;
            if (x == 0 && z == 0 && y == 64) return base;
            if (x == 0 && z == 0 && y == 65) return log1;
            if (x == 0 && z == 0 && y == 66) return log2;
            if (x == 0 && z == 0 && y == 67) return leaf;
            return air;
        });

        FellTreeBehavior fell = new FellTreeBehavior(
                plugin, items, protection, scheduler, (p, m, r) -> {}, m -> {},
                System::currentTimeMillis, new Random(1), uuid -> player, ev -> {}, List.of(tool));

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, player)).thenReturn(playerOwner);
            platform.when(() -> BukkitServerThread.region(eq(plugin), eq(world), anyInt(), anyInt())).thenReturn(chunkRegion);

            fell.onBlockBreak(new BlockBreakEvent(base, player));
            assertEquals(1, playerTasks.size(), "FellTree must schedule ticks on player's entity region");
            verify(scheduler, never()).repeatOnServer(any(), anyLong(), anyLong());

            playerTasks.remove().run();
            verify(player).breakBlock(log1);
            verify(player).breakBlock(log2);
            assertTrue(playerTasks.isEmpty(), "job completes once all logs are broken");
        }
    }

    private Block block(int x, int y, int z, Material type) {
        Block b = mock(Block.class);
        when(b.getWorld()).thenReturn(world);
        when(b.getX()).thenReturn(x);
        when(b.getY()).thenReturn(y);
        when(b.getZ()).thenReturn(z);
        when(b.getType()).thenReturn(type);
        when(b.getLocation()).thenReturn(new Location(world, x, y, z));
        return b;
    }
}
