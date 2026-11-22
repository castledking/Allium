package codes.castled.allium.item;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItemStateValueTest {

    /** A container that checks tag types the way CraftBukkit does: a wrong type throws on get. */
    private static PersistentDataContainer typed(Map<NamespacedKey, Object> tags) {
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(pdc.has(any(NamespacedKey.class), any(PersistentDataType.class))).thenAnswer(inv -> {
            Object value = tags.get(inv.getArgument(0));
            PersistentDataType<?, ?> type = inv.getArgument(1);
            return value != null && type.getPrimitiveType().isInstance(value);
        });
        when(pdc.get(any(NamespacedKey.class), any(PersistentDataType.class))).thenAnswer(inv -> {
            Object value = tags.get(inv.getArgument(0));
            PersistentDataType<?, ?> type = inv.getArgument(1);
            if (value != null && !type.getPrimitiveType().isInstance(value)) {
                throw new IllegalArgumentException("The found tag instance (NBTTagByte) cannot store String");
            }
            return value;
        });
        return pdc;
    }

    @Test
    void textStateIsReadAsIs() {
        NamespacedKey key = new NamespacedKey("allium", "state_level");
        assertEquals("12", ItemService.stateValue(typed(Map.of(key, "12")), key));
    }

    @Test
    void rewrittenBooleansAndNumbersAreReadBackAsTextInsteadOfThrowing() {
        NamespacedKey bound = new NamespacedKey("allium", "state_bound");
        NamespacedKey locked = new NamespacedKey("allium", "state_locked");
        NamespacedKey level = new NamespacedKey("allium", "state_level");
        NamespacedKey big = new NamespacedKey("allium", "state_big");
        PersistentDataContainer pdc = typed(Map.of(bound, (byte) 0, locked, (byte) 1, level, 40, big, 5_000_000_000L));
        assertEquals("false", ItemService.stateValue(pdc, bound));
        assertEquals("true", ItemService.stateValue(pdc, locked));
        assertEquals("40", ItemService.stateValue(pdc, level));
        assertEquals("5000000000", ItemService.stateValue(pdc, big));
    }

    @Test
    void otherTagTypesAreSkipped() {
        NamespacedKey xp = new NamespacedKey("allium", "state_xp");
        assertNull(ItemService.stateValue(typed(Map.of(xp, 2.5d)), xp));
    }
}
