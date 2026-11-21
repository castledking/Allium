package codes.castled.allium.rtp;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.protection.ProtectionDecision;
import codes.castled.allium.protection.ProtectionService;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.teleport.DestinationSafety;
import codes.castled.allium.teleport.TeleportSettings;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RtpTest {

    // -- Geometry --------------------------------------------------------------------------------

    @Test
    void squareSpotsFillTheRingBetweenMinAndMaxOnAllFourSides() {
        Random random = new Random(42);
        int[] sides = new int[4];
        for (int i = 0; i < 20_000; i++) {
            double[] offset = RtpGeometry.offset(RtpSettings.Shape.SQUARE, 500, 2000, random);
            double ring = Math.max(Math.abs(offset[0]), Math.abs(offset[1]));
            assertTrue(ring >= 500 - 1e-9 && ring <= 2000 + 1e-9, "outside the ring: " + offset[0] + ", " + offset[1]);
            sides[(offset[0] >= 0 ? 0 : 1) + (offset[1] >= 0 ? 0 : 2)]++;
        }
        for (int side : sides) assertTrue(side > 4_000, "uneven quadrants " + java.util.Arrays.toString(sides));
    }

    @Test
    void circleSpotsStayBetweenTheRadiiAndSpreadByArea() {
        Random random = new Random(7);
        int outerHalf = 0;
        for (int i = 0; i < 20_000; i++) {
            double[] offset = RtpGeometry.offset(RtpSettings.Shape.CIRCLE, 100, 1000, random);
            double radius = Math.hypot(offset[0], offset[1]);
            assertTrue(radius >= 100 - 1e-9 && radius <= 1000 + 1e-9);
            if (radius > Math.sqrt((1000 * 1000 + 100 * 100) / 2.0)) outerHalf++;
        }
        assertTrue(Math.abs(outerHalf - 10_000) < 600, "half the area should get half the spots: " + outerHalf);
    }

    @Test
    void blockPatternsMatchExactNamesAndOneWildcard() {
        List<String> patterns = List.of("WATER", "*_LEAVES", "STRIPPED_*");
        assertTrue(RtpGeometry.matches(patterns, "water"));
        assertTrue(RtpGeometry.matches(patterns, "OAK_LEAVES"));
        assertTrue(RtpGeometry.matches(patterns, "STRIPPED_OAK_LOG"));
        assertFalse(RtpGeometry.matches(patterns, "LEAVES"));
        assertFalse(RtpGeometry.matches(patterns, "WATER_BUCKET"));
        assertFalse(RtpGeometry.matches(patterns, "GRASS_BLOCK"));
    }

    // -- Settings --------------------------------------------------------------------------------

    @Test
    void theBundledFileReadsAndWorldNamesWorkAsLocations() throws Exception {
        RtpSettings settings = RtpSettings.read(bundled());
        assertEquals("{world}", settings.defaultLocation());
        assertEquals(24, settings.findAttempts());
        assertEquals(3, settings.cacheSize());
        assertTrue(settings.excludedBiomes().contains("deep_ocean"));
        assertTrue(settings.avoidBlocks().contains("*_LEAVES"));
        assertTrue(settings.avoidClaims());
        assertFalse(settings.firstJoin());
        assertTrue(settings.locations().isEmpty());

        RtpSettings.Resolved own = settings.resolve(null, "survival", Set.of("survival", "world_nether")::contains).orElseThrow();
        assertEquals("survival", own.name());
        assertEquals("survival", own.area().world());
        assertNull(own.area().maxRange(), "a world uses default-max-range");
        assertTrue(settings.resolve("nowhere", "survival", Set.of("survival")::contains).isEmpty());
    }

    @Test
    void namedLocationsResolveIgnoringCaseAndBadValuesAreRefused() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("default-location", "wild");
        yaml.set("excluded-biomes", List.of("minecraft:Deep_Ocean"));
        yaml.set("locations.Wild.world", "world");
        yaml.set("locations.Wild.center.x", 100.5);
        yaml.set("locations.Wild.center.z", -40.5);
        yaml.set("locations.Wild.min-range", 1000);
        yaml.set("locations.Wild.max-range", 8000);
        yaml.set("locations.Wild.shape", "circle");
        RtpSettings settings = RtpSettings.read(yaml);
        assertTrue(settings.excludedBiomes().contains("deep_ocean"));
        RtpSettings.Resolved wild = settings.resolve(null, "lobby", name -> false).orElseThrow();
        assertEquals("wild", wild.name());
        assertEquals(new RtpSettings.Area("world", 100.5, -40.5, 1000, 8000.0, RtpSettings.Shape.CIRCLE, null, null, null), wild.area());
        assertEquals("wild", settings.resolve("WILD", "lobby", name -> false).orElseThrow().name());

        yaml.set("locations.Wild.max-range", 500);
        assertThrows(IllegalArgumentException.class, () -> RtpSettings.read(yaml), "max-range must exceed min-range");
        yaml.set("locations.Wild.max-range", 8000);
        yaml.set("locations.Wild.shape", "hexagon");
        assertThrows(IllegalArgumentException.class, () -> RtpSettings.read(yaml));
        yaml.set("locations.Wild.shape", "square");
        yaml.set("find-attempts", 0);
        assertThrows(IllegalArgumentException.class, () -> RtpSettings.read(yaml));
    }

    // -- Spot checks -----------------------------------------------------------------------------

    private record Column(World world, Block floor, DestinationSafety safety, ProtectionService protection, RtpSearch search) {
    }

    private Column column(World.Environment environment, int surface, Material floorType, Biome biome) {
        World world = mock(World.class);
        when(world.getEnvironment()).thenReturn(environment);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getHighestBlockYAt(anyInt(), anyInt(), eq(HeightMap.MOTION_BLOCKING_NO_LEAVES))).thenReturn(surface);
        when(world.getBiome(anyInt(), anyInt(), anyInt())).thenReturn(biome);
        Block floor = mock(Block.class);
        when(floor.getType()).thenReturn(floorType);
        when(floor.isPassable()).thenReturn(false);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(floor);
        DestinationSafety safety = mock(DestinationSafety.class);
        when(safety.safe(any(), any())).thenReturn(true);
        ProtectionService protection = mock(ProtectionService.class);
        when(protection.checkBlockBreak(any(), any(), anyString(), isNull())).thenReturn(ProtectionDecision.allow());
        RtpSearch search = new RtpSearch(mock(TaskScheduler.class), safety, () -> protection);
        return new Column(world, floor, safety, protection, search);
    }

    private static RtpSettings settings() throws Exception {
        return RtpSettings.read(bundled());
    }

    @Test
    void aGoodColumnLandsOnTopFacingRandomlyAndEveryFilterCanRefuseIt() throws Exception {
        RtpSettings settings = settings();
        TeleportSettings rules = mock(TeleportSettings.class);
        RtpSettings.Area area = RtpSettings.Area.of("world");
        Column good = column(World.Environment.NORMAL, 70, Material.GRASS_BLOCK, Biome.PLAINS);
        Player player = mock(Player.class);
        Location spot = good.search().evaluate(good.world(), 10, -20, area, settings, player, rules, null);
        assertNotNull(spot);
        assertEquals(71, spot.getBlockY());
        assertEquals(10.5, spot.getX());
        assertEquals(-19.5, spot.getZ());
        verify(good.protection()).checkBlockBreak(player, spot, "rtp", null);

        Column water = column(World.Environment.NORMAL, 62, Material.WATER, Biome.PLAINS);
        assertNull(water.search().evaluate(water.world(), 0, 0, area, settings, null, rules, null), "water is an avoided block");
        Column leaves = column(World.Environment.NORMAL, 80, Material.OAK_LEAVES, Biome.FOREST);
        assertNull(leaves.search().evaluate(leaves.world(), 0, 0, area, settings, null, rules, null), "*_LEAVES");
        Column ocean = column(World.Environment.NORMAL, 40, Material.SAND, Biome.DEEP_OCEAN);
        assertNull(ocean.search().evaluate(ocean.world(), 0, 0, area, settings, null, rules, null), "excluded biome");
        Column unsafe = column(World.Environment.NORMAL, 70, Material.GRASS_BLOCK, Biome.PLAINS);
        when(unsafe.safety().safe(any(), any())).thenReturn(false);
        assertNull(unsafe.search().evaluate(unsafe.world(), 0, 0, area, settings, null, rules, null), "DestinationSafety decides");
        Column claimed = column(World.Environment.NORMAL, 70, Material.GRASS_BLOCK, Biome.PLAINS);
        when(claimed.protection().checkBlockBreak(any(), any(), anyString(), isNull()))
                .thenReturn(ProtectionDecision.deny("GriefPrevention", "claimed"));
        assertNull(claimed.search().evaluate(claimed.world(), 0, 0, area, settings, player, rules, null), "someone else's claim");
        assertNotNull(claimed.search().evaluate(claimed.world(), 0, 0, area, settings, null, rules, null),
                "a background search leaves the claim check for later");
        RtpSettings.Area low = new RtpSettings.Area("world", null, null, 0, null, RtpSettings.Shape.SQUARE, null, 60, null);
        Column mountain = column(World.Environment.NORMAL, 70, Material.STONE, Biome.PLAINS);
        when(mountain.safety().safe(any(), any())).thenAnswer(call -> ((Location) call.getArgument(0)).getBlockY() == 50);
        Location underCeiling = mountain.search().evaluate(mountain.world(), 0, 0, low, settings, null, rules, null);
        assertNotNull(underCeiling, "a max-y searches upward below it");
        assertEquals(50, underCeiling.getBlockY());
    }

    @Test
    void aBiomeSearchAcceptsOnlyTheWantedBiomesEvenOnesNormallyExcluded() throws Exception {
        RtpSettings settings = settings();
        TeleportSettings rules = mock(TeleportSettings.class);
        RtpSettings.Area area = RtpSettings.Area.of("world");
        Column plains = column(World.Environment.NORMAL, 70, Material.GRASS_BLOCK, Biome.PLAINS);
        assertNull(plains.search().evaluate(plains.world(), 0, 0, area, settings, null, rules, Set.of("cherry_grove")));
        Column grove = column(World.Environment.NORMAL, 90, Material.GRASS_BLOCK, Biome.CHERRY_GROVE);
        assertNotNull(grove.search().evaluate(grove.world(), 0, 0, area, settings, null, rules, Set.of("cherry_grove", "forest")));
        Column beach = column(World.Environment.NORMAL, 63, Material.SAND, Biome.DEEP_OCEAN);
        assertNotNull(beach.search().evaluate(beach.world(), 0, 0, area, settings, null, rules, Set.of("deep_ocean")),
                "asking for a biome overrides excluded-biomes");
        assertTrue(RtpSearch.acceptedBiome("plains", settings, null));
        assertFalse(RtpSearch.acceptedBiome("ocean", settings, null));
        assertTrue(RtpSearch.acceptedBiome("ocean", settings, Set.of("ocean")));
    }

    @Test
    void costsTiersAndDeathSettingsReadAndValidate() throws Exception {
        RtpSettings defaults = settings();
        assertEquals(0, defaults.cost().minor());
        assertFalse(defaults.onDeath());
        assertEquals("{world}", defaults.onDeathLocation());
        assertEquals(256, defaults.biomeScan());
        assertEquals(-1, defaults.cooldownFor(permission -> true), "no tiers: the teleport cooldown applies");

        YamlConfiguration yaml = bundled();
        yaml.set("cost", "12.50");
        yaml.set("locations.far.world", "world");
        yaml.set("locations.far.cost", 40);
        yaml.set("locations.near.world", "world");
        yaml.set("cooldown-tiers.vip", 120);
        yaml.set("cooldown-tiers.mvp", 30);
        yaml.set("on-death.enabled", true);
        RtpSettings settings = RtpSettings.read(yaml);
        assertEquals(1250, settings.costOf(settings.locations().get("near")).minor(), "the global cost");
        assertEquals(4000, settings.costOf(settings.locations().get("far")).minor(), "a location's own cost");
        assertEquals(120, settings.cooldownFor("allium.rtp.cooldown.vip"::equals));
        assertEquals(30, settings.cooldownFor(permission -> permission.startsWith("allium.rtp.cooldown.")), "the shortest tier held");
        assertEquals(-1, settings.cooldownFor(permission -> false));
        assertTrue(settings.onDeath());

        yaml.set("cost", "1.234");
        assertThrows(IllegalArgumentException.class, () -> RtpSettings.read(yaml), "more than two decimals");
        yaml.set("cost", "-5");
        assertThrows(IllegalArgumentException.class, () -> RtpSettings.read(yaml));
        yaml.set("cost", "0");
        yaml.set("cooldown-tiers.vip", "soon");
        assertThrows(IllegalArgumentException.class, () -> RtpSettings.read(yaml));
    }

    @Test
    void theNetherIsSearchedFromBelowTheRoof() throws Exception {
        Column nether = column(World.Environment.NETHER, 127, Material.NETHERRACK, Biome.NETHER_WASTES);
        when(nether.safety().safe(any(), any())).thenAnswer(call -> ((Location) call.getArgument(0)).getBlockY() == 45);
        Location spot = nether.search().evaluate(nether.world(), 3, 3, RtpSettings.Area.of("world_nether"), settings(), null,
                mock(TeleportSettings.class), null);
        assertNotNull(spot);
        assertEquals(45, spot.getBlockY(), "the first safe floor from y 32 up, never the roof at 128");
    }

    // -- /settpr ---------------------------------------------------------------------------------

    @Test
    void settprWritesRtpYmlKeepsItValidAndCanRemoveALocation(@TempDir Path folder) throws Exception {
        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getScheduler()).thenReturn(mock(TaskScheduler.class));
        when(plugin.logger()).thenReturn(mock(codes.castled.allium.util.AlliumLogger.class));
        MessageManager messages = mock(MessageManager.class);
        when(plugin.getMessageManager()).thenReturn(messages);
        try (var bundled = getClass().getClassLoader().getResourceAsStream("rtp.yml")) {
            Files.copy(bundled, folder.resolve("rtp.yml"));
        }
        org.mockito.Mockito.doNothing().when(plugin).saveResource(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
        RtpService rtp = new RtpService(plugin, () -> null);
        rtp.enable();
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        when(console.getName()).thenReturn("CONSOLE");
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(server.getWorld("world")).thenReturn(world);
        List<String> usages = new ArrayList<>();

        rtp.set(console, new String[]{"wild", "world", "world"}, usages::add);
        rtp.set(console, new String[]{"wild", "minrange", "1000"}, usages::add);
        rtp.set(console, new String[]{"wild", "maxrange", "9000"}, usages::add);
        rtp.set(console, new String[]{"wild", "shape", "circle"}, usages::add);
        assertTrue(usages.isEmpty(), usages.toString());
        assertEquals(new RtpSettings.Area("world", null, null, 1000, 9000.0, RtpSettings.Shape.CIRCLE, null, null, null),
                rtp.settings().locations().get("wild"));
        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(folder.resolve("rtp.yml").toFile());
        assertEquals(9000, onDisk.getInt("locations.wild.max-range"));
        assertTrue(onDisk.contains("excluded-biomes"), "the rest of the file stays");

        rtp.set(console, new String[]{"wild", "maxrange", "500"}, usages::add);
        verify(messages).send(eq(console), eq(Messages.RTP_INVALID_VALUE), eq("{reason}"), anyString());
        assertEquals(9000.0, rtp.settings().locations().get("wild").maxRange(), "a refused change is not saved");
        rtp.set(console, new String[]{"wild", "center"}, usages::add);
        verify(messages).send(console, Messages.PLAYER_ONLY);
        rtp.set(console, new String[]{"wild", "fly", "1"}, usages::add);
        assertEquals(List.of("/settpr"), usages);

        rtp.set(console, new String[]{"wild", "remove"}, usages::add);
        assertFalse(rtp.settings().locations().containsKey("wild"));
        verify(messages).send(console, Messages.RTP_REMOVED, "{location}", "wild");
        assertTrue(Files.exists(folder.resolve("rtp.yml.before-edit")));
        rtp.disable();
    }

    private static YamlConfiguration bundled() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var stream = RtpTest.class.getClassLoader().getResourceAsStream("rtp.yml")) {
            assertNotNull(stream);
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return yaml;
    }
}
