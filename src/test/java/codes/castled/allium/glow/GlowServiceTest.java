package codes.castled.allium.glow;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.essentials.GlowCommand;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.CreativeModule;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlowServiceTest {

    private final YamlConfiguration config = new YamlConfiguration();
    private final Set<String> grants = new HashSet<>();
    private final List<String> sent = new ArrayList<>();
    private PluginStart plugin;
    private Server server;
    private TaskScheduler scheduler;
    private MessageManager messages;
    private Player steve;
    private GameMode mode = GameMode.SURVIVAL;
    private boolean glowing;
    private GlowService glow;

    @BeforeEach
    void setUp() {
        plugin = mock(PluginStart.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(plugin.isEnabled()).thenReturn(true);
        server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        messages = mock(MessageManager.class);
        when(plugin.getMessageManager()).thenReturn(messages);
        scheduler = mock(TaskScheduler.class);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(scheduler.repeatOnServer(any(), anyLong(), anyLong())).thenReturn(TaskHandle.NONE);
        when(scheduler.runOn(any(), any(), any(), anyLong())).thenReturn(mock(TaskHandle.class));
        ModuleManager modules = mock(ModuleManager.class);
        CreativeModule creative = mock(CreativeModule.class);
        when(creative.isEnabled()).thenReturn(true);
        when(modules.getModule("creative")).thenReturn(creative);
        when(plugin.getModuleManager()).thenReturn(modules);
        when(plugin.getName()).thenReturn("Allium");
        steve = mock(Player.class);
        UUID steveId = UUID.randomUUID();
        when(steve.getUniqueId()).thenReturn(steveId);
        when(steve.getName()).thenReturn("Steve");
        when(steve.isOnline()).thenReturn(true);
        when(steve.getGameMode()).thenAnswer(i -> mode);
        when(steve.isGlowing()).thenAnswer(i -> glowing);
        org.mockito.Mockito.doAnswer(i -> glowing = i.getArgument(0)).when(steve).setGlowing(org.mockito.ArgumentMatchers.anyBoolean());
        when(steve.hasPermission(anyString())).thenAnswer(i -> grants.contains(i.<String>getArgument(0)));
        when(steve.isPermissionSet(anyString())).thenAnswer(i -> grants.contains(i.<String>getArgument(0)));
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(data.getOrDefault(any(), any(), any())).thenReturn((byte) 0);
        when(steve.getPersistentDataContainer()).thenReturn(data);
        when(server.getPlayer(steveId)).thenReturn(steve);
        when(server.getPlayerExact("Steve")).thenReturn(steve);
        org.mockito.Mockito.doReturn(List.of(steve)).when(server).getOnlinePlayers();
        GlowService.Teams teams = new GlowService.Teams() {
            public void show(Player target, String color, Iterable<? extends Player> viewers) { sent.add("show " + color); }
            public void introduce(Player target, String color, Player viewer) { sent.add("introduce " + color); }
            public void hide(Player target, Iterable<? extends Player> viewers) { sent.add("hide"); }
        };
        glow = new GlowService(plugin, teams);
    }

    @Test
    void aChoiceShowsAndOffHidesIt() {
        glow.choose(steve, "red");
        assertTrue(glowing);
        assertEquals(List.of("show red"), sent);
        glow.choose(steve, null);
        assertFalse(glowing);
        assertEquals("hide", sent.get(sent.size() - 1));
    }

    @Test
    void restrictedCreativeGlowCoversTheChoiceUnlessOwnGlow() {
        config.set("creative-manager.glow.enabled", true);
        glow.reload();
        glow.choose(steve, "red");
        mode = GameMode.CREATIVE;
        glow.evaluate(steve);
        assertEquals("light_purple", glow.desired(steve), "restricted creative shows the creative glow");
        assertEquals("show light_purple", sent.get(sent.size() - 1));
        grants.add(GlowService.PERMISSION_OWN_GLOW);
        assertEquals("red", glow.desired(steve), "own-glow keeps the player's glow");
        grants.remove(GlowService.PERMISSION_OWN_GLOW);
        grants.add("allium.gamemode.creative.bypass");
        assertEquals("red", glow.desired(steve), "unrestricted creative is not covered");
        grants.remove("allium.gamemode.creative.bypass");
        mode = GameMode.SURVIVAL;
        assertEquals("red", glow.desired(steve), "leaving creative brings the choice back");
    }

    @Test
    void spectatorGlowFollowsExplicitColourNodes() {
        mode = GameMode.SPECTATOR;
        assertNull(glow.desired(steve));
        grants.add(GlowService.PERMISSION_SPECTATOR);
        assertEquals("white", glow.desired(steve));
        grants.add(GlowService.PERMISSION_SPECTATOR + ".aqua");
        assertEquals("aqua", glow.desired(steve));
        glow.choose(steve, "gold");
        assertEquals("gold", glow.desired(steve), "a /glow choice wins over the spectator glow");
    }

    @Test
    void namesAndSettings() {
        assertEquals("&dlight purple", GlowService.display("light_purple"));
        assertTrue(GlowService.display("rainbow").startsWith("&cr"));
        assertEquals(16, GlowService.COLORS.size());
        assertEquals("rainbow", GlowService.Settings.read(yaml("color: rainbow")).restrictedColor());
        assertEquals("light_purple", GlowService.Settings.read(yaml("color: nonsense")).restrictedColor());
        assertFalse(GlowService.Settings.read(null).restrictedEnabled());
    }

    @Test
    void foliaTickGameModeAndCloseRouteEntityMutationsToPlayerRegion() {
        ServerThread steveOwner = mock(ServerThread.class);
        when(steveOwner.isServerThread()).thenReturn(false);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, steve)).thenReturn(steveOwner);

            // Rainbow choice + periodic tick: rainbow packets advance without touching setGlowing off-region,
            // and the 10th tick schedules re-evaluation onto steveOwner instead of running on the global thread.
            when(steveOwner.isServerThread()).thenReturn(true);
            glow.choose(steve, GlowService.RAINBOW);
            assertTrue(glowing);

            when(steveOwner.isServerThread()).thenReturn(false);
            mode = GameMode.SPECTATOR;
            grants.add(GlowService.PERMISSION_SPECTATOR);
            glowing = false; // pretend flag drifted and needs re-evaluation on steve's region
            sent.clear();

            for (int i = 0; i < 10; i++) {
                glow.tick();
            }
            assertEquals(10, sent.size(), "rainbow packet advances every tick");
            assertFalse(glowing, "global tick must not call setGlowing off-region");
            var evalTask = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runOn(eq(steveOwner), evalTask.capture(), any(), eq(0L));

            when(steveOwner.isServerThread()).thenReturn(true);
            evalTask.getValue().run();
            assertTrue(glowing);

            // PlayerGameModeChangeEvent must schedule on steveOwner, never runOnServer.
            var modeEvent = mock(PlayerGameModeChangeEvent.class);
            when(modeEvent.getPlayer()).thenReturn(steve);
            glow.onGameMode(modeEvent);
            verify(scheduler, never()).runOnServer(any());

            // Live close on Folia hides packets and schedules setGlowing(false) on steveOwner.
            when(steveOwner.isServerThread()).thenReturn(false);
            glow.close();
            assertTrue(glowing, "close must not call setGlowing(false) off-region while enabled");
            var closeTask = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler, org.mockito.Mockito.atLeast(3)).runOn(eq(steveOwner), closeTask.capture(), any(), eq(0L));
            when(steveOwner.isServerThread()).thenReturn(true);
            closeTask.getValue().run();
            assertFalse(glowing);
        }
    }

    @Test
    void foliaGlowCommandForOtherRunsOnTargetRegionAndRepliesToSenderRegion() {
        Player staff = mock(Player.class);
        UUID staffId = UUID.randomUUID();
        when(staff.getUniqueId()).thenReturn(staffId);
        when(staff.getName()).thenReturn("Staff");
        when(staff.isOnline()).thenReturn(true);
        when(staff.hasPermission(anyString())).thenReturn(true);
        when(server.getPlayer(staffId)).thenReturn(staff);

        ServerThread staffOwner = mock(ServerThread.class);
        ServerThread steveOwner = mock(ServerThread.class);
        when(staffOwner.isServerThread()).thenReturn(true);
        when(steveOwner.isServerThread()).thenReturn(false);

        GlowCommand command = new GlowCommand(plugin, () -> glow);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, staff)).thenReturn(staffOwner);
            platform.when(() -> BukkitServerThread.entity(plugin, steve)).thenReturn(steveOwner);

            var targetTask = ArgumentCaptor.forClass(Runnable.class);
            command.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.GLOW, staff, "glow", new String[]{"aqua", "Steve"}));

            assertFalse(glowing, "sender's region must not mutate target's glow directly");
            verify(scheduler).runOn(eq(steveOwner), targetTask.capture(), any(), eq(0L));

            when(staffOwner.isServerThread()).thenReturn(false);
            when(steveOwner.isServerThread()).thenReturn(true);
            var senderReply = ArgumentCaptor.forClass(Runnable.class);
            targetTask.getValue().run();

            assertTrue(glowing);
            assertEquals("aqua", glow.choice(steve));
            verify(messages).send(eq(steve), eq(Messages.GLOW_SET), eq("{color}"), eq("&baqua"));
            verify(scheduler).runOn(eq(staffOwner), senderReply.capture(), any(), eq(0L));

            when(staffOwner.isServerThread()).thenReturn(true);
            senderReply.getValue().run();
            verify(messages).send(eq(staff), eq(Messages.GLOW_SET_OTHER), eq("{color}"), eq("&baqua"), eq("{player}"), eq("Steve"));
        }
    }

    private static YamlConfiguration yaml(String text) {
        return YamlConfiguration.loadConfiguration(new java.io.StringReader(text));
    }
}
