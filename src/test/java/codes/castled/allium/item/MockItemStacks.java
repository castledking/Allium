package codes.castled.allium.item;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Map-backed ItemStack/ItemMeta/PDC mocks for unit tests that run without a server. */
@SuppressWarnings("deprecation")
public final class MockItemStacks {

    private MockItemStacks() {
    }

    public static ItemStack create(Material material) {
        return create(material, 1, new HashMap<>(), new AtomicReference<>(null), new AtomicReference<>(null));
    }

    public static PersistentDataContainer container(Map<NamespacedKey, Object> backing) {
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        doAnswer(inv -> {
            backing.put(inv.getArgument(0), inv.getArgument(2));
            return null;
        }).when(pdc).set(any(NamespacedKey.class), any(), any());
        when(pdc.get(any(NamespacedKey.class), any())).thenAnswer(inv -> backing.get(inv.getArgument(0)));
        when(pdc.has(any(NamespacedKey.class), any())).thenAnswer(inv -> backing.containsKey(inv.getArgument(0)));
        when(pdc.getKeys()).thenAnswer(inv -> backing.keySet());
        doAnswer(inv -> {
            backing.remove(inv.getArgument(0));
            return null;
        }).when(pdc).remove(any(NamespacedKey.class));
        return pdc;
    }

    public static ItemStack create(
            Material material,
            int initialAmount,
            Map<NamespacedKey, Object> pdcMap,
            AtomicReference<String> displayNameRef,
            AtomicReference<Integer> cmdRef
    ) {
        AtomicReference<Integer> amountRef = new AtomicReference<>(initialAmount);
        PersistentDataContainer pdc = container(pdcMap);

        ItemMeta meta = mock(ItemMeta.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        doAnswer(inv -> {
            displayNameRef.set(inv.getArgument(0));
            return null;
        }).when(meta).setDisplayName(any());
        when(meta.getDisplayName()).thenAnswer(inv -> displayNameRef.get());
        doAnswer(inv -> {
            cmdRef.set(inv.getArgument(0));
            return null;
        }).when(meta).setCustomModelData(any());
        when(meta.getCustomModelData()).thenAnswer(inv -> cmdRef.get() != null ? cmdRef.get() : 0);
        when(meta.hasCustomModelData()).thenAnswer(inv -> cmdRef.get() != null);

        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenAnswer(inv -> amountRef.get());
        doAnswer(inv -> {
            amountRef.set(inv.getArgument(0));
            return null;
        }).when(stack).setAmount(anyInt());
        when(stack.getItemMeta()).thenReturn(meta);
        when(stack.setItemMeta(any())).thenReturn(true);
        when(stack.clone()).thenAnswer(inv -> create(
                material,
                amountRef.get(),
                new HashMap<>(pdcMap),
                new AtomicReference<>(displayNameRef.get()),
                new AtomicReference<>(cmdRef.get())
        ));
        return stack;
    }
}
