package codes.castled.allium.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItemSigningKeyAuditTest {
    @Test void unknownLoadedKeyIsLoudBoundedAndReadOnly() {
        var signer = ItemHmacSigner.randomEphemeral();
        List<String> logs = new ArrayList<>();
        var audit = new ItemSigningKeyAudit(signer, logs::add);
        var stack = mock(ItemStack.class);
        var meta = mock(ItemMeta.class);
        var pdc = mock(PersistentDataContainer.class);
        when(stack.getType()).thenReturn(Material.STONE);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING)).thenReturn("v2:unknown:invalid");
        audit.auditStack(stack);
        audit.auditStack(stack);
        assertEquals(1, logs.size());
        assertTrue(logs.get(0).contains("CRITICAL HMAC KEY MISMATCH"));
        assertTrue(logs.get(0).contains("unknown"));
        verify(stack, never()).setItemMeta(any());
        verify(pdc, never()).set(any(), any(), any());
        when(pdc.get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING)).thenReturn("v2:k1:invalid");
        audit.auditStack(stack);
        assertEquals(1, logs.size());
    }
}
