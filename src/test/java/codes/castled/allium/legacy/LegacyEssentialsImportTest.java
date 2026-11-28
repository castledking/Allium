package codes.castled.allium.legacy;

import codes.castled.allium.ServerVersion;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.UnsafeValues;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LegacyEssentialsImportTest {

    @TempDir
    Path tempDir;

    private Server previousBukkitServer;

    @AfterEach
    void restoreBukkitServer() throws Exception {
        setBukkitServer(previousBukkitServer);
    }

    private static void setBukkitServer(Server server) throws Exception {
        var f = Bukkit.class.getDeclaredField("server");
        f.setAccessible(true);
        f.set(null, server);
    }

    private record Env(
            JavaPlugin plugin,
            Server server,
            World world,
            Path dataDir,
            Path essentialsDir,
            List<Player> online,
            AtomicInteger reloadCount,
            LegacyEssentialsImport importer
    ) {}

    private Env createEnv() throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        Path dataDir = pluginsDir.resolve("Allium");
        Path essentialsDir = pluginsDir.resolve("Essentials");
        Files.createDirectories(dataDir);
        Files.createDirectories(essentialsDir);

        Server server = mock(Server.class);
        World world = mock(World.class);
        UUID worldId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(worldId);

        List<Player> online = new ArrayList<>();
        when(server.getName()).thenReturn("Paper");
        when(server.getVersion()).thenReturn("git-Paper-1.12.2");
        when(server.getWorlds()).thenReturn(Collections.singletonList(world));
        when(server.getWorld("world")).thenReturn(world);
        when(server.getWorld(worldId)).thenReturn(world);
        when(server.getOnlinePlayers()).thenAnswer(i -> online);
        when(server.isPrimaryThread()).thenReturn(true);

        ItemFactory itemFactory = mock(ItemFactory.class);
        when(itemFactory.getItemMeta(any(Material.class))).thenAnswer(inv -> {
            ItemMeta meta = mock(ItemMeta.class);
            when(meta.clone()).thenReturn(meta);
            return meta;
        });
        when(itemFactory.isApplicable(any(), any(Material.class))).thenReturn(true);
        when(itemFactory.asMetaFor(any(), any(Material.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemFactory.updateMaterial(any(), any(Material.class))).thenAnswer(inv -> inv.getArgument(1));
        when(itemFactory.equals(any(), any())).thenReturn(true);
        when(server.getItemFactory()).thenReturn(itemFactory);
        @SuppressWarnings("deprecation")
        UnsafeValues unsafe = mock(UnsafeValues.class);
        when(unsafe.getDataVersion()).thenReturn(1343);
        when(unsafe.fromLegacy(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));
        when(server.getUnsafe()).thenReturn(unsafe);

        previousBukkitServer = Bukkit.getServer();
        setBukkitServer(server);

        JavaPlugin plugin = mock(JavaPlugin.class);
        PluginDescriptionFile desc = new PluginDescriptionFile("Allium", "1.0.2", "codes.castled.allium.AlliumPlugin");
        when(plugin.getDescription()).thenReturn(desc);
        when(plugin.getDataFolder()).thenReturn(dataDir.toFile());
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LegacyEssentialsImportTest"));

        LegacyConfigManager cfg = new LegacyConfigManager(plugin);
        cfg.loadAll();
        LegacyLanguageManager lang = new LegacyLanguageManager(plugin, cfg);
        lang.load();
        LegacyMessageManager messages = new LegacyMessageManager(plugin, cfg, lang, ServerVersion.parse("1.12.2"));
        messages.enable();

        AtomicInteger reloads = new AtomicInteger();
        LegacyEssentialsImport importer = new LegacyEssentialsImport(plugin, messages, reloads::incrementAndGet, true);
        return new Env(plugin, server, world, dataDir, essentialsDir, online, reloads, importer);
    }

    private static ConsoleCommandSender console() {
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class);
        when(sender.isOp()).thenReturn(true);
        when(sender.hasPermission(anyString())).thenReturn(true);
        return sender;
    }

    private static List<String> capturedMessages(ConsoleCommandSender sender) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues();
    }

    private static String extractRunId(List<String> messages) {
        Pattern p = Pattern.compile("essentials-[0-9]{8}-[0-9]{6}(?:-[0-9]{1,3})?");
        for (String m : messages) {
            Matcher matcher = p.matcher(m);
            if (matcher.find()) return matcher.group();
        }
        return null;
    }

    @Test
    void classTargetsJava8() throws Exception {
        String resource = LegacyEssentialsImport.class.getName().replace('.', '/') + ".class";
        try (InputStream in = LegacyEssentialsImport.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "Missing class file for " + resource);
            DataInputStream data = new DataInputStream(in);
            assertEquals(0xCAFEBABE, data.readInt());
            data.readUnsignedShort();
            int major = data.readUnsignedShort();
            assertTrue(major <= 52, "Expected Java 8 bytecode (<=52), found " + major);
        }
    }

    @Test
    void dryRunAndApplyImportAllDomainsAndReload() throws Exception {
        Env env = createEnv();
        UUID aliceId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID bobId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID npcId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        long futureTs = System.currentTimeMillis() + 3_600_000L;
        long recentClaim = System.currentTimeMillis() - 5_000L;

        // World mapping in import/essentials.yml: old_world -> world
        Files.createDirectories(env.dataDir().resolve("import"));
        Files.writeString(env.dataDir().resolve("import/essentials.yml"),
                "worlds:\n  old_world: world\n", StandardCharsets.UTF_8);

        // Pre-existing conflicting warp ("conflict") & balance for bob so we verify conflicts are preserved
        Files.writeString(env.dataDir().resolve("warps.yml"),
                "warps:\n  conflict:\n    world: world\n    x: 1.0\n    y: 64.0\n    z: 1.0\n",
                StandardCharsets.UTF_8);

        // Essentials data
        Files.createDirectories(env.essentialsDir().resolve("userdata"));
        Files.createDirectories(env.essentialsDir().resolve("warps"));

        Files.writeString(env.essentialsDir().resolve("userdata/" + aliceId + ".yml"),
                "last-account-name: Alice\n"
                        + "money: '250.50'\n"
                        + "nickname: '&dAli'\n"
                        + "accepting-pay: false\n"
                        + "confirm-pay: true\n"
                        + "teleportenabled: false\n"
                        + "teleportauto: true\n"
                        + "msgtoggle: true\n"
                        + "ignore:\n  - " + bobId + "\n"
                        + "muted: true\n"
                        + "mute-reason: spam\n"
                        + "jailed: true\n"
                        + "jail: cell1\n"
                        + "timestamps:\n"
                        + "  logout: 1700000000000\n"
                        + "  mute: " + futureTs + "\n"
                        + "  jail: " + futureTs + "\n"
                        + "  kits:\n"
                        + "    starter: " + recentClaim + "\n"
                        + "homes:\n"
                        + "  base:\n"
                        + "    world-name: old_world\n"
                        + "    x: 12.5\n"
                        + "    y: 70.0\n"
                        + "    z: -4.5\n"
                        + "    yaw: 90.0\n"
                        + "    pitch: 0.0\n"
                        + "logoutlocation:\n"
                        + "  world-name: world\n"
                        + "  x: 3.0\n"
                        + "  y: 65.0\n"
                        + "  z: 4.0\n"
                        + "  yaw: 0.0\n"
                        + "  pitch: 0.0\n"
                        + "mail:\n"
                        + "  - 'Bob: Hello legacy'\n"
                        + "  - sender-name: Staff\n"
                        + "    message: Welcome!\n"
                        + "    read: false\n"
                        + "    timestamp: 1700000001000\n",
                StandardCharsets.UTF_8);

        Files.writeString(env.essentialsDir().resolve("userdata/" + npcId + ".yml"),
                "npc: true\nlast-account-name: Shopkeeper\nmoney: '9999'\n", StandardCharsets.UTF_8);
        Files.writeString(env.essentialsDir().resolve("userdata/not-a-uuid.yml"),
                "money: '10'\n", StandardCharsets.UTF_8);

        Files.writeString(env.essentialsDir().resolve("warps/shop.yml"),
                "name: Shop\nworld-name: old_world\nx: 100.0\ny: 64.0\nz: 200.0\nyaw: 180.0\npitch: 0.0\n",
                StandardCharsets.UTF_8);
        Files.writeString(env.essentialsDir().resolve("warps/conflict.yml"),
                "name: conflict\nworld-name: world\nx: 999.0\ny: 64.0\nz: 999.0\n",
                StandardCharsets.UTF_8);
        Files.writeString(env.essentialsDir().resolve("jail.yml"),
                "jails:\n  cell1:\n    world-name: world\n    x: 50.0\n    y: 64.0\n    z: 50.0\n",
                StandardCharsets.UTF_8);
        Files.writeString(env.essentialsDir().resolve("spawn.yml"),
                "spawns:\n  default:\n    world-name: world\n    x: 0.5\n    y: 64.0\n    z: 0.5\n  vip:\n    world-name: world\n    x: 5.0\n    y: 64.0\n    z: 5.0\n",
                StandardCharsets.UTF_8);
        Files.writeString(env.essentialsDir().resolve("kits.yml"),
                "kits:\n  starter:\n    delay: 3600\n    items:\n      - stone 16\n      - diamond_sword 1 sharpness:2 name:&bHero_Blade\n",
                StandardCharsets.UTF_8);
        Files.writeString(env.essentialsDir().resolve("worth.yml"),
                "worth:\n  diamond: 100.0\n", StandardCharsets.UTF_8);
        Files.writeString(env.essentialsDir().resolve("config.yml"),
                "enabledSigns:\n  - warp\n  - custom_unsupported\n", StandardCharsets.UTF_8);

        // 1. Dry run
        ConsoleCommandSender drySender = console();
        assertTrue(env.importer().execute(drySender, new String[]{"essentials"}));
        String runId = extractRunId(capturedMessages(drySender));
        assertNotNull(runId, "Dry run should emit a run ID");
        Path runDir = env.dataDir().resolve("import/essentials").resolve(runId);
        assertTrue(Files.isRegularFile(runDir.resolve("plan.json")));
        assertTrue(Files.isRegularFile(runDir.resolve("records.jsonl")));
        assertTrue(Files.isRegularFile(runDir.resolve("summary.txt")));

        // Tab completion offers runId
        List<String> tabRuns = env.importer().complete(drySender, new String[]{"essentials", "apply", ""});
        assertTrue(tabRuns.contains(runId));

        // 2. Apply
        ConsoleCommandSender applySender = console();
        assertTrue(env.importer().execute(applySender, new String[]{"essentials", "apply", runId}));
        assertEquals(1, env.reloadCount().get(), "Affected legacy modules must be reloaded after apply");
        assertTrue(Files.isRegularFile(runDir.resolve("apply.jsonl")));
        assertTrue(Files.isRegularFile(runDir.resolve("apply-summary.txt")));

        // Verify warps (shop imported, conflict preserved untouched)
        YamlConfiguration warps = YamlConfiguration.loadConfiguration(env.dataDir().resolve("warps.yml").toFile());
        assertEquals("world", warps.getString("warps.shop.world"));
        assertEquals(100.0, warps.getDouble("warps.shop.x"), 0.01);
        assertEquals(1.0, warps.getDouble("warps.conflict.x"), 0.01);

        // Verify spawn
        YamlConfiguration spawn = YamlConfiguration.loadConfiguration(env.dataDir().resolve("spawn.yml").toFile());
        assertEquals("world", spawn.getString("spawn.world"));
        assertEquals(0.5, spawn.getDouble("spawn.x"), 0.01);

        // Verify homes (.properties format readable by portable Homes)
        Path aliceHomes = env.dataDir().resolve("homes").resolve(aliceId + ".properties");
        assertTrue(Files.isRegularFile(aliceHomes));
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(aliceHomes, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        assertTrue(props.containsKey("base"));
        assertTrue(props.getProperty("base").startsWith(env.world().getUID() + "\t12.5\t70.0\t-4.5"));

        // Verify economy (balance + pay prefs; NPC not imported)
        YamlConfiguration econ = YamlConfiguration.loadConfiguration(env.dataDir().resolve("economy-legacy.yml").toFile());
        assertEquals("250.50", econ.getString("accounts." + aliceId + ".balance"));
        assertTrue(econ.getBoolean("accounts." + aliceId + ".pay-disabled"));
        assertTrue(econ.getBoolean("accounts." + aliceId + ".pay-confirm"));
        assertFalse(econ.contains("accounts." + npcId));

        // Verify worth
        YamlConfiguration worth = YamlConfiguration.loadConfiguration(env.dataDir().resolve("worth.yml").toFile());
        assertEquals(100.0, worth.getDouble("worth.diamond"), 0.01);

        // Verify kits & cooldowns
        YamlConfiguration kits = YamlConfiguration.loadConfiguration(env.dataDir().resolve("kits-legacy.yml").toFile());
        assertEquals(3600L, kits.getLong("kits.starter.cooldown"));
        assertEquals(2, kits.getList("kits.starter.items").size());
        YamlConfiguration kitCd = YamlConfiguration.loadConfiguration(env.dataDir().resolve("kit-cooldowns.yml").toFile());
        assertTrue(kitCd.getLong("cooldowns." + aliceId + ".starter") > System.currentTimeMillis());

        // Verify player info (name, nick, ignores, mail)
        YamlConfiguration info = YamlConfiguration.loadConfiguration(env.dataDir().resolve("player-info-legacy.yml").toFile());
        assertEquals("Alice", info.getString("players." + aliceId + ".name"));
        assertEquals("&dAli", info.getString("players." + aliceId + ".nick"));
        assertEquals(Collections.singletonList(bobId.toString()), info.getStringList("players." + aliceId + ".ignores"));
        List<String> mail = info.getStringList("players." + aliceId + ".mail");
        assertEquals(2, mail.size());
        assertTrue(mail.contains("Bob: Hello legacy"));
        assertTrue(mail.contains("Staff: Welcome!"));

        // Verify player settings (msgtoggle=true -> msg=false)
        YamlConfiguration pSettings = YamlConfiguration.loadConfiguration(env.dataDir().resolve("player-settings-legacy.yml").toFile());
        assertFalse(pSettings.getBoolean("players." + aliceId + ".tpa"));
        assertTrue(pSettings.getBoolean("players." + aliceId + ".tp-auto"));
        assertFalse(pSettings.getBoolean("players." + aliceId + ".msg"));

        // Verify logout location
        YamlConfiguration logout = YamlConfiguration.loadConfiguration(env.dataDir().resolve("logout-locations-legacy.yml").toFile());
        assertEquals(env.world().getUID().toString(), logout.getString(aliceId + ".world"));
        assertEquals(3.0, logout.getDouble(aliceId + ".x"), 0.01);

        // Verify moderation (jails, mute, jailed)
        YamlConfiguration mod = YamlConfiguration.loadConfiguration(env.dataDir().resolve("moderation-legacy.yml").toFile());
        assertEquals("world", mod.getString("jails.cell1.world"));
        assertEquals("spam", mod.getString("mutes." + aliceId + ".reason"));
        assertEquals("cell1", mod.getString("jailed." + aliceId + ".jail"));

        // 3. Re-applying the same run ID is refused
        ConsoleCommandSender replaySender = console();
        env.importer().execute(replaySender, new String[]{"essentials", "apply", runId});
        assertTrue(capturedMessages(replaySender).stream().anyMatch(m -> m.contains("already applied")));

        // 4. Second dry run classifies imported records as REPLAYED (0 to import)
        ConsoleCommandSender dry2 = console();
        env.importer().execute(dry2, new String[]{"essentials"});
        String runId2 = extractRunId(capturedMessages(dry2));
        assertNotNull(runId2);
        String planJson2 = Files.readString(env.dataDir().resolve("import/essentials").resolve(runId2).resolve("plan.json"));
        assertTrue(planJson2.contains("\"importCount\": 0"), "Second dry run should have 0 records to import:\n" + planJson2);
    }

    @Test
    void refusesApplyWhenPlayersOnlineOrSourceChanged() throws Exception {
        Env env = createEnv();
        Files.createDirectories(env.essentialsDir().resolve("warps"));
        Path warpFile = env.essentialsDir().resolve("warps/hub.yml");
        Files.writeString(warpFile, "name: hub\nworld-name: world\nx: 10.0\ny: 64.0\nz: 10.0\n", StandardCharsets.UTF_8);

        ConsoleCommandSender dry = console();
        env.importer().execute(dry, new String[]{"essentials"});
        String runId = extractRunId(capturedMessages(dry));
        assertNotNull(runId);

        // Online player blocks apply
        env.online().add(mock(Player.class));
        ConsoleCommandSender onlineAttempt = console();
        env.importer().execute(onlineAttempt, new String[]{"essentials", "apply", runId});
        assertTrue(capturedMessages(onlineAttempt).stream().anyMatch(m -> m.contains("players are online")));
        assertEquals(0, env.reloadCount().get());

        // Clear online players, mutate source file -> SHA-256 mismatch refuses apply
        env.online().clear();
        Files.writeString(warpFile, "name: hub\nworld-name: world\nx: 99.0\ny: 64.0\nz: 10.0\n", StandardCharsets.UTF_8);
        ConsoleCommandSender mutatedAttempt = console();
        env.importer().execute(mutatedAttempt, new String[]{"essentials", "apply", runId});
        assertTrue(capturedMessages(mutatedAttempt).stream().anyMatch(m -> m.contains("changed since")));
        assertEquals(0, env.reloadCount().get());
    }
}
