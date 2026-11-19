package codes.castled.allium.teleport;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerticalTeleportsTest {

    /** A column from y=0: 'S' solid, '.' air, 'W' water, 'M' magma. */
    private static VerticalTeleports.Column column(String layers) {
        return new VerticalTeleports.Column() {
            char at(int y) {
                return y >= 0 && y < layers.length() ? layers.charAt(y) : 'X';
            }

            @Override
            public boolean ground(int y) {
                return at(y) == 'S';
            }

            @Override
            public boolean space(int y) {
                return at(y) == '.';
            }
        };
    }

    @Test
    void topFindsTheHighestGroundWithTwoFreeBlocksAndSkipsWaterAndOneHighGaps() {
        //                         0123456789
        VerticalTeleports.Column c = column("SS..SS.S..");
        assertEquals(OptionalInt.of(8), VerticalTeleports.highest(c, 9, 0));
        assertEquals(OptionalInt.of(2), VerticalTeleports.highest(c, 5, 0), "y=5 has only one free block above");
        assertEquals(OptionalInt.empty(), VerticalTeleports.highest(column("SWW"), 2, 0));
        assertEquals(OptionalInt.empty(), VerticalTeleports.highest(column("M.."), 2, 0));
    }

    @Test
    void bottomFindsTheLowestGroundBelowThePlayer() {
        VerticalTeleports.Column c = column("SS..SS.S..");
        assertEquals(OptionalInt.of(2), VerticalTeleports.lowest(c, 0, 9));
        assertEquals(OptionalInt.empty(), VerticalTeleports.lowest(c, 0, 2), "the feet must stay below the player");
        assertEquals(OptionalInt.empty(), VerticalTeleports.lowest(column("SSSS"), 0, 4));
    }

    @Test
    void underARoofTopStaysBelowItWithoutTheRoofNode() {
        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(256);
        when(world.getLogicalHeight()).thenReturn(128);
        when(world.hasCeiling()).thenReturn(true);
        when(world.getHighestBlockYAt(anyInt(), anyInt())).thenReturn(127);
        Map<Integer, Block> blocks = new HashMap<>();
        for (int y = 0; y < 256; y++) {
            Block block = mock(Block.class);
            // Netherrack floor 0-40, open cave 41-126, bedrock roof 127, open sky above it.
            Material type = y <= 40 ? Material.NETHERRACK : y == 127 ? Material.BEDROCK : Material.AIR;
            when(block.getType()).thenReturn(type);
            when(block.isPassable()).thenReturn(type == Material.AIR);
            when(block.isLiquid()).thenReturn(false);
            blocks.put(y, block);
        }
        when(world.getBlockAt(eq(3), anyInt(), eq(-7))).thenAnswer(inv -> blocks.get(inv.<Integer>getArgument(1)));
        Player player = mock(Player.class);
        when(player.getLocation()).thenReturn(new Location(world, 3.2, 60, -6.6, 90, 10));

        Location under = VerticalTeleports.top(player).orElseThrow();
        assertEquals(41, under.getY(), "the highest spot under the roof is the cave floor");
        assertEquals(3.5, under.getX());
        assertEquals(-6.5, under.getZ());
        assertEquals(90, under.getYaw());

        when(player.hasPermission(VerticalTeleports.PERMISSION_ROOF)).thenReturn(true);
        assertEquals(128, VerticalTeleports.top(player).orElseThrow().getY(), "with allium.top.roof, on top of the bedrock");
        assertEquals(41, VerticalTeleports.bottom(player).orElseThrow().getY());
        assertTrue(VerticalTeleports.bottom(player).isPresent());
    }
}
