package codes.castled.allium.managers.world;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class PregenCursorTest {
    @Test void coversTheWholeSquareExactlyOnceIncludingNegativeCoordinates() {
        var cursor = new PregenCursor(-10,20,2);
        var found = new HashSet<PregenCursor.ChunkPosition>();
        while (cursor.hasNext()) assertTrue(found.add(cursor.next()));
        assertEquals(25,cursor.total());
        assertEquals(25,found.size());
        for (int x = -12; x <= -8; x++) for (int z = 18; z <= 22; z++)
            assertTrue(found.contains(new PregenCursor.ChunkPosition(x,z)));
        assertThrows(NoSuchElementException.class,cursor::next);
    }
    @Test void validatesRadiusAndWorldBoundaryBeforeStarting() {
        assertThrows(IllegalArgumentException.class,() -> new PregenCursor(0,0,0));
        assertThrows(IllegalArgumentException.class,() -> new PregenCursor(0,0,10001));
        assertThrows(IllegalArgumentException.class,() -> new PregenCursor(Integer.MIN_VALUE,0,1));
        assertThrows(IllegalArgumentException.class,() -> new PregenCursor(1874999,0,1));
        assertEquals(400040001L,new PregenCursor(0,0,10000).total());
    }
}
