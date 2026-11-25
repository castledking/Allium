package codes.castled.allium.command;

import codes.castled.allium.command.essentials.PlayerUtilityCommand;
import codes.castled.allium.command.essentials.UnlimitedCommand;
import codes.castled.allium.command.essentials.EditSignCommand;
import codes.castled.allium.command.essentials.BookCommand;
import codes.castled.allium.command.essentials.CondenseCommand;
import codes.castled.allium.command.essentials.RemoveCommand;
import codes.castled.allium.command.essentials.ThunderCommand;
import codes.castled.allium.command.essentials.TreeCommand;
import codes.castled.allium.command.essentials.BlockToolCommand;
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
        this.familyPolicy.registerSpecs(allStartupSpecs());
        initializePolicyFromConfig();
    }

    /** Single authoritative startup inventory of every core Allium command family. */
    public static List<CommandSpec> allStartupSpecs() {
        List<CommandSpec> all = new java.util.ArrayList<>();
        all.addAll(CoreCommandSpecs.ESSENTIALS_PROOF_SPECS);
        all.addAll(CoreCommandSpecs.UTILITY_SPECS);
        all.addAll(CoreCommandSpecs.PARITY_UTILITY_SPECS);
        all.addAll(CoreCommandSpecs.ITEM_SPECS);
        all.addAll(CoreCommandSpecs.MESSAGING_SPECS);
        all.addAll(CoreCommandSpecs.MODERATION_SPECS);
        all.addAll(CoreCommandSpecs.INFO_SPECS);
        all.addAll(CoreCommandSpecs.ALTS_SPECS);
        all.addAll(CoreCommandSpecs.RESTORE_SPECS);
        all.addAll(CoreCommandSpecs.TOOLS_SPECS);
        all.addAll(CoreCommandSpecs.CREATIVE_SPECS);
        all.addAll(CoreCommandSpecs.RTP_SPECS);
        all.addAll(CoreCommandSpecs.WORTH_SPECS);
        all.addAll(CoreCommandSpecs.EXPLOSION_SPECS);
        all.addAll(CoreCommandSpecs.ECONOMY_SPECS);
        all.addAll(CoreCommandSpecs.CHAT_SPECS);
        all.add(CoreCommandSpecs.CLEAR_CHAT);
        all.add(CoreCommandSpecs.EDITNICK);
        all.addAll(CoreCommandSpecs.MAIL_SPECS);
        all.addAll(CoreCommandSpecs.TELEPORT_SPECS);
        all.addAll(CoreCommandSpecs.TRAVEL_SPECS);
        all.addAll(CoreCommandSpecs.KIT_SPECS);
        all.addAll(CoreCommandSpecs.CORE_SPECS);
        all.addAll(List.of(BlockToolCommand.BREAK, BlockToolCommand.SPAWNER,
                TreeCommand.TREE, TreeCommand.BIG_TREE, ThunderCommand.SPEC, RemoveCommand.SPEC,
                CondenseCommand.SPEC, BookCommand.SPEC, EditSignCommand.SPEC, UnlimitedCommand.SPEC));
        for (var action : PlayerUtilityCommand.Action.values()) {
            all.add(action.spec());
        }
        return List.copyOf(all);
    }

    /**
     * Registers an addon command family into Allium's shared command inventory and policy so
     * {@code disabled-commands}, {@code /help}, {@code HideService}, execution, and tab completion
     * apply the exact same rules to addon and core commands.
     */
    public boolean registerAddonCommand(org.bukkit.plugin.Plugin addon, PluginCommand command, SpecCommandHandler handler) {
        CommandSpec spec = handler.spec();
        if (familyPolicy.registerSpecAndApplyStartup(spec)) {
            boolean yielded = CommandMapBridge.yieldCommandFamily(plugin.getServer(), addon, spec);
            plugin.logger().info("Addon command family '" + spec.canonicalName() + "' (" + addon.getName()
                    + ") is disabled via disabled-commands; yielded labels " + spec.allOwnedLabels()
                    + (yielded ? " from CommandMap." : "."));
            return false;
        }
        BukkitCommandRenderer renderer = new BukkitCommandRenderer(plugin, familyPolicy, handler);
        renderedCommands.put(spec.canonicalName(), renderer);
        commands.put(spec.canonicalName(), renderer);
        command.setExecutor(renderer);
        command.setTabCompleter(renderer);
        return true;
    }

    /** Removes a previously registered addon command family from the shared inventory on disable. */
    public void unregisterAddonCommand(CommandSpec spec, PluginCommand command) {
        command.setExecutor(null);
        command.setTabCompleter(null);
        renderedCommands.remove(spec.canonicalName());
        commands.remove(spec.canonicalName());
        familyPolicy.unregisterSpec(spec);
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
        if (familyPolicy.registerSpecAndApplyStartup(spec)) {
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
    public codes.castled.allium.config.ReloadCoordinator.Prepared prepareReload(org.bukkit.configuration.file.FileConfiguration candidateRoot) {
        List<String> rawDisabled = candidateRoot != null
                ? candidateRoot.getStringList("disabled-commands")
                : List.of();
        return () -> {
            CommandFamilyPolicy.PolicyEvaluation eval = applyReloadList(rawDisabled);
            List<codes.castled.allium.config.ReloadReport.Entry> notices = new java.util.ArrayList<>();
            if (!eval.newlyDisabledRequiringRestartToYield().isEmpty()) {
                notices.add(new codes.castled.allium.config.ReloadReport.Entry(
                        "commands",
                        "newly disabled " + eval.newlyDisabledRequiringRestartToYield()
                                + " blocked immediately; restart required to yield CommandMap labels"));
            }
            if (!eval.newlyEnabledRequiringRestartToClaim().isEmpty()) {
                notices.add(new codes.castled.allium.config.ReloadReport.Entry(
                        "commands",
                        "re-enabled " + eval.newlyEnabledRequiringRestartToClaim()
                                + " require a server restart to re-register CommandMap labels"));
            }
            return notices;
        };
    }

    public CommandFamilyPolicy.PolicyEvaluation reload() {
        List<String> rawDisabled = plugin.getConfig() != null
                ? plugin.getConfig().getStringList("disabled-commands")
                : List.of();
        return applyReloadList(rawDisabled);
    }

    private CommandFamilyPolicy.PolicyEvaluation applyReloadList(List<String> rawDisabled) {
        java.util.Set<String> previous = familyPolicy.activeDisabledFamilies();
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
        if (!previous.equals(eval.activeDisabledFamilies())) {
            refreshOnlineCommandTrees();
        }
        return eval;
    }

    private void refreshOnlineCommandTrees() {
        var server = plugin.getServer();
        if (server == null) return;
        var scheduler = plugin.getScheduler();
        for (org.bukkit.entity.Player player : List.copyOf(server.getOnlinePlayers())) {
            if (codes.castled.allium.scheduler.BukkitServerThread.isFolia() && scheduler != null) {
                var owner = codes.castled.allium.scheduler.BukkitServerThread.entity(plugin, player);
                scheduler.runOn(owner, () -> { if (player.isOnline()) player.updateCommands(); }, () -> {}, 0L);
            } else {
                player.updateCommands();
            }
        }
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
