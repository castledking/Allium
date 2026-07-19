package codes.castled.allium.permissions.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Default resolver pipeline for Bukkit, Paper BasicCommand, Brigadier, NightCore,
 * LuckPerms, and registered permission conventions.
 */
public final class DefaultCommandPermissionResolver implements CommandPermissionResolver {

    private static final long PERMISSION_CACHE_TTL_MILLIS = 2_000L;

    private final List<CommandPermissionAdapter> adapters;
    private volatile Set<String> registeredPermissionsCache;
    private volatile long registeredPermissionsCacheTime;

    public DefaultCommandPermissionResolver() {
        this(List.of());
    }

    /**
     * Creates the resolver with framework-specific adapters evaluated before the built-ins.
     * This is the extension point for CommandAPI, ACF, cloud, or another command framework.
     */
    public DefaultCommandPermissionResolver(Collection<? extends CommandPermissionAdapter> additionalAdapters) {
        ArrayList<CommandPermissionAdapter> pipeline = new ArrayList<>(additionalAdapters);
        pipeline.add(new PaperBasicCommandPermissionAdapter());
        pipeline.add(new BrigadierCommandPermissionAdapter());
        pipeline.add(new NightCoreCommandPermissionAdapter());
        pipeline.add(new BukkitCommandPermissionAdapter());
        pipeline.add(new LuckPermsCommandPermissionAdapter());
        pipeline.add(new RegisteredCommandPermissionAdapter());
        this.adapters = List.copyOf(pipeline);
    }

    @Override
    public @NotNull PermissionResult resolve(@NotNull Player player, @NotNull String fullCommandLine) {
        ParsedCommandLine parsed = ParsedCommandLine.parse(fullCommandLine);
        if (parsed.label().isBlank()) {
            return PermissionResult.unknown(false, null);
        }

        Map<String, Command> knownCommands = Bukkit.getCommandMap().getKnownCommands();
        Command command = resolveCommand(knownCommands, parsed.label());
        if (command == null) {
            return PermissionResult.unknown(false, null);
        }

        CommandPermissionContext context = new CommandPermissionContext(
                player,
                fullCommandLine,
                baseLabel(parsed.label()),
                parsed.arguments(),
                command,
                findOwningPlugin(command, parsed.label(), knownCommands),
                registeredPermissionNames()
        );

        for (CommandPermissionAdapter adapter : adapters) {
            try {
                Optional<PermissionResult> result = adapter.resolve(context);
                if (result.isPresent()) {
                    return result.get();
                }
            } catch (Throwable ignored) {
                // A third-party adapter must not prevent native command dispatch.
            }
        }

        // Unknown means Allium cannot inspect the rule; the command framework still enforces it.
        return PermissionResult.unknown(true, command);
    }

    private @Nullable Command resolveCommand(Map<String, Command> knownCommands, String commandLabel) {
        String lowerLabel = commandLabel.toLowerCase(Locale.ROOT);
        Command command = knownCommands.get(lowerLabel);
        if (command != null) {
            return command;
        }

        String baseLabel = baseLabel(lowerLabel);
        command = knownCommands.get(baseLabel);
        if (command != null) {
            return command;
        }

        if (!lowerLabel.contains(":")) {
            for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
                String key = entry.getKey();
                if (key != null && baseLabel(key.toLowerCase(Locale.ROOT)).equals(baseLabel)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private @Nullable String findOwningPlugin(Command command,
                                               String invokedLabel,
                                               Map<String, Command> knownCommands) {
        if (command instanceof PluginIdentifiableCommand identifiable) {
            Plugin plugin = identifiable.getPlugin();
            if (plugin != null) {
                return plugin.getName().toLowerCase(Locale.ROOT);
            }
        }

        String reflectedNamespace = reflectedHelpNamespace(command);
        if (reflectedNamespace != null) {
            return reflectedNamespace;
        }

        int invokedNamespaceSeparator = invokedLabel.indexOf(':');
        if (invokedNamespaceSeparator > 0) {
            return invokedLabel.substring(0, invokedNamespaceSeparator).toLowerCase(Locale.ROOT);
        }

        for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
            String key = entry.getKey();
            if (key != null && entry.getValue() == command && key.contains(":")) {
                return key.substring(0, key.indexOf(':')).toLowerCase(Locale.ROOT);
            }
        }

        Package commandPackage = command.getClass().getPackage();
        if (commandPackage != null) {
            String packageName = commandPackage.getName();
            if (packageName.startsWith("org.bukkit.command.defaults") || packageName.startsWith("net.minecraft")) {
                return "minecraft";
            }
        }

        return null;
    }

    private @Nullable String reflectedHelpNamespace(Command command) {
        try {
            Field field = command.getClass().getField("helpCommandNamespace");
            Object value = field.get(command);
            if (value != null && !value.toString().isBlank()) {
                return value.toString().toLowerCase(Locale.ROOT);
            }
        } catch (ReflectiveOperationException ignored) {
            // Not a CraftBukkit/Paper Brigadier wrapper.
        }
        return null;
    }

    private Set<String> registeredPermissionNames() {
        long now = System.currentTimeMillis();
        Set<String> cached = registeredPermissionsCache;
        if (cached != null && now - registeredPermissionsCacheTime <= PERMISSION_CACHE_TTL_MILLIS) {
            return cached;
        }

        LinkedHashSet<String> refreshed = new LinkedHashSet<>();
        Bukkit.getPluginManager().getPermissions().forEach(permission ->
                refreshed.add(permission.getName().toLowerCase(Locale.ROOT))
        );
        Set<String> immutable = Set.copyOf(refreshed);
        registeredPermissionsCache = immutable;
        registeredPermissionsCacheTime = now;
        return immutable;
    }

    private static String baseLabel(String commandLabel) {
        int colonIndex = commandLabel.indexOf(':');
        return colonIndex == -1 ? commandLabel : commandLabel.substring(colonIndex + 1);
    }

    private record ParsedCommandLine(String label, List<String> arguments) {

        private static ParsedCommandLine parse(String commandLine) {
            String normalized = commandLine == null ? "" : commandLine.strip();
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            if (normalized.isBlank()) {
                return new ParsedCommandLine("", List.of());
            }

            String[] parts = normalized.split("\\s+");
            String label = parts[0].toLowerCase(Locale.ROOT);
            if (parts.length == 1) {
                return new ParsedCommandLine(label, List.of());
            }

            ArrayList<String> arguments = new ArrayList<>(parts.length - 1);
            for (int index = 1; index < parts.length; index++) {
                arguments.add(parts[index]);
            }
            return new ParsedCommandLine(label, List.copyOf(arguments));
        }
    }
}
