package codes.castled.allium.command;

import codes.castled.allium.command.spec.CommandSpec;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Bridges Bukkit/Spigot/Paper {@link CommandMap} internals so disabled command families
 * yield their canonical labels, aliases, and {@code allium:<label>} forms cleanly, and refreshes
 * Brigadier/client command trees via {@code CraftServer#syncCommands()} and
 * {@link Player#updateCommands()}.
 *
 * <p>All reflective access is isolated inside {@link GuardedAccessor} with explicit diagnostics
 * on failure.
 */
public final class CommandMapBridge {

    public record AccessReport(
            String serverClass,
            String commandMapClass,
            String commandMapResolution,
            String knownCommandsResolution,
            boolean syncCommandsSupported,
            CommandMap commandMap,
            Map<String, Command> knownCommands
    ) {
    }

    private CommandMapBridge() {
    }

    /**
     * Unregisters Allium's ownership of the specified {@link CommandSpec} from the server's
     * {@link CommandMap}, promotes any shadowed plugin or vanilla command to the bare label,
     * synchronizes server Brigadier commands, and calls {@link Player#updateCommands()} for all
     * online players.
     *
     * @return true if the server's knownCommands map was accessed and updated
     */
    public static boolean yieldCommandFamily(Server server, Plugin plugin, CommandSpec spec) {
        return yieldCommandFamily(server, plugin, spec, msg -> {
            if (plugin != null && plugin.getLogger() != null) {
                plugin.getLogger().warning(msg);
            }
        });
    }

    public static boolean yieldCommandFamily(
            Server server,
            Plugin plugin,
            CommandSpec spec,
            Consumer<String> diagnosticLogger
    ) {
        AccessReport access = GuardedAccessor.inspectAndResolve(server, diagnosticLogger);
        if (access == null || access.commandMap() == null || access.knownCommands() == null) {
            return false;
        }

        PluginCommand alliumCommand = plugin instanceof JavaPlugin jp
                ? jp.getCommand(spec.canonicalName())
                : null;
        yieldFromKnownCommands(access.knownCommands(), access.commandMap(), plugin, alliumCommand, spec);
        GuardedAccessor.syncAndRefreshPlayerCommands(server, diagnosticLogger);
        return true;
    }

    /**
     * Inspects and reports how {@link CommandMap} and {@code knownCommands} are accessed on the
     * current server platform.
     */
    @Nullable
    public static AccessReport inspectPlatformAccess(Server server, Consumer<String> diagnosticLogger) {
        return GuardedAccessor.inspectAndResolve(server, diagnosticLogger);
    }

    @Nullable
    public static CommandMap resolveCommandMap(Server server) {
        AccessReport report = GuardedAccessor.inspectAndResolve(server, msg -> {});
        return report != null ? report.commandMap() : null;
    }

    /**
     * Resolves the server's {@link CommandMap} without requiring access to {@code knownCommands}.
     * Used by runtime lookups such as canonical muted-command identity resolution.
     */
    @Nullable
    public static CommandMap resolveCommandMapDirect(@Nullable Server server) {
        return GuardedAccessor.resolveCommandMapOnly(server);
    }

    /**
     * Returns the canonical identity object for a Bukkit {@link Command}:
     * for {@code VanillaCommandWrapper} instances (which wrap Mojang Brigadier root nodes),
     * unwraps {@code vanillaCommand} and follows {@code CommandNode#getRedirect()} so aliases
     * such as {@code /minecraft:tell -> /minecraft:msg} and {@code /tm -> /teammsg} resolve
     * to the same canonical command identity. For all other {@link Command}s (including
     * {@link PluginCommand} and custom commands), returns {@code command} itself.
     */
    @Nullable
    public static Object canonicalCommandIdentity(@Nullable Command command) {
        if (command == null) {
            return null;
        }
        try {
            Field nodeField = command.getClass().getField("vanillaCommand");
            Object node = nodeField.get(command);
            if (node != null) {
                Method getRedirect = node.getClass().getMethod("getRedirect");
                getRedirect.trySetAccessible();
                for (int depth = 0; depth < 8; depth++) {
                    Object redirect = getRedirect.invoke(node);
                    if (redirect == null || redirect == node) {
                        break;
                    }
                    node = redirect;
                }
                return node;
            }
        } catch (ReflectiveOperationException ignored) {
            // Not a VanillaCommandWrapper; compare by Command instance identity below.
        }
        return command;
    }

    @Nullable
    public static Map<String, Command> resolveKnownCommands(CommandMap commandMap) {
        return GuardedAccessor.resolveKnownCommandsOnly(commandMap, msg -> {});
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

    /**
     * Single guarded reflective accessor for {@link CommandMap}, {@code knownCommands}, and
     * {@code syncCommands()}, emitting a clear diagnostic if any lookup fails.
     */
    private static final class GuardedAccessor {

        @Nullable
        static CommandMap resolveCommandMapOnly(@Nullable Server server) {
            CommandMapResolution resolved = resolveCommandMapWithMethod(server);
            return resolved != null ? resolved.commandMap() : null;
        }

        @Nullable
        private static CommandMapResolution resolveCommandMapWithMethod(@Nullable Server server) {
            if (server == null) {
                return null;
            }
            try {
                Method method = server.getClass().getMethod("getCommandMap");
                method.setAccessible(true);
                Object result = method.invoke(server);
                if (result instanceof CommandMap map) {
                    return new CommandMapResolution(map, "Server#getCommandMap()");
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
                        return new CommandMapResolution(map, current.getName() + ".commandMap field");
                    }
                } catch (ReflectiveOperationException ignored) {
                }
                current = current.getSuperclass();
            }
            return null;
        }

        @Nullable
        static AccessReport inspectAndResolve(Server server, Consumer<String> diagnosticLogger) {
            if (server == null) {
                diagnosticLogger.accept("CommandMapBridge failed: Server instance is null.");
                return null;
            }

            CommandMapResolution cm = resolveCommandMapWithMethod(server);
            if (cm == null) {
                diagnosticLogger.accept("CommandMapBridge failed to access CommandMap on server class "
                        + server.getClass().getName() + "; disabled-commands cannot yield labels.");
                return null;
            }

            KnownCommandsResolution kc = resolveKnownCommandsWithMethod(cm.commandMap(), diagnosticLogger);
            if (kc == null) {
                return null;
            }

            boolean syncSupported = false;
            try {
                server.getClass().getMethod("syncCommands");
                syncSupported = true;
            } catch (NoSuchMethodException ignored) {
            }

            return new AccessReport(
                    server.getClass().getName(),
                    cm.commandMap().getClass().getName(),
                    cm.resolutionMethod(),
                    kc.resolutionMethod(),
                    syncSupported,
                    cm.commandMap(),
                    kc.knownCommands()
            );
        }

        @Nullable
        static Map<String, Command> resolveKnownCommandsOnly(CommandMap commandMap, Consumer<String> diagnosticLogger) {
            KnownCommandsResolution kc = resolveKnownCommandsWithMethod(commandMap, diagnosticLogger);
            return kc != null ? kc.knownCommands() : null;
        }

        @SuppressWarnings("unchecked")
        @Nullable
        private static KnownCommandsResolution resolveKnownCommandsWithMethod(
                CommandMap commandMap,
                Consumer<String> diagnosticLogger
        ) {
            if (commandMap == null) {
                return null;
            }
            // 1. Public getKnownCommands() method (Paper CommandMap / newer SimpleCommandMap)
            try {
                Method method = commandMap.getClass().getMethod("getKnownCommands");
                method.setAccessible(true);
                Object result = method.invoke(commandMap);
                if (result instanceof Map<?, ?> map) {
                    return new KnownCommandsResolution(
                            (Map<String, Command>) map,
                            commandMap.getClass().getSimpleName() + "#getKnownCommands()"
                    );
                }
            } catch (ReflectiveOperationException ignored) {
            }

            // 2. Protected/private knownCommands field on SimpleCommandMap hierarchy (Spigot 1.20)
            Class<?> current = commandMap.getClass();
            while (current != null) {
                try {
                    Field field = current.getDeclaredField("knownCommands");
                    field.setAccessible(true);
                    Object result = field.get(commandMap);
                    if (result instanceof Map<?, ?> map) {
                        return new KnownCommandsResolution(
                                (Map<String, Command>) map,
                                current.getName() + ".knownCommands field"
                        );
                    }
                } catch (ReflectiveOperationException ignored) {
                }
                if (current == SimpleCommandMap.class) {
                    break;
                }
                current = current.getSuperclass();
            }

            diagnosticLogger.accept("CommandMapBridge failed to access knownCommands on CommandMap class "
                    + commandMap.getClass().getName() + "; disabled-commands cannot yield labels.");
            return null;
        }

        static void syncAndRefreshPlayerCommands(Server server, Consumer<String> diagnosticLogger) {
            if (server == null) {
                return;
            }
            try {
                Method syncMethod = server.getClass().getMethod("syncCommands");
                syncMethod.setAccessible(true);
                syncMethod.invoke(server);
            } catch (NoSuchMethodException ignored) {
            } catch (ReflectiveOperationException e) {
                diagnosticLogger.accept("CommandMapBridge failed to invoke CraftServer#syncCommands() on "
                        + server.getClass().getName() + ": " + e.getMessage());
            }

            Collection<? extends Player> onlinePlayers = server.getOnlinePlayers();
            if (onlinePlayers != null) {
                for (Player player : onlinePlayers) {
                    if (player != null) {
                        try {
                            player.updateCommands();
                        } catch (Throwable t) {
                            diagnosticLogger.accept("CommandMapBridge failed to call updateCommands() for player "
                                    + player.getName() + ": " + t.getMessage());
                        }
                    }
                }
            }
        }

        private record CommandMapResolution(
                CommandMap commandMap,
                String resolutionMethod
        ) {
        }

        private record KnownCommandsResolution(
                Map<String, Command> knownCommands,
                String resolutionMethod
        ) {
        }
    }
}
