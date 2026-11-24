package codes.castled.allium.command;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.GamemodeCommand;
import codes.castled.allium.command.essentials.SpeedCommand;
import codes.castled.allium.command.essentials.WeatherCommand;
import codes.castled.allium.command.spec.CommandMessageKeys;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.economy.EconomyCommands;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies unified core/addon command governance and family-owned alias argument transforms.
 */
class CommandRegistrationCompatibilityTest {

    private static PluginStart mockPlugin(List<String> disabledCommands) {
        PluginStart plugin = mock(PluginStart.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("disabled-commands", disabledCommands);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(plugin.getMessageManager()).thenReturn(mock(MessageManager.class));
        Server server = mock(Server.class);
        when(server.getOnlinePlayers()).thenReturn(List.of());
        when(plugin.getServer()).thenReturn(server);
        return plugin;
    }

    private static CommandSpec addonSpec(String canonical, List<String> aliases, String permission) {
        return new CommandSpec(
                canonical,
                "addon",
                "addon",
                aliases,
                "Addon command " + canonical,
                "/" + canonical,
                "/" + canonical,
                permission,
                null,
                List.of(),
                List.of(),
                CommandMessageKeys.ofOutcomes(java.util.Map.of())
        );
    }

    @Test
    void addonCommandSharesPolicyDisabledCommandsAndLifecycleWithCoreCommands() {
        PluginStart plugin = mockPlugin(List.of("curioroll"));
        CommandManager manager = new CommandManager(plugin);
        Plugin addon = mock(Plugin.class);
        when(addon.getName()).thenReturn("Curios");

        CommandSpec disabledSpec = addonSpec("curios", List.of("curioroll"), "curios.use");
        PluginCommand disabledCmd = mock(PluginCommand.class);
        SpecCommandHandler disabledHandler = new StubHandler(disabledSpec);

        // Disabled via alias in disabled-commands before addon registers
        assertFalse(manager.registerAddonCommand(addon, disabledCmd, disabledHandler));
        assertTrue(manager.getFamilyPolicy().isDisabled("curios"));
        assertTrue(manager.getFamilyPolicy().isDisabled("curioroll"));
        assertNull(manager.getRenderedCommands().get("curios"));

        // Enabled addon command registers into shared renderedCommands & policy
        CommandSpec activeSpec = addonSpec("party", List.of("p"), "partylocator.use");
        PluginCommand activeCmd = mock(PluginCommand.class);
        AtomicReference<String[]> capturedArgs = new AtomicReference<>();
        SpecCommandHandler activeHandler = new SpecCommandHandler() {
            @Override
            public CommandSpec spec() {
                return activeSpec;
            }

            @Override
            public void execute(BukkitCommandContext context) {
                capturedArgs.set(context.args());
            }
        };

        assertTrue(manager.registerAddonCommand(addon, activeCmd, activeHandler));
        assertNotNull(manager.getRenderedCommands().get("party"));
        assertTrue(manager.getFamilyPolicy().findSpec("p").isPresent());
        verify(activeCmd).setExecutor(any(BukkitCommandRenderer.class));
        verify(activeCmd).setTabCompleter(any(BukkitCommandRenderer.class));

        // Dispatches normally when enabled, blocks immediately when disabled on reload
        BukkitCommandRenderer renderer = manager.getRenderedCommands().get("party");
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("partylocator.use")).thenReturn(true);
        Command bukkitCmd = mock(Command.class);

        assertTrue(renderer.onCommand(sender, bukkitCmd, "p", new String[]{"invite", "Steve"}));
        assertEquals(List.of("invite", "Steve"), List.of(capturedArgs.get()));

        manager.getFamilyPolicy().applyReload(List.of("p"));
        capturedArgs.set(null);
        assertTrue(renderer.onCommand(sender, bukkitCmd, "p", new String[]{"invite", "Alex"}));
        assertNull(capturedArgs.get(), "Newly disabled addon command must be blocked at dispatch");

        // Unregister cleans up shared inventory and policy
        manager.unregisterAddonCommand(activeSpec, activeCmd);
        assertNull(manager.getRenderedCommands().get("party"));
        assertTrue(manager.getFamilyPolicy().findSpec("party").isEmpty());
        assertTrue(manager.getFamilyPolicy().findSpec("p").isEmpty());
    }

