package codes.castled.allium.command.spec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Platform-neutral command family specification: canonical label, aliases, namespace,
 * subcommand tree, positional arguments, permission nodes, suggestion sources, and message keys.
 */
public record CommandSpec(
        String canonicalName,
        String namespace,
        String moduleName,
        List<String> aliases,
        String description,
        String selfUsage,
        String consoleUsage,
        String basePermission,
        String othersPermission,
        List<CommandArgSpec> arguments,
        List<CommandNodeSpec> children,
        CommandMessageKeys messageKeys
) {

    public static final String DEFAULT_NAMESPACE = "allium";

    public CommandSpec {
        Objects.requireNonNull(canonicalName, "canonicalName");
        final String normCanonical = canonicalName.trim().toLowerCase(Locale.ROOT);
        if (normCanonical.isEmpty()) {
            throw new IllegalArgumentException("canonicalName must not be empty");
        }
        canonicalName = normCanonical;
        namespace = (namespace == null || namespace.isBlank())
                ? DEFAULT_NAMESPACE
                : namespace.trim().toLowerCase(Locale.ROOT);
        moduleName = moduleName == null ? "essentials" : moduleName.trim().toLowerCase(Locale.ROOT);
        aliases = aliases == null
                ? List.of()
                : aliases.stream()
                        .map(a -> a.trim().toLowerCase(Locale.ROOT))
                        .filter(a -> !a.isEmpty() && !a.equals(normCanonical))
                        .distinct()
                        .toList();
        description = description == null ? "" : description;
        selfUsage = selfUsage == null ? "/" + normCanonical : selfUsage;
        consoleUsage = consoleUsage == null ? selfUsage : consoleUsage;
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        children = children == null ? List.of() : List.copyOf(children);
        Objects.requireNonNull(messageKeys, "messageKeys");
    }

    /**
     * Returns all bare labels owned by this command family (canonical name + aliases).
     */
    public Set<String> bareLabels() {
        Set<String> labels = new LinkedHashSet<>();
        labels.add(canonicalName);
        labels.addAll(aliases);
        return Collections.unmodifiableSet(labels);
    }

    /**
     * Returns all labels owned by this command family in Bukkit's CommandMap:
     * canonical name, aliases, and {@code <namespace>:<label>} for each.
     */
    public Set<String> allOwnedLabels() {
        Set<String> labels = new LinkedHashSet<>();
        labels.add(canonicalName);
        labels.add(namespace + ":" + canonicalName);
        for (String alias : aliases) {
            labels.add(alias);
            labels.add(namespace + ":" + alias);
        }
        return Collections.unmodifiableSet(labels);
    }

    /**
     * Evaluates permission-aware, visibility-aware, position-aware tab completions for this command spec.
     */
    public List<String> complete(CommandCompletionContext context, String[] args) {
        Objects.requireNonNull(context, "context");
        if (args == null || args.length == 0) {
            return List.of();
        }
        if (basePermission != null && !basePermission.isEmpty() && !context.hasPermission(basePermission)) {
            return List.of();
        }

        List<CommandNodeSpec> currentChildren = this.children;
        List<CommandArgSpec> currentArguments = this.arguments;
        int argOffset = 0;

        while (argOffset < args.length - 1 && !currentChildren.isEmpty()) {
            String token = args[argOffset];
            CommandNodeSpec matchedChild = null;
            for (CommandNodeSpec child : currentChildren) {
                if (child.matches(token)) {
                    matchedChild = child;
                    break;
                }
            }
            if (matchedChild == null) {
                return List.of();
            }
            if (matchedChild.permissionNode() != null
                    && !matchedChild.permissionNode().isEmpty()
                    && !context.hasPermission(matchedChild.permissionNode())) {
                return List.of();
            }
            currentChildren = matchedChild.children();
            currentArguments = matchedChild.arguments();
            argOffset++;
        }

        String prefix = args[args.length - 1] == null ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();

        // Suggest matching child subcommands at the current depth
        if (!currentChildren.isEmpty()) {
            for (CommandNodeSpec child : currentChildren) {
                if (child.permissionNode() == null
                        || child.permissionNode().isEmpty()
                        || context.hasPermission(child.permissionNode())) {
                    if (child.name().startsWith(prefix)) {
                        candidates.add(child.name());
                    }
                }
            }
        }

        // Suggest positional arguments at the current depth
        int relativeArgIndex = (args.length - 1) - argOffset;
        if (relativeArgIndex >= 0 && relativeArgIndex < currentArguments.size()) {
            CommandArgSpec argSpec = currentArguments.get(relativeArgIndex);
            if (argSpec.permissionNode() != null
                    && !argSpec.permissionNode().isEmpty()
                    && !context.hasPermission(argSpec.permissionNode())) {
                return candidates;
            }
            SuggestionSpec suggestion = argSpec.suggestion();
            if (suggestion.requiredPermission() != null
                    && !suggestion.requiredPermission().isEmpty()
                    && !context.hasPermission(suggestion.requiredPermission())) {
                return candidates;
            }

            List<String> rawSuggestions = switch (suggestion.source()) {
                case NONE -> List.of();
                case GAMEMODES, LITERAL_LIST -> suggestion.literals();
                case ONLINE_PLAYERS -> context.visibleOnlinePlayers();
                case KNOWN_PLAYERS -> context.knownPlayers(prefix);
                case MAIL_DELETE_IDS -> context.mailIds(false);
                case HOME_NAMES -> context.travelNames(false, prefix);
                case WARP_NAMES -> context.travelNames(true, prefix);
                case KIT_NAMES -> context.kitNames(false, prefix);
                case ALL_KIT_NAMES -> context.kitNames(true, prefix);
                case MAIL_CLAIM_IDS -> {
                    List<String> ids = context.mailIds(true);
                    if (ids.isEmpty()) {
                        yield List.of();
                    }
                    List<String> claimable = new ArrayList<>(ids);
                    claimable.add("all");
                    yield claimable;
                }
            };

            for (String candidate : rawSuggestions) {
                if (candidate != null && candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    candidates.add(candidate);
                }
            }
        }

        return List.copyOf(candidates);
    }
}
