package net.survivalfun.allium.core;

import net.survivalfun.allium.Allium;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ModuleManager {

    private final Allium plugin;
    private final Map<String, AbstractModule> modules = new LinkedHashMap<>();

    public ModuleManager(Allium plugin) {
        this.plugin = plugin;
    }

    public void registerModule(AbstractModule module) {
        modules.put(module.getName().toLowerCase(), module);
    }

    public void loadModules() {
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
