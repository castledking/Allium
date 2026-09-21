package codes.castled.allium.spawnercraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlushieCraftTest {

    @Test
    void datapackItemNamesResolveToTheirOwnMob() {
        // These borrow other mobs' note block sounds, so the item name must win.
        assertEquals("mooshroom", MobHeadRegistry.mobForItemName("Red Mooshroom Head"));
        assertEquals("cave_spider", MobHeadRegistry.mobForItemName("Cave Spider Head"));
        assertEquals("guardian", MobHeadRegistry.mobForItemName("Guardian Head"));
        assertEquals("copper_golem", MobHeadRegistry.mobForItemName("Oxidized Copper Golem Head"));
        assertNull(MobHeadRegistry.mobForItemName("Player Head"));
    }

    @Test
    void plushieIdsFollowNexoNaming() {
        assertEquals("axolotl_plushie", PlushieCraftListener.plushieIdForMob("axolotl"));
        assertEquals("enderdragon_plushie", PlushieCraftListener.plushieIdForMob("ender_dragon"));
        assertEquals("copper_golem_unoxidized_plushie", PlushieCraftListener.plushieIdForMob("copper_golem"));
        assertEquals("zombie_nautilus_temperate_plushie", PlushieCraftListener.plushieIdForMob("zombie_nautilus"));
    }
}
