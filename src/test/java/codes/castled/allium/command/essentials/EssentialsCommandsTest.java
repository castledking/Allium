package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandRenderer;
import codes.castled.allium.command.spec.CommandFamilyPolicy;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.compat.GodModeCompatProvider;
import codes.castled.allium.message.LanguageManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class EssentialsCommandsTest {

    private PluginStart plugin;
    private Server server;
    private MessageManager messageManager;
    private CommandFamilyPolicy policy;
    private Command command;
    private Player self;
    private Player other;
    private ConsoleCommandSender console;

    @BeforeEach
    void setUp() {
        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("Allium");
        PluginDescriptionFile desc = new PluginDescriptionFile("Allium", "1.0.0-SNAPSHOT", "codes.castled.allium.PluginStart");
        when(plugin.getDescription()).thenReturn(desc);

        LanguageManager langManager = mock(LanguageManager.class);
        for (Messages m : Messages.values()) {
            when(langManager.get(m)).thenReturn(m.getDefault());
        }
        messageManager = new MessageManager(plugin, langManager);
        when(plugin.getMessageManager()).thenReturn(messageManager);

        policy = new CommandFamilyPolicy();
        policy.registerSpecs(CoreCommandSpecs.ESSENTIALS_PROOF_SPECS);
        policy.initializeStartup(List.of());

        GodModeCompatProvider.init(plugin);

        command = mock(Command.class);
        self = mock(Player.class);
        other = mock(Player.class);
        console = mock(ConsoleCommandSender.class);

        when(self.getName()).thenReturn("Alice");
        when(other.getName()).thenReturn("Bob");
        when(server.getPlayer("Bob")).thenReturn(other);
        when(self.getActivePotionEffects()).thenReturn(Collections.emptyList());
        when(other.getActivePotionEffects()).thenReturn(Collections.emptyList());

        PersistentDataContainer selfPdc = mock(PersistentDataContainer.class);
        PersistentDataContainer otherPdc = mock(PersistentDataContainer.class);
        when(self.getPersistentDataContainer()).thenReturn(selfPdc);
        when(other.getPersistentDataContainer()).thenReturn(otherPdc);
        when(selfPdc.getOrDefault(any(NamespacedKey.class), eq(PersistentDataType.INTEGER), eq(0))).thenReturn(0);
        when(otherPdc.getOrDefault(any(NamespacedKey.class), eq(PersistentDataType.INTEGER), eq(0))).thenReturn(0);
    }

    @Test
    void healSelfOtherAndConsole() {
        BukkitCommandRenderer heal = new BukkitCommandRenderer(plugin, policy, new HealCommand(plugin));
        when(self.hasPermission("allium.heal")).thenReturn(true);
        when(self.hasPermission("allium.heal.others")).thenReturn(true);
        when(console.hasPermission("allium.heal")).thenReturn(true);
        when(console.hasPermission("allium.heal.others")).thenReturn(true);

        heal.onCommand(self, command, "heal", new String[0]);
        verify(self).setHealth(20.0);
        verify(self).setFoodLevel(20);

        heal.onCommand(self, command, "heal", new String[]{"Bob"});
        verify(other).setHealth(20.0);

        heal.onCommand(console, command, "heal", new String[]{"Bob"});
        verify(other, times(2)).setHealth(20.0);

        heal.onCommand(console, command, "heal", new String[0]);
        verify(console).sendMessage(org.mockito.ArgumentMatchers.<String>argThat(m -> org.bukkit.ChatColor.stripColor(m).contains("/heal <player>")));
    }

    @Test
    void feedSelfOtherAndConsole() {
        BukkitCommandRenderer feed = new BukkitCommandRenderer(plugin, policy, new FeedCommand(plugin));
        when(self.hasPermission("allium.feed")).thenReturn(true);
        when(self.hasPermission("allium.feed.others")).thenReturn(true);
        when(console.hasPermission("allium.feed")).thenReturn(true);
        when(console.hasPermission("allium.feed.others")).thenReturn(true);

        feed.onCommand(self, command, "feed", new String[0]);
        verify(self).setFoodLevel(20);

        feed.onCommand(self, command, "feed", new String[]{"Bob"});
        verify(other).setFoodLevel(20);

        feed.onCommand(console, command, "feed", new String[]{"Bob"});
        verify(other, times(2)).setFoodLevel(20);

        feed.onCommand(console, command, "feed", new String[0]);
        verify(console).sendMessage(org.mockito.ArgumentMatchers.<String>argThat(m -> org.bukkit.ChatColor.stripColor(m).contains("/feed <player>")));
    }

    @Test
    void flySelfOtherAndConsole() {
        BukkitCommandRenderer fly = new BukkitCommandRenderer(plugin, policy, new FlyCommand(plugin));
        // On a server allium.fly grants its child allium.tfly, the command's base node.
        for (String node : new String[]{"allium.fly", "allium.tfly", "allium.fly.others"}) {
            when(self.hasPermission(node)).thenReturn(true);
            when(console.hasPermission(node)).thenReturn(true);
        }

        fly.onCommand(self, command, "fly", new String[0]);
        verify(self).setAllowFlight(true);
        verify(self).setFlying(true);

        fly.onCommand(self, command, "fly", new String[]{"Bob"});
        verify(other).setAllowFlight(true);

        fly.onCommand(console, command, "fly", new String[]{"Bob"});
        verify(other, times(2)).setAllowFlight(true);

        fly.onCommand(console, command, "fly", new String[0]);
        verify(console).sendMessage(org.mockito.ArgumentMatchers.<String>argThat(m -> org.bukkit.ChatColor.stripColor(m).contains("/fly <player>")));
    }

    @Test
    void godSelfOtherAndConsole() {
        BukkitCommandRenderer god = new BukkitCommandRenderer(plugin, policy, new GodCommand(plugin));
        when(self.hasPermission("allium.god")).thenReturn(true);
        when(self.hasPermission("allium.god.others")).thenReturn(true);
        when(console.hasPermission("allium.god")).thenReturn(true);
        when(console.hasPermission("allium.god.others")).thenReturn(true);

        god.onCommand(self, command, "god", new String[0]);
        verify(self).setInvulnerable(true);

        god.onCommand(self, command, "god", new String[]{"Bob"});
        verify(other).setInvulnerable(true);

        god.onCommand(console, command, "god", new String[]{"Bob"});
        verify(other, times(2)).setInvulnerable(true);

        god.onCommand(console, command, "god", new String[0]);
        verify(console).sendMessage(org.mockito.ArgumentMatchers.<String>argThat(m -> org.bukkit.ChatColor.stripColor(m).contains("/god <player>")));
    }

    @Test
    void gamemodeSelfOtherAndConsole() {
        BukkitCommandRenderer gm = new BukkitCommandRenderer(plugin, policy, new GamemodeCommand(plugin));
        when(self.hasPermission("allium.gamemode")).thenReturn(true);
        when(self.hasPermission("allium.gamemode.others")).thenReturn(true);
        when(console.hasPermission("allium.gamemode")).thenReturn(true);
        when(console.hasPermission("allium.gamemode.others")).thenReturn(true);
        for (String mode : List.of("survival", "creative", "adventure", "spectator")) {
            when(self.hasPermission("allium.gamemode." + mode)).thenReturn(true);
            when(console.hasPermission("allium.gamemode." + mode)).thenReturn(true);
        }

        gm.onCommand(self, command, "gamemode", new String[]{"creative"});
        verify(self).setGameMode(GameMode.CREATIVE);

        gm.onCommand(self, command, "gamemode", new String[]{"spectator", "Bob"});
        verify(other).setGameMode(GameMode.SPECTATOR);

        gm.onCommand(console, command, "gamemode", new String[]{"adventure", "Bob"});
        verify(other).setGameMode(GameMode.ADVENTURE);

        gm.onCommand(console, command, "gamemode", new String[]{"survival"});
        verify(console).sendMessage(org.mockito.ArgumentMatchers.<String>argThat(m -> org.bukkit.ChatColor.stripColor(m).contains("/gamemode <mode> <player>")));
    }

    @Test
    void reloadDisablingBlocksExecutionAndTabCompletionAcrossAliases() {
        BukkitCommandRenderer gm = new BukkitCommandRenderer(plugin, policy, new GamemodeCommand(plugin));
        when(self.hasPermission("allium.gamemode")).thenReturn(true);

        policy.applyReload(List.of("gm"));

        gm.onCommand(self, command, "gm", new String[]{"creative"});
        verify(self, never()).setGameMode(any());
        assertEquals(List.of(), gm.onTabComplete(self, command, "gm", new String[]{"c"}));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"gmc,CREATIVE","creative,CREATIVE","gms,SURVIVAL","survival,SURVIVAL","gma,ADVENTURE","adventure,ADVENTURE","gmsp,SPECTATOR","spectator,SPECTATOR"})
    void fixedAliasesSharePermissionsAndTargetResolution(String alias,GameMode mode){
        var renderer=new BukkitCommandRenderer(plugin,policy,new GamemodeCommand(plugin));
        renderer.onCommand(self,command,alias,new String[0]);verify(self,never()).setGameMode(any());
        when(self.hasPermission("allium.gamemode")).thenReturn(true);
        renderer.onCommand(self,command,alias,new String[0]);verify(self,never()).setGameMode(any());
        when(self.hasPermission("allium.gamemode."+mode.name().toLowerCase(java.util.Locale.ROOT))).thenReturn(true);
        renderer.onCommand(self,command,alias,new String[0]);verify(self).setGameMode(mode);
        renderer.onCommand(self,command,alias,new String[]{"Bob"});verify(other,never()).setGameMode(any());
        when(self.hasPermission("allium.gamemode.others")).thenReturn(true);
        renderer.onCommand(self,command,"allium:"+alias,new String[]{"Bob"});verify(other).setGameMode(mode);
        policy.applyReload(java.util.List.of("gamemode"));
        renderer.onCommand(self,command,alias,new String[0]);verify(self,times(1)).setGameMode(mode);
    }


    @Test
    void eachModeNeedsItsOwnNodeAndCompletionOffersOnlyThoseModes() {
        BukkitCommandRenderer gm = new BukkitCommandRenderer(plugin, policy, new GamemodeCommand(plugin));
        when(self.hasPermission("allium.gamemode")).thenReturn(true);
        when(self.hasPermission("allium.gamemode.others")).thenReturn(true);
        when(self.hasPermission("allium.gamemode.survival")).thenReturn(true);
        when(self.hasPermission("allium.gamemode.spectator")).thenReturn(true);

        gm.onCommand(self, command, "gamemode", new String[]{"creative"});
        gm.onCommand(self, command, "gamemode", new String[]{"creative", "Bob"});
        verify(self, never()).setGameMode(any());
        verify(other, never()).setGameMode(any());
        gm.onCommand(self, command, "gamemode", new String[]{"spectator", "Bob"});
        verify(other).setGameMode(GameMode.SPECTATOR);
        assertEquals(List.of("survival", "spectator"), gm.onTabComplete(self, command, "gamemode", new String[]{""}));
    }
}