    @Test
    void familyOwnedAliasTransformsRewriteExecutionAndCompletionArgs() {
        PluginStart plugin = mockPlugin(List.of());

        // GamemodeCommand: /gmc Steve -> ["creative", "Steve"], /gm 1 -> ["1"]
        GamemodeCommand gm = new GamemodeCommand(plugin);
        var gmExec = gm.transformExecutionArgs("gmc", new String[]{"Steve"});
        var gmPass = assertInstanceOf(SpecCommandHandler.AliasTransform.Pass.class, gmExec);
        assertEquals(List.of("creative", "Steve"), List.of(gmPass.args()));
        assertInstanceOf(SpecCommandHandler.AliasTransform.InvalidUsage.class,
                gm.transformExecutionArgs("gmc", new String[]{"Steve", "extra"}));
        assertInstanceOf(SpecCommandHandler.AliasTransform.NoCompletion.class,
                gm.transformCompletionArgs("gmt", new String[]{""}));
        var gmComp = assertInstanceOf(SpecCommandHandler.AliasTransform.Pass.class,
                gm.transformCompletionArgs("gms", new String[]{"Al"}));
        assertEquals(List.of("survival", "Al"), List.of(gmComp.args()));

        // SpeedCommand: /flyspeed 2 Steve -> ["fly", "2", "Steve"]
        SpeedCommand speed = new SpeedCommand(plugin);
        var flyPass = assertInstanceOf(SpecCommandHandler.AliasTransform.Pass.class,
                speed.transformExecutionArgs("flyspeed", new String[]{"2", "Steve"}));
        assertEquals(List.of("fly", "2", "Steve"), List.of(flyPass.args()));
        var walkComp = assertInstanceOf(SpecCommandHandler.AliasTransform.Pass.class,
                speed.transformCompletionArgs("walkspeed", new String[]{"3"}));
        assertEquals(List.of("walk", "3"), List.of(walkComp.args()));

        // WeatherCommand: /sun 60 -> ["sun", "60"], /rain -> ["storm"]
        WeatherCommand weather = new WeatherCommand(plugin);
        var sunPass = assertInstanceOf(SpecCommandHandler.AliasTransform.Pass.class,
                weather.transformExecutionArgs("sun", new String[]{"60"}));
        assertEquals(List.of("sun", "60"), List.of(sunPass.args()));
        var rainPass = assertInstanceOf(SpecCommandHandler.AliasTransform.Pass.class,
                weather.transformExecutionArgs("rain", new String[]{}));
        assertEquals(List.of("storm"), List.of(rainPass.args()));

        // EconomyCommands paytoggle: /payon -> ["on"], /payoff -> ["off"]
        CommandSpec payToggleSpec = CoreCommandSpecs.ECONOMY_SPECS.stream()
                .filter(s -> s.canonicalName().equals("paytoggle")).findFirst().orElseThrow();
        SpecCommandHandler payToggle = new EconomyCommands(payToggleSpec, null);
        var payOn = assertInstanceOf(SpecCommandHandler.AliasTransform.Pass.class,
                payToggle.transformExecutionArgs("payon", new String[]{}));
        assertEquals(List.of("on"), List.of(payOn.args()));
        var payOff = assertInstanceOf(SpecCommandHandler.AliasTransform.Pass.class,
                payToggle.transformExecutionArgs("payoff", new String[]{}));
        assertEquals(List.of("off"), List.of(payOff.args()));

        // EconomyCommands payconfirmtoggle: /payconfirmon -> ["on"], /payconfirmoff -> ["off"]
        CommandSpec payConfirmSpec = CoreCommandSpecs.ECONOMY_SPECS.stream()
                .filter(s -> s.canonicalName().equals("payconfirmtoggle")).findFirst().orElseThrow();
        SpecCommandHandler payConfirm = new EconomyCommands(payConfirmSpec, null);
        var confirmOn = assertInstanceOf(SpecCommandHandler.AliasTransform.Pass.class,
                payConfirm.transformExecutionArgs("payconfirmon", new String[]{}));
        assertEquals(List.of("on"), List.of(confirmOn.args()));
    }

    @Test
    void startupSpecInventoryIncludesCoreAndUtilityCommandsWithoutDuplicates() {
        List<CommandSpec> specs = CommandManager.allStartupSpecs();
        List<String> names = new ArrayList<>();
        for (CommandSpec spec : specs) {
            names.add(spec.canonicalName());
        }
        assertEquals(names.size(), names.stream().distinct().count(), "Duplicate canonical spec names in startup inventory");
        assertTrue(names.contains(CoreCommandSpecs.GAMEMODE.canonicalName()));
        assertTrue(names.contains(CoreCommandSpecs.GIVE.canonicalName()));
        assertTrue(names.contains("paytoggle"));
    }

    private record StubHandler(CommandSpec spec) implements SpecCommandHandler {
        @Override
        public void execute(BukkitCommandContext context) {
        }
    }
}
