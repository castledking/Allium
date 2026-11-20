package codes.castled.allium.glow;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.module.CreativeModule;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlowServiceTest {

    private final YamlConfiguration config = new YamlConfiguration();
    private final Set<String> grants = new HashSet<>();
    private final List<String> sent = new ArrayList<>();
    private Player steve;
    private GameMode mode = GameMode.SURVIVAL;
    private boolean glowing;
    private GlowService glow;

    @BeforeEach
    void setUp() {
        PluginStart plugin = mock(PluginStart.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(scheduler.repeatOnServer(any(), anyLong(), anyLong())).thenReturn(TaskHandle.NONE);
        ModuleManager modules = mock(ModuleManager.class);
        CreativeModule creative = mock(CreativeModule.class);
        when(creative.isEnabled()).thenReturn(true);
        when(modules.getModule("creative")).thenReturn(creative);
        when(plugin.getModuleManager()).thenReturn(modules);
        when(plugin.getName()).thenReturn("Allium");
        steve = mock(Player.class);
        when(steve.getUniqueId()).thenReturn(UUID.randomUUID());
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

    private static YamlConfiguration yaml(String text) {
        return YamlConfiguration.loadConfiguration(new java.io.StringReader(text));
    }
}
