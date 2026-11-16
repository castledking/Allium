package codes.castled.allium.essentials;

import codes.castled.allium.command.spec.CommandCompletionContext;
import codes.castled.allium.command.spec.CommandFamilyPolicy;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ParityUtilitiesDomainTest {

    @Test
    void speedParserAndBukkitConversionHandleDefaultsBoundsAndReset() {
        var selfReset = SpeedSpecParser.parse(new String[]{"reset"}).orElseThrow();
        assertNull(selfReset.explicitType());
        assertEquals(1.0f, selfReset.displaySpeed());
        assertTrue(selfReset.reset());
        assertEquals(-1, selfReset.targetArgIdx());

        var explicitWalkOther = SpeedSpecParser.parse(new String[]{"fly", "2.5", "Bob"}).orElseThrow();
        assertEquals(SpeedSpecParser.SpeedType.FLY, explicitWalkOther.explicitType());
        assertEquals(2.5f, explicitWalkOther.displaySpeed());
        assertEquals(2, explicitWalkOther.targetArgIdx());

        var implicitOther = SpeedSpecParser.parse(new String[]{"5", "Bob"}).orElseThrow();
        assertNull(implicitOther.explicitType());
        assertEquals(5.0f, implicitOther.displaySpeed());
        assertEquals(1, implicitOther.targetArgIdx());

        assertEquals(0.2f, SpeedSpecParser.toBukkitSpeed(SpeedSpecParser.SpeedType.WALK, 1.0f), 1e-6f);
        assertEquals(0.1f, SpeedSpecParser.toBukkitSpeed(SpeedSpecParser.SpeedType.FLY, 1.0f), 1e-6f);
        assertEquals(1.0f, SpeedSpecParser.toBukkitSpeed(SpeedSpecParser.SpeedType.WALK, 10.0f), 1e-6f);
        assertEquals(0.0f, SpeedSpecParser.toBukkitSpeed(SpeedSpecParser.SpeedType.FLY, 0.0f), 1e-6f);
        assertEquals("2", SpeedSpecParser.formatSpeed(2.0f));
        assertEquals("2.5", SpeedSpecParser.formatSpeed(2.5f));

        assertTrue(SpeedSpecParser.parse(new String[]{"-0.1"}).isEmpty());
        assertTrue(SpeedSpecParser.parse(new String[]{"10.1"}).isEmpty());
        assertTrue(SpeedSpecParser.parse(new String[]{"NaN"}).isEmpty());
        assertTrue(SpeedSpecParser.parse(new String[]{"Infinity"}).isEmpty());
        assertTrue(SpeedSpecParser.parse(new String[]{"walk"}).isEmpty());
    }

    @Test
    void worldTimeParserCoversNamesTicksClocksAndPause() {
        assertEquals(1000L, WorldTimeParser.parseTimeTicks("day").orElseThrow());
        assertEquals(6000L, WorldTimeParser.parseTimeTicks("noon").orElseThrow());
        assertEquals(13000L, WorldTimeParser.parseTimeTicks("night").orElseThrow());
        assertEquals(18000L, WorldTimeParser.parseTimeTicks("midnight").orElseThrow());
        assertEquals(23000L, WorldTimeParser.parseTimeTicks("sunrise").orElseThrow());
        assertEquals(13000L, WorldTimeParser.parseTimeTicks("13000t").orElseThrow());
        assertEquals(0L, WorldTimeParser.parseTimeTicks("6:00").orElseThrow());
        assertEquals(6000L, WorldTimeParser.parseTimeTicks("12:00").orElseThrow());
        assertEquals(12000L, WorldTimeParser.parseTimeTicks("18:00").orElseThrow());
        assertEquals(18000L, WorldTimeParser.parseTimeTicks("00:00").orElseThrow());

        assertTrue(WorldTimeParser.parseTimeTicks("24:00").isEmpty());
        assertTrue(WorldTimeParser.parseTimeTicks("-100").isEmpty());

        var setAll = WorldTimeParser.parseTimeCommand(new String[]{"set", "midnight", "*"}).orElseThrow();
        assertEquals(WorldTimeParser.Action.SET, setAll.action());
        assertEquals(18000L, setAll.ticks());
        assertEquals("*", setAll.worldSelector());

        var add = WorldTimeParser.parseTimeCommand(new String[]{"add", "2500t", "world_nether"}).orElseThrow();
        assertEquals(WorldTimeParser.Action.ADD, add.action());
        assertEquals(2500L, add.ticks());
        assertEquals("world_nether", add.worldSelector());

        var pause = WorldTimeParser.parseTimeCommand(new String[]{"pause", "on", "world"}).orElseThrow();
        assertEquals(WorldTimeParser.Action.PAUSE, pause.action());
        assertEquals(Boolean.TRUE, pause.pauseState());
        assertEquals("world", pause.worldSelector());

        assertEquals("6000 ticks (12:00)", WorldTimeParser.formatTime(30000L));
    }

    @Test
    void weatherStateParserValidatesConditionsAndDurationBounds() {
        var sun = WeatherState.parse(new String[]{"sun", "world"}).orElseThrow();
        assertEquals(WeatherState.Condition.CLEAR, sun.condition());
        assertNull(sun.durationSeconds());
        assertEquals("world", sun.worldSelector());

        var storm = WeatherState.parse(new String[]{"storm", "600", "*"}).orElseThrow();
        assertEquals(WeatherState.Condition.RAIN, storm.condition());
        assertEquals(600, storm.durationSeconds());
        assertEquals("*", storm.worldSelector());

        assertTrue(WeatherState.parse(new String[]{"thunder", "0"}).isEmpty());
        assertTrue(WeatherState.parse(new String[]{"thunder", "-5"}).isEmpty());
        assertTrue(WeatherState.parse(new String[]{"clear", "1000001"}).isEmpty());
        assertTrue(WeatherState.parse(new String[]{"acidrain"}).isEmpty());
    }

    @Test
    void enchantParserSupportsModernAndLegacyOrderAndClearFilter() {
        var enchants = Set.of("sharpness", "efficiency", "unbreaking");
        var players = Set.of("Alice", "Bob");

        var modern = EnchantArgParser.parse(
                new String[]{"sharpness", "5", "Bob"},
                enchants::contains,
                players::contains
        ).orElseThrow();
        assertEquals("sharpness", modern.enchantmentToken());
        assertEquals(5, modern.level());
        assertEquals("Bob", modern.targetName());

        var legacy = EnchantArgParser.parse(
                new String[]{"Alice", "efficiency", "0"},
                enchants::contains,
                players::contains
        ).orElseThrow();
        assertEquals("efficiency", legacy.enchantmentToken());
        assertEquals(0, legacy.level());
        assertEquals("Alice", legacy.targetName());

        assertTrue(EnchantArgParser.parse(
                new String[]{"sharpness", "256"},
                enchants::contains,
                players::contains
        ).isEmpty());

        assertTrue(InventoryClearFilter.matchesMaterialKey("minecraft:diamond_sword", "DIAMOND-SWORD"));
        assertTrue(InventoryClearFilter.isAllToken("*"));
        assertEquals(64, InventoryClearFilter.parseMaxCount("64"));
        assertNull(InventoryClearFilter.parseMaxCount("0"));
    }

    @Test
    void commandSpecsIncludeAliasesPermissionsAndCompletionBoundaries() {
        CommandFamilyPolicy policy = new CommandFamilyPolicy();
        policy.registerSpecs(CoreCommandSpecs.UTILITY_SPECS);
        policy.registerSpecs(CoreCommandSpecs.PARITY_UTILITY_SPECS);
        policy.initializeStartup(List.of("ci", "trash", "uptime"));

        assertTrue(policy.isDisabled("clearinventory"));
        assertTrue(policy.isDisabled("allium:clear"));
        assertTrue(policy.isDisabled("disposal"));
        assertTrue(policy.isDisabled("gc"));
        assertFalse(policy.isDisabled("speed"));
        assertEquals("repair", CoreCommandSpecs.UTILITY_SPECS.get(0).canonicalName());

        CommandCompletionContext selfOnly = context(Set.of("allium.ping", "allium.enchant"), List.of("Alice", "Bob"));
        assertEquals(List.of(), CoreCommandSpecs.PING.complete(selfOnly, new String[]{""}));

        CommandCompletionContext withOthers = context(Set.of("allium.ping", "allium.ping.others"), List.of("Alice", "Bob"));
        assertEquals(List.of("Bob"), CoreCommandSpecs.PING.complete(withOthers, new String[]{"b"}));
    }

    private static CommandCompletionContext context(Set<String> perms, List<String> players) {
        return new CommandCompletionContext() {
            @Override
            public boolean hasPermission(String permissionNode) {
                return permissionNode == null || permissionNode.isEmpty() || perms.contains(permissionNode);
            }

            @Override
            public List<String> visibleOnlinePlayers() {
                return players;
            }
        };
    }
}
