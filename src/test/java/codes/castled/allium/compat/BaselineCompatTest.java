package codes.castled.allium.compat;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BaselineCompatTest {

    @Test
    void materialCompatDoesNotFallbackToAirForUnknownNames() {
        assertNull(MaterialCompat.getMaterial("not_a_real_material_id"));
        assertNull(MaterialCompat.getMaterial(""));
        assertNull(MaterialCompat.getMaterial(null));
        assertEquals(Material.STONE, MaterialCompat.getMaterial("stone"));
        assertEquals(Material.DIAMOND_SWORD, MaterialCompat.getMaterial("DIAMOND_SWORD"));
    }

    @Test
    void materialCompatIdentifiesAirVariants() {
        assertTrue(MaterialCompat.isAir(null));
        assertTrue(MaterialCompat.isAir(Material.AIR));
        assertTrue(MaterialCompat.isAir(Material.CAVE_AIR));
        assertTrue(MaterialCompat.isAir(Material.VOID_AIR));
        assertFalse(MaterialCompat.isAir(Material.STONE));
    }

    @Test
    void worldHeightCompatDelegatesToWorldBounds() {
        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);

        WorldHeightCompat compat = WorldHeightCompatProvider.current();
        assertEquals(-64, compat.minHeight(world));
        assertEquals(320, compat.maxHeight(world));
    }

    @Test
    void entityCompatClearsArrowsFreezeAndPotionEffects() {
        LivingEntity entity = mock(LivingEntity.class);
        PotionEffect effect = mock(PotionEffect.class);
        when(effect.getType()).thenReturn(PotionEffectType.SPEED);
        when(entity.getActivePotionEffects()).thenReturn(Collections.singletonList(effect));

        EntityCompat compat = EntityCompatProvider.current();
        compat.clearArrows(entity);
        compat.clearFreeze(entity);
        compat.clearPotionEffects(entity);

        verify(entity).setArrowsInBody(0);
        verify(entity).setFreezeTicks(0);
        verify(entity).removePotionEffect(PotionEffectType.SPEED);
    }
}
