package codes.castled.allium;

import codes.castled.allium.legacy.LegacyBukkitRuntime;
import codes.castled.allium.legacy.LegacyCompat;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UnifiedBootstrapTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesMinecraftAndJavaVersionsAcrossEntireMatrix() {
        ServerVersion v1122 = ServerVersion.parse("1.12.2-R0.1-SNAPSHOT");
        assertEquals(1, v1122.major);
        assertEquals(12, v1122.minor);
        assertEquals(2, v1122.patch);
        assertTrue(v1122.isSupported());
        assertFalse(v1122.supportsModernRuntime(8));
        assertFalse(v1122.supportsModernRuntime(17));

        assertFalse(ServerVersion.parse("1.12.1-R0.1-SNAPSHOT").isSupported());
        assertFalse(ServerVersion.parse("1.8.8-R0.1-SNAPSHOT").isSupported());

        ServerVersion v1165 = ServerVersion.parse("1.16.5-R0.1-SNAPSHOT");
        assertTrue(v1165.isSupported());
        assertFalse(v1165.supportsModernRuntime(17));

        ServerVersion v1194 = ServerVersion.parse("1.19.4-R0.1-SNAPSHOT");
        assertTrue(v1194.isSupported());
        assertFalse(v1194.supportsModernRuntime(17));

        ServerVersion v120 = ServerVersion.parse("1.20-R0.1-SNAPSHOT");
        assertTrue(v120.isSupported());
        assertFalse(v120.supportsModernRuntime(8));
        assertFalse(v120.supportsModernRuntime(16));
        assertTrue(v120.supportsModernRuntime(17));

        ServerVersion v263 = ServerVersion.parse("26.3-R0.1-SNAPSHOT");
        assertTrue(v263.isSupported());
        assertTrue(v263.supportsModernRuntime(25));
        assertTrue(v263.compareTo(v120) > 0);

        assertEquals(8, ServerVersion.parseJavaMajor("1.8"));
        assertEquals(8, ServerVersion.parseJavaMajor("1.8.0_502"));
        assertEquals(11, ServerVersion.parseJavaMajor("11"));
        assertEquals(17, ServerVersion.parseJavaMajor("17.0.14"));
        assertEquals(21, ServerVersion.parseJavaMajor("21"));
        assertEquals(25, ServerVersion.parseJavaMajor("25"));
    }

    @Test
    void bootstrapAndLegacyRuntimeClassesAreJava8Bytecode() throws Exception {
        List<String> java8Classes = List.of(
                "codes/castled/allium/AlliumBootstrap.class",
                "codes/castled/allium/AlliumRuntime.class",
                "codes/castled/allium/ServerVersion.class",
                "codes/castled/allium/legacy/LegacyBukkitRuntime.class",
                "codes/castled/allium/legacy/LegacyCompat.class",
                "codes/castled/allium/portable/Homes.class",
                "codes/castled/allium/portable/UtilityMessages.class"
        );
        for (String resource : java8Classes) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(in, "Missing class resource: " + resource);
                DataInputStream dis = new DataInputStream(in);
                assertEquals(0xCAFEBABE, dis.readInt(), "Invalid class magic for " + resource);
                dis.readUnsignedShort(); // minor
                int major = dis.readUnsignedShort();
                assertTrue(major <= 52, resource + " must be Java 8 bytecode (<= 52), was " + major);
            }
        }
    }

    @Test
    void legacyRuntimeHandlesHomesWarpsSpawnTpaMsgGodAndModernFallback() {
        JavaPlugin host = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager pm = mock(PluginManager.class);
        PluginDescriptionFile desc = mock(PluginDescriptionFile.class);
        when(host.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pm);
        when(server.getName()).thenReturn("CraftBukkit");
        when(server.getVersion()).thenReturn("git-Spigot-1.12.2");
        when(host.getDataFolder()).thenReturn(tempDir.toFile());
        when(host.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(host.getDescription()).thenReturn(desc);
        when(desc.getVersion()).thenReturn("1.0.2");

        World world = mock(World.class);
        UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        when(world.getName()).thenReturn("world");
        when(world.getSeaLevel()).thenReturn(63);
        when(server.getWorld(worldId)).thenReturn(world);
        when(server.getWorld("world")).thenReturn(world);
        when(server.getWorlds()).thenReturn(List.of(world));

        Player alice = mockPlayer("Alice", world, 10, 64, 20, true);
        Player bob = mockPlayer("Bob", world, 50, 70, 80, false);
        when(server.getPlayerExact("Alice")).thenReturn(alice);
        when(server.getPlayerExact("Bob")).thenReturn(bob);
        when(server.getPlayer(alice.getUniqueId())).thenReturn(alice);
        when(server.getPlayer(bob.getUniqueId())).thenReturn(bob);
        when(server.getOnlinePlayers()).thenAnswer(inv -> List.of(alice, bob));

        LegacyBukkitRuntime runtime = new LegacyBukkitRuntime(host, ServerVersion.parse("1.12.2-R0.1-SNAPSHOT"), 8);
        runtime.onLoad();
        runtime.onEnable();

        // /allium version
        assertTrue(runtime.onCommand(alice, cmd("allium"), "allium", new String[]{"version"}));
        verify(alice).sendMessage(argThat((String msg) -> msg.contains("[") && msg.contains("Allium")
                && msg.endsWith("Allium 1.0.2 on Spigot (Minecraft 1.12.2)")));

        // /sethome, /homes, /home, /delhome
        assertTrue(runtime.onCommand(alice, cmd("sethome"), "sethome", new String[]{"base"}));
        verify(alice).sendMessage(argThat((String msg) -> msg.contains("Allium") && msg.endsWith("Home 'base' set.")));
        assertTrue(runtime.onCommand(alice, cmd("homes"), "homes", new String[0]));
        verify(alice).sendMessage(argThat((String msg) -> msg.contains("Allium") && msg.endsWith("Homes: base")));
        assertTrue(runtime.onCommand(alice, cmd("home"), "home", new String[]{"base"}));
        verify(alice).teleport(any(Location.class));
        assertTrue(runtime.onCommand(alice, cmd("delhome"), "delhome", new String[]{"base"}));
        verify(alice).sendMessage(argThat((String msg) -> msg.contains("Allium") && msg.endsWith("Home 'base' deleted.")));

        // /setwarp & /warp
        assertTrue(runtime.onCommand(alice, cmd("setwarp"), "setwarp", new String[]{"market"}));
        assertTrue(runtime.onCommand(alice, cmd("warp"), "warp", new String[]{"market"}));
        assertEquals(List.of("market"), runtime.onTabComplete(alice, cmd("warp"), "warp", new String[]{"m"}));

        // /setspawn & /spawn
        assertTrue(runtime.onCommand(alice, cmd("setspawn"), "setspawn", new String[0]));
        assertTrue(runtime.onCommand(bob, cmd("spawn"), "spawn", new String[0]));
        verify(bob, atLeastOnce()).teleport(any(Location.class));

        // /tpa & /tpaccept
        assertTrue(runtime.onCommand(bob, cmd("tpa"), "tpa", new String[]{"Alice"}));
        assertTrue(runtime.onCommand(alice, cmd("tpaccept"), "tpaccept", new String[0]));

        // /msg & /reply & /msgtoggle
        assertTrue(runtime.onCommand(alice, cmd("msg"), "msg", new String[]{"Bob", "hello", "there"}));
        assertTrue(runtime.onCommand(bob, cmd("reply"), "reply", new String[]{"hi!"}));
        assertTrue(runtime.onCommand(bob, cmd("msgtoggle"), "msgtoggle", new String[0]));

        // /god cancels damage
        assertTrue(runtime.onCommand(alice, cmd("god"), "god", new String[0]));
        @SuppressWarnings("deprecation")
        EntityDamageEvent dmg = new EntityDamageEvent(alice, EntityDamageEvent.DamageCause.FALL, 5.0);
        runtime.onDamage(dmg);
        assertTrue(dmg.isCancelled());

        // /gamemode alias gmc & /heal & /fly
        assertTrue(runtime.onCommand(alice, cmd("gamemode"), "gmc", new String[0]));
        verify(alice).setGameMode(GameMode.CREATIVE);
        assertTrue(runtime.onCommand(alice, cmd("heal"), "heal", new String[0]));
        verify(alice).setHealth(20.0);
        assertTrue(runtime.onCommand(alice, cmd("fly"), "fly", new String[0]));
        verify(alice).setAllowFlight(true);

        // 1.20+-only command gives clear notice
        assertTrue(runtime.onCommand(alice, cmd("ice"), "ice", new String[0]));
        verify(alice).sendMessage(argThat((String msg) -> msg.contains("requires Minecraft 1.20+")));

        // Color helper works safely
        assertEquals("§aHello", LegacyCompat.color("&aHello"));

        runtime.onDisable();
    }

    private static Command cmd(String name) {
        Command c = mock(Command.class);
        when(c.getName()).thenReturn(name);
        return c;
    }

    private static Player mockPlayer(String name, World world, double x, double y, double z, boolean op) {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        when(p.isOnline()).thenReturn(true);
        when(p.isOp()).thenReturn(op);
        when(p.getWorld()).thenReturn(world);
        when(p.getLocation()).thenReturn(new Location(world, x, y, z));
        when(p.teleport(any(Location.class))).thenReturn(true);
        when(p.hasPermission(anyString())).thenReturn(op);
        when(p.isPermissionSet(anyString())).thenReturn(false);
        Set<PermissionAttachmentInfo> perms = Collections.emptySet();
        when(p.getEffectivePermissions()).thenReturn(perms);
        AttributeInstance maxHealth = mock(AttributeInstance.class);
        when(maxHealth.getValue()).thenReturn(20.0);
        when(p.getAttribute(Attribute.GENERIC_MAX_HEALTH)).thenReturn(maxHealth);
        PlayerInventory inv = mock(PlayerInventory.class);
        when(p.getInventory()).thenReturn(inv);
        when(inv.getItemInMainHand()).thenReturn(new ItemStack(Material.AIR));
        return p;
    }
}
