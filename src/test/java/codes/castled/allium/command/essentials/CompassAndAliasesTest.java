package codes.castled.allium.command.essentials;

import codes.castled.allium.command.spec.CoreCommandSpecs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompassAndAliasesTest {

    @Test
    void bearingIsDegreesClockwiseFromNorth() {
        assertEquals(0, CompassCommand.bearing(180));
        assertEquals(90, CompassCommand.bearing(-90));
        assertEquals(180, CompassCommand.bearing(0));
        assertEquals(270, CompassCommand.bearing(90));
        assertEquals(90, CompassCommand.bearing(270), "yaw is not always wrapped");
        assertEquals(0, CompassCommand.direction(0));
        assertEquals(0, CompassCommand.direction(22));
        assertEquals(1, CompassCommand.direction(23));
        assertEquals(2, CompassCommand.direction(90));
        assertEquals(7, CompassCommand.direction(315));
        assertEquals(0, CompassCommand.direction(340));
        assertEquals(350, Math.round(CoordsCommand.yaw(-10)));
    }

    @Test
    void requestedAliasesBelongToTheirCommands() {
        assertEquals("creative", CoreCommandSpecs.gamemodeAlias("creativemode"));
        assertEquals("adventure", CoreCommandSpecs.gamemodeAlias("allium:adventuremode"));
        assertEquals("walk", CoreCommandSpecs.speedAlias("wspeed"));
        assertEquals("walk", CoreCommandSpecs.speedAlias("walkspeed"));
        assertNull(CoreCommandSpecs.speedAlias("speed"));
        assertTrue(CoreCommandSpecs.WORKBENCH.aliases().containsAll(java.util.List.of("craft", "wb", "wbench")));
        assertTrue(CoreCommandSpecs.LIST.aliases().contains("who"));
        assertTrue(CoreCommandSpecs.COORDS.aliases().containsAll(java.util.List.of("getpos", "whereami")));
        assertTrue(CoreCommandSpecs.GC.aliases().contains("tps"));
        assertTrue(CoreCommandSpecs.AFK.aliases().contains("away"));
        assertTrue(CoreCommandSpecs.ECONOMY_SPECS.stream().anyMatch(s -> s.canonicalName().equals("baltop") && s.aliases().contains("balancetop")));
        assertTrue(CoreCommandSpecs.CHAT_SPECS.stream().anyMatch(s -> s.canonicalName().equals("action") && s.aliases().contains("me")
                && "allium.me".equals(s.basePermission())));
        assertTrue(CoreCommandSpecs.CHAT_SPECS.stream().anyMatch(s -> s.canonicalName().equals("channel")
                && s.aliases().containsAll(java.util.List.of("shout", "ac", "amsg"))));
    }
}
