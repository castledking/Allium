package codes.castled.allium;

import codes.castled.allium.legacy.LegacyBukkitRuntime;
import codes.castled.allium.legacy.LegacyCompat;
import org.bukkit.BanList;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyModulesTest {

    @TempDir
    Path tempDir;

    private record Harness(
            JavaPlugin plugin,
            Server server,
            World world,
            LegacyBukkitRuntime runtime,
            List<Player> onlinePlayers,
            BanList nameBans,
            BanList ipBans
    ) {
        Player addPlayer(String name, UUID id, boolean op) {
            Player p = mock(Player.class);
            PlayerInventory inv = mock(PlayerInventory.class);
            when(p.getName()).thenReturn(name);
            when(p.getDisplayName()).thenReturn(name);
            when(p.getUniqueId()).thenReturn(id);
            when(p.isOnline()).thenReturn(true);
            when(p.isOp()).thenReturn(op);
            when(p.hasPermission(anyString())).thenReturn(op);
            when(p.getWorld()).thenReturn(world);
            when(p.getLocation()).thenReturn(new Location(world, 10.0, 64.0, 20.0));
            when(p.getInventory()).thenReturn(inv);
            when(inv.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
            when(inv.getStorageContents()).thenReturn(new ItemStack[36]);
            when(inv.getContents()).thenReturn(new ItemStack[36]);
            when(inv.getArmorContents()).thenReturn(new ItemStack[4]);
            onlinePlayers.add(p);
            when(server.getPlayerExact(name)).thenReturn(p);
            when(server.getPlayer(name)).thenReturn(p);
            when(server.getPlayer(id)).thenReturn(p);
            when(server.getOfflinePlayer(name)).thenReturn(p);
            when(server.getOfflinePlayer(id)).thenReturn(p);
            return p;
        }

        boolean run(Player sender, String cmdName, String... args) {
            Command cmd = mock(Command.class);
            when(cmd.getName()).thenReturn(cmdName);
            return runtime.onCommand(sender, cmd, cmdName, args);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Harness createHarness() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager pm = mock(PluginManager.class);
        World world = mock(World.class);
        BanList nameBans = mock(BanList.class);
        BanList ipBans = mock(BanList.class);
        PluginDescriptionFile desc = new PluginDescriptionFile("Allium", "1.0.2", "codes.castled.allium.AlliumBootstrap");
        List<Player> online = new ArrayList<>();

        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDescription()).thenReturn(desc);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LegacyModulesTest"));
        when(server.getPluginManager()).thenReturn(pm);
        when(server.getName()).thenReturn("Spigot");
        when(server.getVersion()).thenReturn("git-Spigot-1.12.2");
        when(server.getBukkitVersion()).thenReturn("1.12.2-R0.1-SNAPSHOT");
        when(server.getMaxPlayers()).thenReturn(100);
        when(server.getOnlinePlayers()).thenAnswer(inv -> (Collection) online);
        when(server.getWorlds()).thenReturn(Collections.singletonList(world));
        when(server.getWorld("world")).thenReturn(world);
        when(server.getBanList(BanList.Type.NAME)).thenReturn(nameBans);
        when(server.getBanList(BanList.Type.IP)).thenReturn(ipBans);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getMaxHeight()).thenReturn(256);
        when(world.getSpawnLocation()).thenReturn(new Location(world, 0.0, 64.0, 0.0));
        when(world.getPlayers()).thenAnswer(inv -> online);
        when(world.getLoadedChunks()).thenReturn(new org.bukkit.Chunk[0]);
        when(world.getEntities()).thenReturn(Collections.emptyList());

        LegacyBukkitRuntime runtime = new LegacyBukkitRuntime(plugin, ServerVersion.parse("1.12.2-R0.1-SNAPSHOT"), 8);
        runtime.onEnable();
        return new Harness(plugin, server, world, runtime, online, nameBans, ipBans);
    }

    @Test
    void allNewLegacyModuleClassesCompileToJava8Bytecode() throws Exception {
        String[] classNames = {
                "codes.castled.allium.legacy.LegacyBukkitRuntime",
                "codes.castled.allium.legacy.LegacyCompat",
                "codes.castled.allium.legacy.LegacyConfigManager",
                "codes.castled.allium.legacy.LegacyLanguageManager",
                "codes.castled.allium.legacy.LegacyMessageManager",
                "codes.castled.allium.legacy.LegacyCommandManager",
                "codes.castled.allium.legacy.LegacyHelpCommand",
                "codes.castled.allium.legacy.LegacyEconomyModule",
                "codes.castled.allium.legacy.LegacyKitsModule",
                "codes.castled.allium.legacy.LegacyModerationModule",
                "codes.castled.allium.legacy.LegacyPlayerInfoModule",
                "codes.castled.allium.legacy.LegacyTravelModule",
                "codes.castled.allium.legacy.LegacyUtilitiesModule"
        };
        for (String name : classNames) {
            Class<?> clazz = Class.forName(name);
            String path = name.replace('.', '/') + ".class";
            try (InputStream in = clazz.getClassLoader().getResourceAsStream(path)) {
                assertNotNull(in, "Missing classfile for " + name);
                DataInputStream dis = new DataInputStream(in);
                assertEquals(0xCAFEBABE, dis.readInt());
                dis.readUnsignedShort();
                int major = dis.readUnsignedShort();
                assertTrue(major <= 52, name + " must be Java 8 bytecode (<= 52), got " + major);
            }
        }
    }

    @Test
    void compatParsersHandleDurationsAndMoney() {
        assertEquals(90_000L, LegacyCompat.parseDurationMillis("1m30s"));
        assertEquals(86_400_000L, LegacyCompat.parseDurationMillis("1d"));
        assertEquals(-1L, LegacyCompat.parseDurationMillis("abc"));
        assertEquals("1d 2h 3m 4s", LegacyCompat.formatDuration(86_400_000L + 7_200_000L + 180_000L + 4_000L));

        assertEquals(new BigDecimal("2500.00"), LegacyCompat.parseMoneyAmount("2.5k"));
        assertEquals(new BigDecimal("1000000.00"), LegacyCompat.parseMoneyAmount("1m"));
        assertNull(LegacyCompat.parseMoneyAmount("-5"));
        assertEquals("$1,234.50", LegacyCompat.formatMoney(new BigDecimal("1234.5")));
    }

    @Test
    void economyEcoPayPayToggleBaltopAndWorthWorkEndToEnd() {
        Harness h = createHarness();
        Player alice = h.addPlayer("Alice", UUID.randomUUID(), true);
        Player bob = h.addPlayer("Bob", UUID.randomUUID(), true);

        assertTrue(h.run(alice, "eco", "give", "Alice", "1k"));
        assertTrue(h.run(alice, "pay", "Bob", "250"));
        verify(alice).sendMessage(contains("$250.00"));
        verify(bob).sendMessage(contains("$250.00"));

        // PayToggle blocks payments
        assertTrue(h.run(bob, "paytoggle"));
        assertTrue(h.run(alice, "pay", "Bob", "50"));
        verify(alice).sendMessage(contains("not accepting payments"));

        // Baltop and SetWorth / Worth
        assertTrue(h.run(alice, "baltop"));
        verify(alice).sendMessage(contains("Top Balances"));

        assertTrue(h.run(alice, "setworth", "DIAMOND", "100"));
        assertTrue(h.run(alice, "worth", "DIAMOND"));
        verify(alice, atLeastOnce()).sendMessage(contains("$100.00"));
    }

    @Test
    void moderationMuteFreezeJailWarnBanAndSocialSpyWork() {
        Harness h = createHarness();
        Player mod = h.addPlayer("Mod", UUID.randomUUID(), true);
        Player grief = h.addPlayer("Grief", UUID.randomUUID(), false);
        when(grief.hasPermission("allium.msg")).thenReturn(true);

        // Mute blocks private messages
        assertTrue(h.run(mod, "mute", "Grief", "10m", "spam"));
        assertTrue(h.run(mod, "mutes"));
        verify(mod, atLeastOnce()).sendMessage(contains("Muted players (1)"));

        assertTrue(h.run(grief, "msg", "Mod", "hello"));
        verify(grief).sendMessage(contains("while muted"));

        assertTrue(h.run(mod, "unmute", "Grief"));

        // SocialSpy notifies mod on PM
        Player friend = h.addPlayer("Friend", UUID.randomUUID(), false);
        assertTrue(h.run(mod, "socialspy"));
        assertTrue(h.run(grief, "msg", "Friend", "secret"));
        verify(mod).sendMessage(contains("[SocialSpy]"));

        // Jail + Warn + Notes + Ban
        assertTrue(h.run(mod, "setjail", "cell1"));
        assertTrue(h.run(mod, "jail", "Grief", "cell1", "5m"));
        assertTrue(h.run(mod, "jailedplayers"));
        verify(mod).sendMessage(contains("Jailed players (1)"));
        assertTrue(h.run(mod, "unjail", "Grief"));

        assertTrue(h.run(mod, "warn", "Grief", "language"));
        assertTrue(h.run(mod, "notes", "Grief"));
        verify(mod, atLeastOnce()).sendMessage(contains("language"));
        assertTrue(h.run(mod, "unnote", "Grief", "1"));

        assertTrue(h.run(mod, "ban", "Grief", "griefing"));
        verify(h.nameBans()).addBan(eq("Grief"), contains("griefing"), isNull(), eq("Mod"));
        verify(grief).kickPlayer(contains("griefing"));
    }

    @Test
    void travelTpAutoTpToggleTopTpPosAndRenameHomeWork() {
        Harness h = createHarness();
        Player alice = h.addPlayer("Alice", UUID.randomUUID(), true);
        Player bob = h.addPlayer("Bob", UUID.randomUUID(), true);

        // Home + renamehome
        assertTrue(h.run(alice, "sethome", "base"));
        assertTrue(h.run(alice, "renamehome", "base", "castle"));
        verify(alice).sendMessage(contains("Renamed home 'base' to 'castle'"));

        // TpToggle blocks TPA
        when(alice.hasPermission("allium.tptoggle.bypass")).thenReturn(false);
        assertTrue(h.run(bob, "tptoggle"));
        assertTrue(h.run(alice, "tpa", "Bob"));
        verify(alice).sendMessage(contains("not accepting teleport requests"));
        assertTrue(h.run(bob, "tptoggle")); // re-enable

        // TpAuto immediately teleports
        assertTrue(h.run(bob, "tpauto"));
        assertTrue(h.run(alice, "tpa", "Bob"));
        verify(alice).teleport(any(Location.class));

        // TpPos + Top
        when(h.world().getHighestBlockYAt(10, 20)).thenReturn(70);
        assertTrue(h.run(alice, "top"));
        assertTrue(h.run(alice, "tppos", "100", "80", "200"));
        verify(alice).sendMessage(contains("100.0, 80.0, 200.0"));
    }

    @Test
    void kitsCreateClaimCooldownAndResetWork() {
        Harness h = createHarness();
        Player admin = h.addPlayer("Admin", UUID.randomUUID(), true);
        Player user = h.addPlayer("User", UUID.randomUUID(), false);
        when(user.hasPermission("allium.kits.starter")).thenReturn(true);
        when(user.hasPermission("allium.kits")).thenReturn(true);

        ItemStack[] inv = new ItemStack[36];
        inv[0] = new ItemStack(Material.STONE, 16);
        when(admin.getInventory().getStorageContents()).thenReturn(inv);

        assertTrue(h.run(admin, "createkit", "starter", "60"));
        assertTrue(h.run(user, "kits"));
        verify(user).sendMessage(contains("starter"));

        assertTrue(h.run(user, "kit", "starter"));
        verify(user).sendMessage(contains("Claimed kit 'starter'"));

        // Cooldown blocks second immediate claim
        assertTrue(h.run(user, "kit", "starter"));
        verify(user).sendMessage(contains("again in"));

        // Admin resets cooldown
        assertTrue(h.run(admin, "kitreset", "User", "starter"));
        assertTrue(h.run(user, "kit", "starter"));
        verify(user, times(2)).sendMessage(contains("Claimed kit 'starter'"));
    }

    @Test
    void playerInfoAfkIgnoreMailNickAndUtilitiesWork() {
        Harness h = createHarness();
        Player alice = h.addPlayer("Alice", UUID.randomUUID(), true);
        Player bob = h.addPlayer("Bob", UUID.randomUUID(), true);

        // Ignore blocks /msg
        assertTrue(h.run(bob, "ignore", "Alice"));
        when(alice.hasPermission("allium.ignore.bypass")).thenReturn(false);
        assertTrue(h.run(alice, "msg", "Bob", "hi"));
        verify(alice).sendMessage(contains("ignoring you"));
        assertTrue(h.run(bob, "unignore", "Alice"));

        // Mail send + read + clear
        assertTrue(h.run(alice, "mail", "send", "Bob", "Meet at spawn"));
        assertTrue(h.run(bob, "mail", "read"));
        verify(bob).sendMessage(contains("Meet at spawn"));
        assertTrue(h.run(bob, "mail", "clear"));

        // Nick + Whois + List + AFK + GC + Compass + Glow + Pvp
        assertTrue(h.run(alice, "nick", "&dAllie"));
        verify(alice).setDisplayName(contains("Allie"));
        assertTrue(h.run(alice, "whois", "Alice"));
        verify(alice).sendMessage(contains("=== Whois: Alice ==="));
        assertTrue(h.run(alice, "list"));
        assertTrue(h.run(alice, "afk", "brb"));
        assertTrue(h.run(alice, "gc"));
        verify(alice).sendMessage(contains("Memory:"));
        assertTrue(h.run(alice, "compass"));
        verify(alice).sendMessage(contains("Facing:"));
        assertTrue(h.run(alice, "glow"));
        verify(alice).setGlowing(true);
        assertTrue(h.run(alice, "pvp"));
        verify(alice).sendMessage(contains("PvP disabled"));
        assertTrue(h.run(alice, "help"));
        verify(alice, atLeastOnce()).sendMessage(contains("Help Menu"));
    }

    @Test
    void configLangMessageSoundsActionbarCommandManagerAndHelpWorkTogether() throws Exception {
        // Seed custom config.yml: es_ES locale, disabled-commands: [gm], modules.economy: false, currency €
        java.nio.file.Files.writeString(tempDir.resolve("config.yml"),
                "settings:\n"
                        + "  locale: es_ES\n"
                        + "disabled-commands:\n"
                        + "  - gm\n"
                        + "modules:\n"
                        + "  economy: false\n"
                        + "sounds:\n"
                        + "  defaults:\n"
                        + "    volume: 1.0\n"
                        + "    pitch: 1.0\n"
                        + "  messages:\n"
                        + "    fallback-volume: 0.8\n"
                        + "    scale-sent-sound: true\n");
        // Seed a custom lang entry with [actionbar] and [sound]
        java.nio.file.Files.createDirectories(tempDir.resolve("lang"));
        java.nio.file.Files.writeString(tempDir.resolve("lang/es_ES.yml"),
                "prefix: \"&7[&dAllium&7]\"\n"
                        + "custom:\n"
                        + "  toast:\n"
                        + "    - \"{prefix} &aHola {name}\"\n"
                        + "    - \"[actionbar] &eBar {name}\"\n"
                        + "    - \"[sound] block.note_block.chime 1.2 0.9\"\n");

        // Load real plugin.yml so LegacyCommandManager sees real Allium commands + aliases (gamemode -> [gm])
        PluginDescriptionFile realDesc;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(in);
            realDesc = new PluginDescriptionFile(in);
        }

        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager pm = mock(PluginManager.class);
        World world = mock(World.class);
        List<Player> online = new ArrayList<>();

        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDescription()).thenReturn(realDesc);
        when(plugin.getName()).thenReturn("Allium");
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LegacyCoreSystemsTest"));
        when(plugin.getResource(anyString())).thenAnswer(inv ->
                getClass().getClassLoader().getResourceAsStream(inv.getArgument(0)));
        when(server.getPluginManager()).thenReturn(pm);
        when(server.getName()).thenReturn("Spigot");
        when(server.getVersion()).thenReturn("git-Spigot-1.12.2");
        when(server.getBukkitVersion()).thenReturn("1.12.2-R0.1-SNAPSHOT");
        when(server.getOnlinePlayers()).thenAnswer(inv -> (Collection) online);
        when(server.getWorlds()).thenReturn(Collections.singletonList(world));
        when(world.getName()).thenReturn("world");

        LegacyBukkitRuntime runtime = new LegacyBukkitRuntime(plugin, ServerVersion.parse("1.12.2-R0.1-SNAPSHOT"), 8);
        runtime.onEnable();

        Player alice = mock(Player.class);
        Player.Spigot spigot = mock(Player.Spigot.class);
        when(alice.getName()).thenReturn("Alice");
        when(alice.getUniqueId()).thenReturn(UUID.randomUUID());
        when(alice.isOnline()).thenReturn(true);
        when(alice.isOp()).thenReturn(true);
        when(alice.hasPermission(anyString())).thenReturn(true);
        when(alice.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(alice.spigot()).thenReturn(spigot);
        online.add(alice);

        // 1. CommandManager disables 'gamemode' via alias 'gm' and blocks disabled 'economy' module
        Command gmCmd = mock(Command.class);
        when(gmCmd.getName()).thenReturn("gamemode");
        assertTrue(runtime.onCommand(alice, gmCmd, "gm", new String[]{"creative"}));
        verify(alice, never()).setGameMode(any());
        assertTrue(runtime.onTabComplete(alice, gmCmd, "gm", new String[]{""}).isEmpty());

        Command balCmd = mock(Command.class);
        when(balCmd.getName()).thenReturn("balance");
        assertTrue(runtime.onCommand(alice, balCmd, "balance", new String[0]));
        verify(alice).sendMessage(contains("Module 'economy' is disabled"));

        // 2. /messagesounds (/mfx) status, volume, toggle, reset (localized in es_ES!)
        Command mfxCmd = mock(Command.class);
        when(mfxCmd.getName()).thenReturn("messagesounds");
        assertTrue(runtime.onCommand(alice, mfxCmd, "mfx", new String[0]));
        assertTrue(runtime.onCommand(alice, mfxCmd, "mfx", new String[]{"volume", "0.5"}));
        assertTrue(runtime.onCommand(alice, mfxCmd, "mfx", new String[]{"toggle"}));
        assertTrue(runtime.onCommand(alice, mfxCmd, "mfx", new String[]{"toggle"}));
        assertTrue(runtime.onCommand(alice, mfxCmd, "mfx", new String[]{"reset"}));

        // 3. Lang + [actionbar] + 1.12.2 sound remapping (block.note_block.chime -> block.note.chime)
        LegacyCompat.playSound(alice, "minecraft:block.note_block.chime", 0.9f, 1.2f, true);
        verify(alice).playSound(any(Location.class), eq("block.note.chime"), eq(0.9f), eq(1.2f));
        LegacyCompat.sendActionBar(alice, "§eBar Alice");
        verify(spigot).sendMessage(eq(net.md_5.bungee.api.ChatMessageType.ACTION_BAR),
                any(net.md_5.bungee.api.chat.BaseComponent[].class));

        // 4. /help paginated menu, exact command card, search, fuzzy suggestion, tab completion
        Command helpCmd = mock(Command.class);
        when(helpCmd.getName()).thenReturn("help");
        assertTrue(runtime.onCommand(alice, helpCmd, "help", new String[0]));
        assertTrue(runtime.onCommand(alice, helpCmd, "help", new String[]{"/heal"}));
        assertTrue(runtime.onCommand(alice, helpCmd, "help", new String[]{"teleport"}));
        assertTrue(runtime.onCommand(alice, helpCmd, "help", new String[]{"heall"}));
        List<String> completions = runtime.onTabComplete(alice, helpCmd, "help", new String[]{"hea"});
        assertTrue(completions.contains("heal"), "Expected /help tab completion to include 'heal', got " + completions);
        assertFalse(completions.contains("gamemode"), "Disabled command should not appear in /help completions");

        runtime.onDisable();
    }
}
