package codes.castled.allium;

import codes.castled.allium.legacy.LegacyBukkitRuntime;
import codes.castled.allium.legacy.LegacyCompat;
import org.bukkit.BanList;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.DataInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
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
            when(p.canSee(any(Player.class))).thenReturn(true);
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
            return runAs(sender, cmdName, cmdName, args);
        }

        boolean runAs(Player sender, String cmdName, String alias, String... args) {
            Command cmd = mock(Command.class);
            when(cmd.getName()).thenReturn(cmdName);
            return runtime.onCommand(sender, cmd, alias, args);
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
    void legacyPaymentConfirmationPersistsAndMatchesTheRecipientAndAmount() {
        UUID aliceId = UUID.randomUUID();
        Harness first = createHarness();
        Player alice = first.addPlayer("Alice", aliceId, true);
        Player bob = first.addPlayer("Bob", UUID.randomUUID(), true);
        first.run(alice, "eco", "give", "Alice", "100");
        first.runAs(alice, "payconfirmtoggle", "payconfirmon");
        first.run(alice, "pay", "Bob", "25");
        verify(alice).sendMessage(contains("Confirm paying"));
        verify(bob, never()).sendMessage(contains("Received"));
        first.runtime().onDisable();

        Harness restarted = createHarness();
        Player payer = restarted.addPlayer("Alice", aliceId, true);
        Player recipient = restarted.addPlayer("Bob", bob.getUniqueId(), true);
        Player other = restarted.addPlayer("Steve", UUID.randomUUID(), true);
        restarted.run(payer, "pay", "Bob", "25");
        restarted.run(payer, "pay", "Bob", "26");
        restarted.run(payer, "pay", "Steve", "26");
        verify(payer, times(3)).sendMessage(contains("Confirm paying"));
        verify(recipient, never()).sendMessage(contains("Received"));
        verify(other, never()).sendMessage(contains("Received"));
        restarted.run(payer, "pay", "Steve", "26.00");
        verify(other).sendMessage(contains("$26.00"));
        restarted.runAs(payer, "payconfirmtoggle", "allium:payconfirmoff");
        restarted.run(payer, "pay", "Bob", "25");
        verify(recipient).sendMessage(contains("$25.00"));
        restarted.runtime().onDisable();
    }

    @Test
    void legacyPaymentDoesNotChangeEitherBalanceWhenStorageFails() throws Exception {
        Harness h = createHarness();
        Player alice = h.addPlayer("Alice", UUID.randomUUID(), true);
        Player bob = h.addPlayer("Bob", UUID.randomUUID(), true);
        h.run(alice, "eco", "give", "Alice", "100");
        Path data = tempDir.resolve("economy-legacy.yml");
        Path backup = Files.move(data, tempDir.resolve("economy.backup"));
        Files.createDirectory(data);
        try {
            h.run(alice, "pay", "Bob", "25");
            verify(alice).sendMessage(contains("storage is unavailable"));
            verify(bob, never()).sendMessage(contains("Received"));
            h.run(alice, "balance");
            h.run(bob, "balance");
            verify(alice).sendMessage(endsWith("$100.00"));
            verify(bob).sendMessage(endsWith("$0.00"));
        } finally {
            Files.delete(data);
            Files.move(backup, data);
            h.runtime().onDisable();
        }
    }

    @Test
    void compatibilitySettingsAndDirectTogglesSharePersistedState() {
        Harness first = createHarness();
        UUID targetId = UUID.randomUUID();
        Player target = first.addPlayer("Target", targetId, true);
        assertTrue(first.run(target, "toggle", "msg", "off"));
        assertTrue(first.run(target, "tptoggle", "off"));
        assertTrue(first.run(target, "toggle", "pay", "off"));
        first.runtime().onDisable();

        Harness restarted = createHarness();
        Player sameTarget = restarted.addPlayer("Target", targetId, true);
        Player other = restarted.addPlayer("Other", UUID.randomUUID(), false);
        when(other.hasPermission("allium.tpa")).thenReturn(true);
        when(other.hasPermission("allium.pay")).thenReturn(true);

        assertTrue(restarted.run(sameTarget, "toggles"));
        verify(sameTarget).sendMessage(contains("Private messages§c [Off]"));
        verify(sameTarget).sendMessage(contains("Teleport requests§c [Off]"));
        verify(sameTarget).sendMessage(contains("Payments§c [Off]"));
        assertTrue(restarted.run(other, "msg", "Target", "hello"));
        assertTrue(restarted.run(other, "tpa", "Target"));
        assertTrue(restarted.run(other, "pay", "Target", "1"));
        verify(other).sendMessage(contains("not accepting private messages"));
        verify(other).sendMessage(contains("not accepting teleport requests"));
        verify(other).sendMessage(contains("not accepting payments"));

        when(sameTarget.hasPermission("allium.toggle.msg")).thenReturn(false);
        assertTrue(restarted.run(sameTarget, "toggle", "msg", "on"));
        verify(sameTarget).sendMessage(contains("cannot change that setting"));
        assertTrue(restarted.run(sameTarget, "msgtoggle", "on"));
        verify(sameTarget).sendMessage(contains("Private messages enabled"));
        restarted.runtime().onDisable();
    }

    @Test
    void compatibilityTeleportMatchesVisiblePlayersAndRestrictsOtherPlayerTeleports() {
        Harness h = createHarness();
        Player scout = h.addPlayer("Scout", UUID.randomUUID(), false);
        Player nasty = h.addPlayer("MrNasty0627", UUID.randomUUID(), false);
        when(scout.hasPermission("allium.tp")).thenReturn(true);
        Command teleport = mock(Command.class);
        when(teleport.getName()).thenReturn("tp");
        when(teleport.testPermissionSilent(scout)).thenReturn(true);
        assertEquals(List.of("MrNasty0627"), h.runtime().onTabComplete(scout, teleport, "tp", new String[]{"nsaty"}));
        assertTrue(h.run(scout, "tp", "nasty"));
        assertTrue(h.run(scout, "tp", "nsaty"));
        verify(scout, times(2)).teleport(any(Location.class));

        assertTrue(h.run(scout, "tp", "nasty", "Scout"));
        verify(scout).sendMessage(contains("permission to teleport other players"));
        verify(nasty, never()).teleport(any(Location.class));

        when(scout.canSee(nasty)).thenReturn(false);
        assertTrue(h.runtime().onTabComplete(scout, teleport, "tp", new String[]{"nasty"}).isEmpty());
        assertTrue(h.run(scout, "tp", "nasty"));
        verify(scout).sendMessage(contains("not online"));
        verify(scout, times(2)).teleport(any(Location.class));
    }

    @Test
    void compatibilityReplyModePersistsAndUsesOnlyTheChosenDirection() {
        UUID id = UUID.randomUUID();
        Harness first = createHarness();
        Player player = first.addPlayer("Reader", id, true);
        assertTrue(first.run(player, "rtoggle"));
        verify(player).sendMessage(contains("last player you messaged"));
        first.runtime().onDisable();

        Harness restarted = createHarness();
        Player reader = restarted.addPlayer("Reader", id, true);
        Player incoming = restarted.addPlayer("Incoming", UUID.randomUUID(), true);
        Player outgoing = restarted.addPlayer("Outgoing", UUID.randomUUID(), true);
        assertTrue(restarted.run(incoming, "msg", "Reader", "first-message"));
        assertTrue(restarted.run(reader, "reply", "missing-recipient"));
        verify(reader).sendMessage(contains("No one to reply to"));
        verify(incoming, never()).sendMessage(contains("missing-recipient"));

        assertTrue(restarted.run(reader, "msg", "Outgoing", "outbound-message"));
        assertTrue(restarted.run(incoming, "msg", "Reader", "latest-message"));
        assertTrue(restarted.run(reader, "reply", "recipient-reply"));
        verify(outgoing).sendMessage(contains("recipient-reply"));
        verify(incoming, never()).sendMessage(contains("recipient-reply"));

        assertTrue(restarted.run(reader, "rtoggle"));
        verify(reader).sendMessage(contains("last player who messaged you"));
        assertTrue(restarted.run(reader, "reply", "sender-reply"));
        verify(incoming).sendMessage(contains("sender-reply"));
        verify(outgoing, never()).sendMessage(contains("sender-reply"));
        restarted.runtime().onDisable();
    }

    @Test
    void compatibilitySignEditingUsesClipboardAndRespectsProtectionEvents() {
        Harness h = createHarness();
        Player player = h.addPlayer("Builder", UUID.randomUUID(), true);
        Block block = mock(Block.class);
        Sign sign = mock(Sign.class);
        when(block.getState()).thenReturn(sign);
        when(sign.getLines()).thenReturn(new String[]{"first", "second", "", "last"});
        when(sign.update(false, false)).thenReturn(true);
        when(player.getTargetBlock(org.mockito.ArgumentMatchers.<Set<Material>>isNull(), eq(6))).thenReturn(block);

        assertTrue(h.run(player, "editsign", "copy"));
        verify(sign, never()).setLine(anyInt(), anyString());
        PluginManager manager = h.server().getPluginManager();
        doAnswer(inv -> {
            SignChangeEvent event = inv.getArgument(0);
            event.setLine(0, "plugin-modified");
            return null;
        }).when(manager).callEvent(any(SignChangeEvent.class));
        assertTrue(h.run(player, "editsign", "paste"));
        verify(sign).setLine(0, "plugin-modified");
        verify(sign).setLine(1, "second");
        verify(sign).update(false, false);

        doAnswer(inv -> {
            inv.<BlockBreakEvent>getArgument(0).setCancelled(true);
            return null;
        }).when(manager).callEvent(any(BlockBreakEvent.class));
        assertTrue(h.run(player, "editsign", "set", "1", "bypass"));
        verify(sign, never()).setLine(anyInt(), eq("bypass"));
        verify(sign, times(1)).update(false, false);
        verify(player).sendMessage(contains("cannot change that block"));
    }

    @Test
    void compatibilityUnlimitedOnlyRestoresTheItemConsumedByAnAllowedPlacement() {
        Harness h = createHarness();
        Player builder = h.addPlayer("Builder", UUID.randomUUID(), true);
        when(builder.getGameMode()).thenReturn(GameMode.SURVIVAL);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(h.server().getScheduler()).thenReturn(scheduler);
        ArgumentCaptor<org.bukkit.event.Listener> listeners = ArgumentCaptor.forClass(org.bukkit.event.Listener.class);
        verify(h.server().getPluginManager(), atLeastOnce()).registerEvents(listeners.capture(), eq(h.plugin()));
        org.bukkit.event.Listener unlimited = listeners.getAllValues().stream()
                .filter(listener -> listener.getClass().getSimpleName().equals("LegacyUnlimitedCommand")).findFirst().orElseThrow();
        assertTrue(h.run(builder, "unlimited", "stone"));

        BlockPlaceEvent placement = mock(BlockPlaceEvent.class);
        when(placement.getPlayer()).thenReturn(builder);
        when(placement.getHand()).thenReturn(EquipmentSlot.HAND);
        when(placement.canBuild()).thenReturn(true);
        ItemStack placed = mock(ItemStack.class);
        ItemStack restored = mock(ItemStack.class);
        ItemStack remainder = mock(ItemStack.class);
        when(placement.getItemInHand()).thenReturn(placed);
        when(placed.getType()).thenReturn(Material.STONE);
        when(placed.clone()).thenReturn(restored);
        when(restored.getAmount()).thenReturn(2);
        when(remainder.getAmount()).thenReturn(1);
        when(restored.isSimilar(remainder)).thenReturn(true);
        PlayerInventory inventory = builder.getInventory();
        when(inventory.getItem(0)).thenReturn(remainder);

        try {
            var listener = unlimited.getClass().getDeclaredMethod("onPlace", BlockPlaceEvent.class);
            listener.setAccessible(true);
            listener.invoke(unlimited, placement);
            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runTask(eq(h.plugin()), task.capture());
            when(placement.isCancelled()).thenReturn(true);
            task.getValue().run();
            verify(inventory, never()).setItem(eq(0), any(ItemStack.class));

            when(placement.isCancelled()).thenReturn(false);
            when(remainder.getAmount()).thenReturn(2);
            task.getValue().run();
            verify(inventory, never()).setItem(eq(0), any(ItemStack.class));

            when(remainder.getAmount()).thenReturn(1);
            task.getValue().run();
            verify(inventory).setItem(0, restored);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void compatibilityOfflineTeleportHidesVanishedLogoutPositions() throws Exception {
        Harness h = createHarness();
        Player staff = h.addPlayer("Staff", UUID.randomUUID(), false);
        when(staff.hasPermission("allium.reload")).thenReturn(true);
        when(staff.hasPermission("allium.tp.offline")).thenReturn(true);
        when(staff.hasPermission("allium.teleport.unsafe")).thenReturn(true);
        UUID worldId = h.world().getUID();
        when(h.server().getWorld(worldId)).thenReturn(h.world());
        java.nio.file.Files.writeString(tempDir.resolve("logout-locations-legacy.yml"),
                UUID.randomUUID() + ":\n  name: Hidden\n  hidden: true\n  world: " + worldId
                        + "\n  x: 12.5\n  y: 64.0\n  z: -7.5\n  yaw: 90.0\n");
        assertTrue(h.run(staff, "allium", "reload"));
        assertTrue(h.run(staff, "tpoffline", "Hidden"));
        verify(staff).sendMessage(contains("No saved offline location"));
        verify(staff, never()).teleport(any(Location.class));

        when(staff.hasPermission("allium.vanish.see")).thenReturn(true);
        when(staff.teleport(any(Location.class))).thenReturn(true);
        assertTrue(h.run(staff, "tpoffline", "hidden"));
        verify(staff).teleport(new Location(h.world(), 12.5, 64.0, -7.5, 90, 0));
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
