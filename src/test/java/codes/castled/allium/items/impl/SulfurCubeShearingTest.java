package codes.castled.allium.items.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.entity.SulfurCube;
import org.junit.jupiter.api.Test;

class SulfurCubeShearingTest {
    @Test
    void shearsACubeContainingAnEjectableBlock() {
        AtomicBoolean ready = new AtomicBoolean(true);
        AtomicInteger shears = new AtomicInteger();
        SulfurCube cube = cube(ready, shears);

        assertTrue(MobDisarmerItem.shearSulfurCube(cube));
        assertEquals(1, shears.get());
    }

    @Test
    void leavesAnEmptyCubeUntouched() {
        AtomicInteger shears = new AtomicInteger();
        SulfurCube cube = cube(new AtomicBoolean(false), shears);

        assertFalse(MobDisarmerItem.shearSulfurCube(cube));
        assertEquals(0, shears.get());
    }

    private SulfurCube cube(final AtomicBoolean ready, final AtomicInteger shears) {
        return (SulfurCube) Proxy.newProxyInstance(SulfurCube.class.getClassLoader(),
                new Class<?>[]{SulfurCube.class}, (proxy, method, args) -> {
                    if (method.getName().equals("readyToBeSheared")) return ready.get();
                    if (method.getName().equals("shear")) {
                        shears.incrementAndGet();
                        ready.set(false);
                        return null;
                    }
                    Class<?> type = method.getReturnType();
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
                });
    }
}
