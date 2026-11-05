package codes.castled.allium.command;

import codes.castled.allium.command.spec.CommandSpec;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;

/**
 * Bridges Bukkit/Spigot/Paper {@link CommandMap} internals so disabled command families
 * yield their canonical labels, aliases, and {@code allium:<label>} forms cleanly.
 */
public final class CommandMapBridge {

    private CommandMapBridge() {
    }

    /**
     * Unregisters Allium's ownership of the specified {@link CommandSpec} from the server's
     * {@link CommandMap} and promotes any shadowed plugin or vanilla command to the bare label.
     *
     * @return true if the server's knownCommands map was accessed and updated
     */
    public static boolean yieldCommandFamily(Server server, Plugin plugin, CommandSpec spec) {
        CommandMap commandMap = resolveCommandMap(server);
        if (commandMap == null) {
            return false;
        }
        Map<String, Command> knownCommands = resolveKnownCommands(commandMap);
        if (knownCommands == null) {
            return false;
        }

        PluginCommand alliumCommand = plugin instanceof JavaPlugin jp
                ? jp.getCommand(spec.canonicalName())
                : null;
        yieldFromKnownCommands(knownCommands, commandMap, plugin, alliumCommand, spec);
        syncServerCommands(server);
        return true;
    }

    /**
     * Pure map-level implementation of label yielding and fallback promotion (directly testable).
     */
    public static void yieldFromKnownCommands(
            Map<String, Command> knownCommands,
            @Nullable CommandMap commandMap,
            Plugin alliumPlugin,
            @Nullable Command alliumCommand,
            CommandSpec spec
    ) {
        for (String label : spec.allOwnedLabels()) {
            String key = label.toLowerCase(Locale.ROOT);
            Command current = knownCommands.get(key);
            if (isOwnedByPlugin(current, alliumPlugin, alliumCommand)) {
                knownCommands.remove(key);
            }
        }

        if (alliumCommand != null && commandMap != null) {
            alliumCommand.unregister(commandMap);
        }

        // If Allium shadowed another plugin's command (or vanilla command) during loadPlugins(),
        // promote the shadowed command to the bare label so the other plugin owns it immediately.
        for (String bareLabel : spec.bareLabels()) {
            String normalizedBare = bareLabel.toLowerCase(Locale.ROOT);
            if (!knownCommands.containsKey(normalizedBare)) {
                Command promoted = findShadowedFallback(knownCommands, alliumPlugin, alliumCommand, normalizedBare);
                if (promoted != null) {
                    knownCommands.put(normalizedBare, promoted);
                }
            }
        }
    }

    @Nullable
    private static Command findShadowedFallback(
            Map<String, Command> knownCommands,
            Plugin alliumPlugin,
            @Nullable Command alliumCommand,
            String bareLabel
    ) {
        String suffix = ":" + bareLabel;
        Command vanillaFallback = null;

        for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
            String key = entry.getKey();
            Command cmd = entry.getValue();
            if (key == null || cmd == null || isOwnedByPlugin(cmd, alliumPlugin, alliumCommand)) {
                continue;
            }
            String lowerKey = key.toLowerCase(Locale.ROOT);
            if (lowerKey.endsWith(suffix)) {
                if (lowerKey.startsWith("minecraft:") || lowerKey.startsWith("bukkit:") || lowerKey.startsWith("spigot:")) {
                    if (vanillaFallback == null) {
                        vanillaFallback = cmd;
                    }
                } else {
                    return cmd;
                }
            }
        }
        return vanillaFallback;
    }

    private static boolean isOwnedByPlugin(
            @Nullable Command command,
            Plugin alliumPlugin,
            @Nullable Command alliumCommand
    ) {
        if (command == null) {
            return false;
        }
        if (command == alliumCommand) {
            return true;
        }
        if (command instanceof PluginIdentifiableCommand pic) {
            return pic.getPlugin() == alliumPlugin;
        }
        return false;
    }

    @Nullable
    public static CommandMap resolveCommandMap(Server server) {
        if (server == null) {
            return null;
        }
        try {
            Method method = server.getClass().getMethod("getCommandMap");
            method.setAccessible(true);
            Object result = method.invoke(server);
            if (result instanceof CommandMap map) {
                return map;
            }
        } catch (ReflectiveOperationException ignored) {
        }

        Class<?> current = server.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField("commandMap");
                field.setAccessible(true);
                Object result = field.get(server);
                if (result instanceof CommandMap map) {
                    return map;
                }
            } catch (ReflectiveOperationException ignored) {
            }
            current = current.getSuperclass();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static Map<String, Command> resolveKnownCommands(CommandMap commandMap) {
        if (commandMap == null) {
            return null;
        }
        try {
            Method method = commandMap.getClass().getMethod("getKnownCommands");
            method.setAccessible(true);
            Object result = method.invoke(commandMap);
            if (result instanceof Map<?, ?> map) {
                return (Map<String, Command>) map;
            }
        } catch (ReflectiveOperationException ignored) {
        }

        Class<?> current = commandMap.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField("knownCommands");
                field.setAccessible(true);
                Object result = field.get(commandMap);
                if (result instanceof Map<?, ?> map) {
                    return (Map<String, Command>) map;
                }
            } catch (ReflectiveOperationException ignored) {
            }
            if (current == SimpleCommandMap.class) {
                break;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static void syncServerCommands(Server server) {
        if (server == null) {
            return;
        }
        try {
            Method syncMethod = server.getClass().getMethod("syncCommands");
            syncMethod.setAccessible(true);
            syncMethod.invoke(server);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
