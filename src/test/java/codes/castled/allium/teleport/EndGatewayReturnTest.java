package codes.castled.allium.teleport;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EndGatewayReturnTest {
    @Test
    void returnsAtTheStoredExitBelowTheVanillaRoofAndPreservesFacing() {
        World world = mock(World.class);
        when(world.getEnvironment()).thenReturn(World.Environment.THE_END);
        when(world.getMaxHeight()).thenReturn(256);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        Block air = mock(Block.class);
        Block stone = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(stone.getType()).thenReturn(Material.END_STONE);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation ->
                invocation.<Integer>getArgument(1) == 63 ? stone : air);
        DestinationSafety safety = mock(DestinationSafety.class);
        when(safety.safe(any(), any())).thenReturn(true);
        var search = new EndGatewayReturn(safety);
        var roof = new Location(world, 2, 115, 2, 30, -15);
        var exit = new Location(world, 2, 64, 2);
        assertEquals(new Location(world, 2.5, 64, 2.5, 30, -15),
                search.resolve(roof, exit, 512, 5).orElseThrow());
        assertTrue(search.resolve(new Location(world, 900, 64, 0), null, 512, 5).isEmpty());
        verify(world, never()).getChunkAt(anyInt(), anyInt());
    }
}
