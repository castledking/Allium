package codes.castled.allium.core;

import codes.castled.allium.PluginStart;
import codes.castled.allium.module.EconomyModule;
import codes.castled.allium.module.EssentialsModule;
import codes.castled.allium.module.ToolsModule;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ModuleManager {

    private final PluginStart plugin;
    private final Map<String, AbstractModule> modules = new LinkedHashMap<>();

    public ModuleManager(PluginStart plugin) {
        this.plugin = plugin;
    }

    public void registerModule(AbstractModule module) {
        modules.put(module.getName().toLowerCase(), module);
    }

    public void loadModules() {
        registerModule(new EconomyModule(plugin));
        registerModule(new EssentialsModule(plugin));
        registerModule(new ToolsModule(plugin));
        for (AbstractModule module : modules.values()) {
            String key = module.getName().toLowerCase();
            boolean enabled = plugin.getConfig().getBoolean("modules." + key, true);
            if (enabled) {
                try {
                    module.enable();
                } catch (Exception e) {
                    plugin.logger().severe("Failed to enable module [" + module.getName() + "]: " + e.getMessage());
                }
            }
        }
    }

    public void unloadModules() {
        for (AbstractModule module : modules.values()) {
            if (module.isEnabled()) {
                try {
                    module.disable();
                } catch (Exception e) {
                    plugin.logger().severe("Failed to disable module [" + module.getName() + "]: " + e.getMessage());
                }
            }
        }
    }

    public void reloadModules() {
        unloadModules();
        loadModules();
    }

    @Nullable
    public AbstractModule getModule(String name) {
        return modules.get(name.toLowerCase());
    }

    public Map<String, AbstractModule> getModules() {
        return Collections.unmodifiableMap(modules);
    }
}
