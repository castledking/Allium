package codes.castled.allium.command;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.spec.CommandFamilyPolicy;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CommandManager {

    private final PluginStart plugin;
    private final CommandFamilyPolicy familyPolicy = new CommandFamilyPolicy();
    private final Map<String, CommandExecutor> commands = new LinkedHashMap<>();
    private final Map<String, BukkitCommandRenderer> renderedCommands = new LinkedHashMap<>();

    public CommandManager(PluginStart plugin) {
        this.plugin = plugin;
        this.familyPolicy.registerSpecs(CoreCommandSpecs.ESSENTIALS_PROOF_SPECS);
        this.familyPolicy.registerSpecs(CoreCommandSpecs.ITEM_SPECS);
        this.familyPolicy.registerSpecs(CoreCommandSpecs.MESSAGING_SPECS);
        this.familyPolicy.registerSpecs(CoreCommandSpecs.MAIL_SPECS);
        this.familyPolicy.registerSpecs(CoreCommandSpecs.CORE_SPECS);
        initializePolicyFromConfig();
    }

    private void initializePolicyFromConfig() {
        List<String> rawDisabled = plugin.getConfig() != null
                ? plugin.getConfig().getStringList("disabled-commands")
                : List.of();
        CommandFamilyPolicy.PolicyEvaluation evaluation = familyPolicy.initializeStartup(rawDisabled);
        logUnknownDisabledEntries(evaluation.unknownEntries());
    }

    /**
     * Registers a command backed by the platform-neutral {@link CommandSpec}.
     */
    public void register(SpecCommandHandler handler) {
        CommandSpec spec = handler.spec();
        familyPolicy.registerSpec(spec);

        if (familyPolicy.isDisabled(spec.canonicalName())) {
            boolean yielded = CommandMapBridge.yieldCommandFamily(plugin.getServer(), plugin, spec);
            plugin.logger().info("Command family '" + spec.canonicalName()
                    + "' is disabled via disabled-commands; yielded labels " + spec.allOwnedLabels()
                    + (yielded ? " from CommandMap." : "."));
            return;
        }

        BukkitCommandRenderer renderer = new BukkitCommandRenderer(plugin, familyPolicy, handler);
        renderedCommands.put(spec.canonicalName(), renderer);
        register(spec.canonicalName(), renderer);
    }

    public void register(String name, CommandExecutor executor) {
        if (familyPolicy.isDisabled(name)) {
            familyPolicy.findSpec(name).ifPresent(spec ->
                    CommandMapBridge.yieldCommandFamily(plugin.getServer(), plugin, spec));
            return;
        }
        commands.put(name, executor);
        PluginCommand cmd = plugin.getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(executor);
            if (executor instanceof TabCompleter completer) {
                cmd.setTabCompleter(completer);
            }
        } else {
            plugin.logger().warning("Command '" + name + "' is not defined in plugin.yml.");
        }
    }

    public void registerAll() {
        // Yield any remaining known command families that were disabled in config
        for (CommandSpec spec : familyPolicy.registeredSpecs().values()) {
            if (familyPolicy.isDisabled(spec.canonicalName()) && !renderedCommands.containsKey(spec.canonicalName())) {
                CommandMapBridge.yieldCommandFamily(plugin.getServer(), plugin, spec);
            }
        }
    }

    /**
     * Re-evaluates {@code disabled-commands} on configuration reload.
     * Immediately blocks newly disabled families at dispatch time and logs clear diagnostics
     * when a server restart is required to yield or reclaim Bukkit {@code CommandMap} labels.
     */
    public CommandFamilyPolicy.PolicyEvaluation reload() {
        List<String> rawDisabled = plugin.getConfig() != null
                ? plugin.getConfig().getStringList("disabled-commands")
                : List.of();
        CommandFamilyPolicy.PolicyEvaluation eval = familyPolicy.applyReload(rawDisabled);
        logUnknownDisabledEntries(eval.unknownEntries());

        if (!eval.newlyDisabledRequiringRestartToYield().isEmpty()) {
            plugin.logger().warning("Commands newly disabled on reload: "
                    + eval.newlyDisabledRequiringRestartToYield()
                    + ". Dispatch is blocked immediately, but a server restart is required to yield their CommandMap labels to other plugins.");
        }
        if (!eval.newlyEnabledRequiringRestartToClaim().isEmpty()) {
            plugin.logger().warning("Commands re-enabled on reload that were disabled at startup: "
                    + eval.newlyEnabledRequiringRestartToClaim()
                    + ". A server restart is required to re-register their CommandMap labels.");
        }
        return eval;
    }

    private void logUnknownDisabledEntries(List<String> unknownEntries) {
        for (String unknown : unknownEntries) {
            plugin.logger().warning("Unknown command entry in disabled-commands: '" + unknown
                    + "' (not a registered Allium command or alias; ignored).");
        }
    }

    public void unregisterAll() {
        commands.clear();
        renderedCommands.clear();
    }

    public Map<String, CommandExecutor> getCommands() {
        return Collections.unmodifiableMap(commands);
    }

    public Map<String, BukkitCommandRenderer> getRenderedCommands() {
        return Collections.unmodifiableMap(renderedCommands);
    }

    public CommandFamilyPolicy getFamilyPolicy() {
        return familyPolicy;
    }
}
