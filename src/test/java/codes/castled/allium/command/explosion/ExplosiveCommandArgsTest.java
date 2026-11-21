package codes.castled.allium.command.explosion;

import codes.castled.allium.explosion.ExplosionSettings;
import codes.castled.allium.message.Messages;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplosiveCommandArgsTest {

    private static final Set<String> TYPES = new LinkedHashSet<>(List.of("fireball", "small", "skull", "arrow"));

    private static FireballCommand.Request fireball(String label, String... args) throws FireballCommand.Refused {
        return FireballCommand.parse(label, args, TYPES, 8.0D);
    }

    @Test
    void fireballReadsTypeSpeedAndRideInEssentialsOrder() throws Exception {
        assertEquals(new FireballCommand.Request("fireball", 2.0D, false), fireball("fireball"));
        assertEquals(new FireballCommand.Request("small", 2.0D, false), fireball("fireball", "SMALL"));
        assertEquals(new FireballCommand.Request("arrow", 5.0D, false), fireball("fireball", "arrow", "5"));
        assertEquals(new FireballCommand.Request("arrow", 5.0D, true), fireball("fireball", "arrow", "5", "ride"));
        assertEquals(new FireballCommand.Request("arrow", 2.0D, true), fireball("fireball", "arrow", "ride"));
        // A lone number is a speed for the default projectile; speeds clamp to 0..max-speed.
        assertEquals(new FireballCommand.Request("fireball", 3.5D, false), fireball("fireball", "3.5"));
        assertEquals(new FireballCommand.Request("fireball", 8.0D, false), fireball("fireball", "99"));
        assertEquals(new FireballCommand.Request("fireball", 0.0D, false), fireball("fireball", "-4"));
    }

    @Test
    void fireskullThrowsASkullUnlessATypeIsGiven() throws Exception {
        assertEquals("skull", fireball("fireskull").type());
        assertEquals("skull", fireball("fireskull", "1").type());
        assertEquals("small", fireball("fireskull", "small").type());
        assertEquals("fireball", fireball("fireentity").type());
    }

    @Test
    void fireballRefusesUnknownTypesBadSpeedsAndExtraWords() {
        FireballCommand.Refused unknown = assertThrows(FireballCommand.Refused.class, () -> fireball("fireball", "cow"));
        assertEquals(Messages.FIREBALL_UNKNOWN_TYPE, unknown.message);
        assertEquals("fireball, small, skull, arrow", unknown.replacements[3]);
        assertEquals(Messages.FIREBALL_INVALID_SPEED,
                assertThrows(FireballCommand.Refused.class, () -> fireball("fireball", "skull", "fast")).message);
        assertNull(assertThrows(FireballCommand.Refused.class, () -> fireball("fireball", "skull", "2", "walk")).message);
        assertNull(assertThrows(FireballCommand.Refused.class, () -> fireball("fireball", "skull", "2", "ride", "now")).message);
    }

    @Test
    void theDefaultSpeedNeverExceedsTheMaximum() throws Exception {
        assertEquals(1.0D, FireballCommand.parse("fireball", new String[0], TYPES, 1.0D).speed());
    }

    @Test
    void explodeReadsALoneNumberAsAPowerAndAnythingElseAsAPlayer() {
        assertEquals(new ExplodeCommand.Request(null, null), ExplodeCommand.parse(new String[0], true));
        assertEquals(new ExplodeCommand.Request(null, "5"), ExplodeCommand.parse(new String[]{"5"}, true));
        assertEquals(new ExplodeCommand.Request("Steve", null), ExplodeCommand.parse(new String[]{"Steve"}, true));
        assertEquals(new ExplodeCommand.Request("Steve", "7"), ExplodeCommand.parse(new String[]{"Steve", "7"}, true));
        // The console must name a player.
        assertNull(ExplodeCommand.parse(new String[0], false));
        assertNull(ExplodeCommand.parse(new String[]{"5"}, false));
        assertEquals(new ExplodeCommand.Request("Steve", null), ExplodeCommand.parse(new String[]{"Steve"}, false));
        assertNull(ExplodeCommand.parse(new String[]{"a", "b", "c"}, true));
    }

    @Test
    void explodePowersStayInsideTheConfiguredRange() throws Exception {
        List<String> warnings = new ArrayList<>();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("explode:\n  min-power: 2\n  max-power: 6\n  default-power: 3\n");
        ExplosionSettings settings = ExplosionSettings.read(yaml, warnings::add);
        assertTrue(warnings.isEmpty());
        assertEquals(3, ExplodeCommand.power(null, settings));
        assertEquals(6, ExplodeCommand.power("6", settings));
        assertNull(ExplodeCommand.power("7", settings));
        assertNull(ExplodeCommand.power("1", settings));
        assertNull(ExplodeCommand.power("big", settings));
        assertFalse(FireballCommand.typeNames().isEmpty());
    }
}
