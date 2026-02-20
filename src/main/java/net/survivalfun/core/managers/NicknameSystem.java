package net.survivalfun.core.managers;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.commands.Nick;

public class NicknameSystem {
    private final PluginStart plugin;
    private NicknameManager nicknameManager;

    public NicknameSystem(PluginStart plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        // Initialize nickname manager
        this.nicknameManager = new NicknameManager(plugin);
        
        // Register command
        plugin.getCommand("nickname").setExecutor(new Nick(plugin));
        
        // Register tab completer if needed
        // plugin.getCommand("nickname").setTabCompleter(new NicknameTabCompleter());
    }

    public NicknameManager getNicknameManager() {
        return nicknameManager;
    }
}
