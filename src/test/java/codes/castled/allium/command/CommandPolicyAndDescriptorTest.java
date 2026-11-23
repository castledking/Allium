package codes.castled.allium.command;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.items.GiveCommand;
import codes.castled.allium.command.spec.CommandFamilyPolicy;
import codes.castled.allium.command.spec.CommandMessageKeys;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.item.delivery.ItemDeliveryService;
import codes.castled.allium.item.delivery.OverflowPolicy;
import codes.castled.allium.item.library.ItemLibrary;
import codes.castled.allium.item.spec.ItemSpecParser;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CommandPolicyAndDescriptorTest {

    @Test
    void everyCommandAndAliasInPluginYmlIsKnownToStartupPolicy() {
        YamlConfiguration pluginYml = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/plugin.yml").toFile());
        ConfigurationSection commands = pluginYml.getConfigurationSection("commands");
        assertNotNull(commands, "plugin.yml must declare commands");

        YamlConfiguration config = new YamlConfiguration();
        config.set("disabled-commands", List.of("claimrank", "ar", "voterestart", "clearchat"));
        AlliumLogger logger = mock(AlliumLogger.class);
        PluginStart plugin = mockPlugin(config, logger, List.of());

        CommandManager manager = new CommandManager(plugin);
        CommandFamilyPolicy policy = manager.getFamilyPolicy();

        // None of the startup aliases/roots should produce unknown-command warnings.
        verify(logger, never()).warning(contains("Unknown command entry"));
        assertTrue(policy.isDisabled("redeem"));
        assertTrue(policy.isDisabled("claimrank"));
        assertTrue(policy.isDisabled("autorestart"));
        assertTrue(policy.isDisabled("ar"));
        assertTrue(policy.isDisabled("voterestart"));
        assertTrue(policy.isDisabled("clearchat"));

        List<String> missingRoots = new ArrayList<>();
        List<String> missingAliases = new ArrayList<>();
        for (String root : commands.getKeys(false)) {
            String canonical = root.toLowerCase(Locale.ROOT);
            if (policy.resolveCanonicalFamily(canonical).isEmpty()) {
                missingRoots.add(root);
                continue;
            }
            ConfigurationSection section = commands.getConfigurationSection(root);
            if (section == null) continue;
            Object aliasesRaw = section.get("aliases");
            List<String> aliases = aliasesRaw instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : aliasesRaw instanceof String s ? List.of(s) : List.of();
            for (String alias : aliases) {
                var resolved = policy.resolveCanonicalFamily(alias);
                if (resolved.isEmpty() || !resolved.get().equals(canonical)) {
                    missingAliases.add(root + " -> alias '" + alias + "' (resolved=" + resolved + ")");
                }
            }
        }
        assertEquals(List.of(), missingRoots, "Commands in plugin.yml missing from CommandManager startup policy");
        assertEquals(List.of(), missingAliases, "Aliases in plugin.yml missing or mismatched in CommandManager startup policy");
    }

    @Test
    void disablingGiveOnReloadBlocksBukkitPaperDirectExecutionSuggestionsHelpAndKeepsVanillaIndependent() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("disabled-commands", List.of());
        AlliumLogger logger = mock(AlliumLogger.class);
        Player onlinePlayer = mock(Player.class);
        when(onlinePlayer.isOnline()).thenReturn(true);
        PluginStart plugin = mockPlugin(config, logger, List.of(onlinePlayer));

        CommandManager manager = new CommandManager(plugin);
        when(plugin.getCommandManager()).thenReturn(manager);
        MessageManager messages = plugin.getMessageManager();

        ItemLibrary library = mock(ItemLibrary.class);
        ItemDeliveryService delivery = mock(ItemDeliveryService.class);
        GiveCommand give = new GiveCommand(plugin, () -> new ItemSpecParser(false), library, delivery, () -> OverflowPolicy.DROP);
        BukkitCommandRenderer renderer = new BukkitCommandRenderer(plugin, manager.getFamilyPolicy(), give);

        CommandSender admin = mock(CommandSender.class);
        when(admin.hasPermission(anyString())).thenReturn(true);
        Command dummyCmd = new Command("give") {
            @Override public boolean execute(@NotNull CommandSender s, @NotNull String l, @NotNull String[] a) { return true; }
        };

        // Initially enabled across both Bukkit and Paper admission paths.
        assertTrue(give.canUse(admin));
        assertEquals(CommandFamilyPolicy.Admission.ALLOWED, give.admission(admin, "give"));
        assertEquals(CommandFamilyPolicy.Admission.ALLOWED, give.admission(admin, "allium:give"));

        // Disable 'give' and reload live.
        config.set("disabled-commands", List.of("give"));
        var eval = manager.reload();
        assertTrue(eval.activeDisabledFamilies().contains("give"));
        assertTrue(eval.newlyDisabledRequiringRestartToYield().contains("give"));
        verify(onlinePlayer).updateCommands();

        // Paper Brigadier admission & direct GiveCommand.give() are immediately blocked.
        assertFalse(give.canUse(admin));
        assertFalse(give.admitExecution(admin, "give"));
        give.give(admin, List.of(onlinePlayer), "diamond 1");
        verifyNoInteractions(library, delivery);

        // Bukkit renderer dispatch & tab-completion are blocked for both bare and allium: labels.
        assertTrue(renderer.onCommand(admin, dummyCmd, "give", new String[]{"Steve", "diamond"}));
        assertTrue(renderer.onCommand(admin, dummyCmd, "allium:give", new String[]{"Steve", "diamond"}));
        assertEquals(List.of(), renderer.onTabComplete(admin, dummyCmd, "give", new String[]{"Steve", "dia"}));
        assertEquals(List.of(), renderer.onTabComplete(admin, dummyCmd, "allium:give", new String[]{"Steve", "dia"}));
        verify(messages, atLeast(3)).sendByKey(eq(admin), eq(CoreCommandSpecs.GIVE.messageKeys().moduleDisabledKey()));

        // Explicit vanilla namespace is NOT disabled by Allium's policy.
        assertFalse(manager.getFamilyPolicy().isDisabled("minecraft:give"));

        // HelpCommand hides Allium's disabled /give while keeping non-Allium commands visible.
        Server bareServer = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (p, m, a) -> null
        );
        SimpleCommandMap commandMap = new SimpleCommandMap(bareServer);
        Plugin alliumPlugin = dummyPlugin("Allium");
        Plugin otherPlugin = dummyPlugin("Other");
        commandMap.register("allium", new OwnedCommand("give", alliumPlugin));
        commandMap.register("other", new OwnedCommand("othercmd", otherPlugin));
        ServerWithCommandMap serverWithMap = (ServerWithCommandMap) Proxy.newProxyInstance(
                ServerWithCommandMap.class.getClassLoader(),
                new Class<?>[]{ServerWithCommandMap.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getCommandMap" -> commandMap;
                    case "getOnlinePlayers" -> List.of(onlinePlayer);
                    default -> null;
                }
        );
        when(plugin.getServer()).thenReturn(serverWithMap);
        when(plugin.getName()).thenReturn("Allium");

        HelpCommand help = new HelpCommand(plugin);
        List<String> visiblePaths = help.entries(admin).stream().map(e -> e.path().toLowerCase(Locale.ROOT)).toList();
        assertFalse(visiblePaths.contains("give"), "disabled Allium command must be hidden from /help");
        assertTrue(visiblePaths.contains("othercmd"), "other plugin commands must remain visible in /help");

        // Re-enabling on reload restores admission immediately.
        config.set("disabled-commands", List.of());
        manager.reload();
        assertTrue(give.canUse(admin));
        assertTrue(give.admitExecution(admin, "give"));
    }

    @Test
    void lateRegisteredSpecStillHonorsStartupDisabledAlias() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("disabled-commands", List.of("latealias"));
        PluginStart plugin = mockPlugin(config, mock(AlliumLogger.class), List.of());
        CommandManager manager = new CommandManager(plugin);

        CommandSpec lateSpec = new CommandSpec("latecmd", CommandSpec.DEFAULT_NAMESPACE, "test", List.of("latealias"),
                "Late command", "/latecmd", "/latecmd", "allium.late", null,
                List.of(), List.of(), CommandMessageKeys.ofOutcomes(Map.of()));
        AtomicBoolean executed = new AtomicBoolean();
        manager.register(new SpecCommandHandler() {
            @Override public CommandSpec spec() { return lateSpec; }
            @Override public void execute(BukkitCommandContext context) { executed.set(true); }
        });

        assertTrue(manager.getFamilyPolicy().isDisabled("latecmd"));
        assertTrue(manager.getFamilyPolicy().isDisabled("latealias"));
        assertFalse(manager.getRenderedCommands().containsKey("latecmd"));
        assertFalse(executed.get());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static PluginStart mockPlugin(YamlConfiguration config, AlliumLogger logger, List<Player> online) {
        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        MessageManager messages = mock(MessageManager.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.logger()).thenReturn(logger);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(server.getOnlinePlayers()).thenReturn((Collection) online);
        return plugin;
    }

    private static Plugin dummyPlugin(String name) {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                }
        );
    }

    private interface ServerWithCommandMap extends Server {
        org.bukkit.command.CommandMap getCommandMap();
    }

    private static final class OwnedCommand extends Command implements PluginIdentifiableCommand {
        private final Plugin owner;

        OwnedCommand(String name, Plugin owner) {
            super(name);
            this.owner = owner;
        }

        @Override
        public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
            return true;
        }

        @Override
        public @NotNull Plugin getPlugin() {
            return owner;
        }
    }
}
