package codes.castled.allium.permissions.command;

import org.bukkit.command.Command;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class PermissionCandidates {

    private PermissionCandidates() {
    }

    static List<String> forContext(CommandPermissionContext context) {
        String pluginName = normalize(context.pluginName());
        if (pluginName == null) {
            return List.of();
        }

        Set<String> identities = commandIdentities(context.command(), context.label());
        List<String> modules = findModules(pluginName, identities, context.registeredPermissions());
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        if (!context.arguments().isEmpty()) {
            addNormalized(actions, context.arguments().getFirst());
        } else {
            // Only infer a GUI "open" action when the owning module actually registers it.
            // This covers /ah without making every no-argument plugin command look like open.
            if (hasRegisteredModuleAction(
                    pluginName,
                    modules,
                    "open",
                    context.registeredPermissions()
            )) {
                actions.add("open");
            }
            actions.addAll(commandLabels(context.command(), context.label()));
        }

        LinkedHashSet<String> exact = new LinkedHashSet<>();
        for (String action : actions) {
            for (String module : modules) {
                exact.add(pluginName + "." + module + ".command." + action);
            }
            exact.add(pluginName + ".command." + action);
            for (String module : modules) {
                exact.add(pluginName + "." + module + "." + action);
            }
            exact.add(pluginName + "." + action);
        }

        for (String label : commandLabels(context.command(), context.label())) {
            exact.add(pluginName + "." + label);
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>(exact);
        for (String module : modules) {
            candidates.add(pluginName + "." + module + ".command.*");
        }
        candidates.add(pluginName + ".command.*");
        for (String module : modules) {
            candidates.add(pluginName + "." + module + ".*");
        }
        candidates.add(pluginName + ".*");
        return List.copyOf(candidates);
    }

    private static boolean hasRegisteredModuleAction(String pluginName,
                                                     Collection<String> modules,
                                                     String action,
                                                     Collection<String> registeredPermissions) {
        Set<String> normalizedPermissions = registeredPermissions.stream()
                .map(PermissionCandidates::normalizePermission)
                .collect(java.util.stream.Collectors.toSet());
        return modules.stream().anyMatch(module -> normalizedPermissions.contains(
                pluginName + "." + module + ".command." + action
        ));
    }

    private static List<String> findModules(String pluginName,
                                            Set<String> identities,
                                            Collection<String> registeredPermissions) {
        String prefix = pluginName + ".";
        LinkedHashSet<String> modules = new LinkedHashSet<>();

        registeredPermissions.stream()
                .map(PermissionCandidates::normalizePermission)
                .filter(permission -> permission.startsWith(prefix))
                .forEach(permission -> {
                    String remainder = permission.substring(prefix.length());
                    int commandMarker = remainder.indexOf(".command.");
                    if (commandMarker > 0) {
                        modules.add(remainder.substring(0, commandMarker));
                    }
                });

        List<String> matching = modules.stream()
                .filter(module -> moduleMatches(module, identities))
                .sorted()
                .toList();
        if (!matching.isEmpty()) {
            return matching;
        }
        if (modules.size() == 1) {
            return List.copyOf(modules);
        }
        return List.of();
    }

    private static boolean moduleMatches(String module, Set<String> identities) {
        for (String modulePart : module.split("\\.")) {
            String normalizedModule = normalize(modulePart);
            if (normalizedModule == null) {
                continue;
            }
            for (String identity : identities) {
                if (identity.equals(normalizedModule)) {
                    return true;
                }
                if (identity.length() >= 4 && normalizedModule.length() >= 4
                        && (identity.startsWith(normalizedModule) || normalizedModule.startsWith(identity))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> commandIdentities(Command command, String invokedLabel) {
        LinkedHashSet<String> identities = new LinkedHashSet<>(commandLabels(command, invokedLabel));

        String usage = command.getUsage();
        if (usage != null && !usage.isBlank()) {
            addNormalized(identities, usage.strip().split("\\s+", 2)[0]);
        }
        for (String classNamePart : command.getClass().getName().split("[.$]")) {
            addNormalized(identities, classNamePart);
        }
        return identities;
    }

    private static Set<String> commandLabels(Command command, String invokedLabel) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        addNormalized(labels, invokedLabel);
        addNormalized(labels, command.getName());
        command.getAliases().forEach(alias -> addNormalized(labels, alias));
        return labels;
    }

    private static void addNormalized(Collection<String> values, String value) {
        String normalized = normalize(value);
        if (normalized != null) {
            values.add(normalized);
        }
    }

    private static String normalizePermission(String permission) {
        return permission == null ? "" : permission.strip().toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.strip().toLowerCase(Locale.ROOT);
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        int colonIndex = normalized.indexOf(':');
        if (colonIndex != -1) {
            normalized = normalized.substring(colonIndex + 1);
        }
        normalized = normalized.replaceAll("[^a-z0-9_-]", "");
        return normalized.isBlank() ? null : normalized;
    }
}
