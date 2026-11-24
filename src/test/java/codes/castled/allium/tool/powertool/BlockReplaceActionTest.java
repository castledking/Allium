package codes.castled.allium.tool.powertool;

import codes.castled.allium.protection.ProtectionDecision;
import codes.castled.allium.protection.ProtectionHook;
import codes.castled.allium.protection.ProtectionService;
import org.bukkit.Axis;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class BlockReplaceActionTest {

    private World world;
    private Player player;
    private PlayerInventory inventory;
    private ItemStack[] storage;
    private ItemStack offhand;
    private Block block;
    private Material blockType;
    private AtomicBoolean protectionAllowed;
    private BlockReplaceAction actionHandler;

    @BeforeEach
    void setUp() {
        world = mock(World.class);
        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        storage = new ItemStack[36];
        offhand = null;
        block = mock(Block.class);
        blockType = Material.STONE;
        protectionAllowed = new AtomicBoolean(true);

        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Builder");
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0.5, 64, 0.5));
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getInventory()).thenReturn(inventory);

        when(inventory.getHeldItemSlot()).thenReturn(0);
        when(inventory.getStorageContents()).thenAnswer(inv -> storage);
        when(inventory.getItem(anyInt())).thenAnswer(inv -> storage[(int) inv.getArgument(0)]);
        doAnswer(inv -> {
            storage[(int) inv.getArgument(0)] = inv.getArgument(1);
            return null;
        }).when(inventory).setItem(anyInt(), any());
        when(inventory.getItemInOffHand()).thenAnswer(inv -> offhand);
        doAnswer(inv -> {
            offhand = inv.getArgument(0);
            return null;
        }).when(inventory).setItemInOffHand(any());
        when(inventory.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());

        when(block.getWorld()).thenReturn(world);
        when(block.getLocation()).thenReturn(new Location(world, 2, 64, 2));
        when(block.getX()).thenReturn(2);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(2);
        when(block.getType()).thenAnswer(inv -> blockType);
        doAnswer(inv -> {
            blockType = inv.getArgument(0);
            return null;
        }).when(block).setType(any(Material.class), anyBoolean());

        ProtectionHook hook = new ProtectionHook() {
            @Override public String name() { return "test"; }
            @Override public ProtectionDecision checkEntityInteraction(Player p, Entity target, Event cause) { return ProtectionDecision.allow(); }
            @Override public ProtectionDecision checkBlockBreak(Player p, Location loc, Event cause) {
                return protectionAllowed.get() ? ProtectionDecision.allow() : ProtectionDecision.deny("test", "denied");
            }
        };
        ProtectionService protection = new ProtectionService(List.of(hook), null, msg -> {});
        actionHandler = new BlockReplaceAction(null, protection, new Random(42L));
    }

    private PowerToolContext context(Block target) {
        return new PowerToolContext(
                player,
                "allium:trowel",
                null,
                new ItemStack(Material.IRON_SHOVEL, 1),
                PowerToolBinding.HAND_MAIN,
                PowerToolTrigger.RIGHT,
                target,
                null,
                null
        );
    }

    private static PowerToolAction replaceAction(String value, Map<String, Object> params) {
        return new PowerToolAction(
                PowerToolAction.TYPE_BLOCK_REPLACE,
                value,
                false,
                0L,
                PowerToolCooldownScope.PLAYER,
                null,
                0L,
                null,
                params,
                true
        );
    }

    @Test
    void hotbarSourcePullsOnlyFromHotbarConsumesReturnsOldBlockAndSkipsSameAndToolSlot() {
        storage[0] = new ItemStack(Material.IRON_SHOVEL, 1); // held tool slot (excluded)
        storage[1] = new ItemStack(Material.STONE, 10);      // matches current block (skipped by skip-same=true)
        storage[2] = new ItemStack(Material.COBBLESTONE, 5); // eligible hotbar slot
        storage[12] = new ItemStack(Material.DIAMOND_BLOCK, 10); // main inventory (must be ignored for hotbar)

        boolean replaced = actionHandler.execute(context(block), replaceAction(null, Map.of("source", "hotbar")));
        assertTrue(replaced);
        assertEquals(Material.COBBLESTONE, blockType);
        assertEquals(4, storage[2].getAmount(), "consumed 1 cobblestone from hotbar");
        assertEquals(10, storage[12].getAmount(), "did not touch main inventory slot");
        verify(inventory).addItem(argThat((ItemStack returned) ->
                returned.getType() == Material.STONE && returned.getAmount() == 1));
    }

    @Test
    void inventorySourcePullsFromFullStorageAndDropsNaturallyWhenConfigured() {
        storage[0] = new ItemStack(Material.IRON_SHOVEL, 1);
        storage[20] = new ItemStack(Material.ANDESITE, 1);
        when(block.getDrops(any(), any())).thenReturn(List.of(new ItemStack(Material.COBBLESTONE, 1)));

        boolean replaced = actionHandler.execute(context(block), replaceAction(null, Map.of(
                "source", "inventory",
                "drop-old", "natural"
        )));
        assertTrue(replaced);
        assertEquals(Material.ANDESITE, blockType);
        assertNull(storage[20], "single item stack cleared when consumed");
        verify(world).dropItemNaturally(any(Location.class), argThat((ItemStack drop) ->
                drop.getType() == Material.COBBLESTONE && drop.getAmount() == 1));
        verify(inventory, never()).addItem(any(ItemStack[].class));
    }

    @Test
    void offhandSourcePullsAndConsumesFromOffhand() {
        offhand = new ItemStack(Material.OAK_PLANKS, 2);
        boolean replaced = actionHandler.execute(context(block), replaceAction(null, Map.of("source", "offhand")));
        assertTrue(replaced);
        assertEquals(Material.OAK_PLANKS, blockType);
        assertEquals(1, offhand.getAmount());
    }

    @Test
    void listSourceSupportsSequentialCyclingWeightedRandomAndOptionalSurvivalConsume() {
        // Sequential cycling through hardcoded list without consuming (default for list).
        PowerToolAction sequential = replaceAction("oak_planks,spruce_planks,birch_planks", Map.of(
                "source", "list",
                "selection", "sequential",
                "skip-same", false
        ));
        List<Material> cycle = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            assertTrue(actionHandler.execute(context(block), sequential));
            cycle.add(blockType);
        }
        assertEquals(List.of(Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.BIRCH_PLANKS, Material.OAK_PLANKS), cycle);
        verify(inventory, never()).setItem(anyInt(), any());

        // Weighted random list defaults to source=list when value/blocks are supplied.
        PowerToolAction weighted = replaceAction("cobblestone:9,mossy_cobblestone:1", Map.of("skip-same", false));
        int cobble = 0;
        int mossy = 0;
        for (int i = 0; i < 200; i++) {
            assertTrue(actionHandler.execute(context(block), weighted));
            if (blockType == Material.COBBLESTONE) cobble++;
            else if (blockType == Material.MOSSY_COBBLESTONE) mossy++;
            else fail("Unexpected material " + blockType);
        }
        assertTrue(cobble > mossy * 3, "expected weighted cobblestone (" + cobble + ") >> mossy (" + mossy + ")");

        // List with consume=true skips blocks not in inventory and consumes one that is present.
        blockType = Material.STONE;
        storage[5] = new ItemStack(Material.SPRUCE_PLANKS, 3);
        PowerToolAction consumeList = replaceAction("oak_planks,spruce_planks", Map.of(
                "source", "list",
                "consume", true
        ));
        assertTrue(actionHandler.execute(context(block), consumeList));
        assertEquals(Material.SPRUCE_PLANKS, blockType);
        assertEquals(2, storage[5].getAmount());
    }

    @Test
    void creativeModeDoesNotConsumeOrReturnReplacedBlocks() {
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        storage[1] = new ItemStack(Material.BRICKS, 1);

        assertTrue(actionHandler.execute(context(block), replaceAction(null, Map.of("source", "hotbar"))));
        assertEquals(Material.BRICKS, blockType);
        assertEquals(1, storage[1].getAmount(), "creative never consumes blocks");
        verify(inventory, never()).addItem(any(ItemStack[].class));
    }

    @Test
    void respectsProtectionContainersUnbreakablesAllowAndDenyLists() {
        storage[1] = new ItemStack(Material.OAK_PLANKS, 16);
        PowerToolAction hotbar = replaceAction(null, Map.of("source", "hotbar"));

        // Clicking air does nothing.
        assertFalse(actionHandler.execute(context(null), hotbar));

        // Protection denial blocks replacement.
        protectionAllowed.set(false);
        assertFalse(actionHandler.execute(context(block), hotbar));
        assertEquals(Material.STONE, blockType);
        protectionAllowed.set(true);

        // Bedrock / unbreakable blocks refused by default.
        blockType = Material.BEDROCK;
        assertFalse(actionHandler.execute(context(block), hotbar));
        assertEquals(Material.BEDROCK, blockType);

        // Containers refused by default.
        blockType = Material.CHEST;
        when(block.getState()).thenReturn(mock(Chest.class));
        assertFalse(actionHandler.execute(context(block), hotbar));
        when(block.getState()).thenReturn(null);

        // allow-replace whitelist and deny-replace blacklist enforced.
        blockType = Material.DIRT;
        assertFalse(actionHandler.execute(context(block), replaceAction(null, Map.of(
                "source", "hotbar",
                "allow-replace", List.of("stone", "cobblestone")
        ))));
        assertFalse(actionHandler.execute(context(block), replaceAction(null, Map.of(
                "source", "hotbar",
                "deny-replace", List.of("dirt")
        ))));
        assertTrue(actionHandler.execute(context(block), replaceAction(null, Map.of(
                "source", "hotbar",
                "allow-replace", List.of("dirt")
        ))));
        assertEquals(Material.OAK_PLANKS, blockType);
    }

    @Test
    void copyCompatibleDataPreservesStairsAndLogOrientation() {
        Stairs fromStairs = mock(Stairs.class);
        Stairs toStairs = mock(Stairs.class);
        when(fromStairs.getFacing()).thenReturn(BlockFace.EAST);
        when(fromStairs.getHalf()).thenReturn(Bisected.Half.TOP);
        when(fromStairs.getShape()).thenReturn(Stairs.Shape.INNER_LEFT);
        when(fromStairs.isWaterlogged()).thenReturn(true);
        when(toStairs.getFaces()).thenReturn(Set.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST));

        BlockReplaceAction.copyCompatibleData(fromStairs, toStairs);
        verify(toStairs).setFacing(BlockFace.EAST);
        verify(toStairs).setHalf(Bisected.Half.TOP);
        verify(toStairs).setShape(Stairs.Shape.INNER_LEFT);
        verify(toStairs).setWaterlogged(true);

        Orientable fromLog = mock(Orientable.class);
        Orientable toLog = mock(Orientable.class);
        when(fromLog.getAxis()).thenReturn(Axis.X);
        when(toLog.getAxes()).thenReturn(Set.of(Axis.X, Axis.Y, Axis.Z));
        BlockReplaceAction.copyCompatibleData(fromLog, toLog);
        verify(toLog).setAxis(Axis.X);
    }
}
