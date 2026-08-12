package codes.castled.allium.items.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Enderman;
import org.junit.jupiter.api.Test;

class EndermanCarriedBlockTest {
    @Test
    void clearsAndReturnsTheCarriedBlockAsAnItem() {
        AtomicReference<BlockData> carried = new AtomicReference<>(block(Material.GRASS_BLOCK));
        Enderman enderman = enderman(carried);

        Material stripped = MobDisarmerItem.stripCarriedBlock(enderman);

        assertEquals(Material.GRASS_BLOCK, stripped);
        assertNull(carried.get());
    }

    @Test
    void ignoresAnEndermanWithEmptyHands() {
        AtomicReference<BlockData> carried = new AtomicReference<>();
        assertNull(MobDisarmerItem.stripCarriedBlock(enderman(carried)));
    }

    private Enderman enderman(final AtomicReference<BlockData> carried) {
        return proxy(Enderman.class, (method, args) -> switch (method) {
            case "getCarriedBlock" -> carried.get();
            case "setCarriedBlock" -> {
                carried.set((BlockData) args[0]);
                yield null;
            }
            default -> defaultValue(Enderman.class.getMethod(method).getReturnType());
        });
    }

    private BlockData block(final Material material) {
        return proxy(BlockData.class, (method, args) -> method.equals("getMaterial")
                ? material : defaultValue(BlockData.class.getMethod(method).getReturnType()));
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(final Class<T> type, final Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> invocation.call(method.getName(), args));
    }

    private Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == char.class) return '\0';
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(String method, Object[] args) throws Exception;
    }
}
