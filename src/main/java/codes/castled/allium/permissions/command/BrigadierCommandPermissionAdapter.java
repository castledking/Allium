package codes.castled.allium.permissions.command;

import com.mojang.brigadier.tree.CommandNode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

final class BrigadierCommandPermissionAdapter implements CommandPermissionAdapter {

    @Override
    public Optional<PermissionResult> resolve(CommandPermissionContext context) {
        Command command = context.command();
        Field nodeField = findNodeField(command.getClass());
        boolean vanillaWrapper = isVanilla(context);

        // Vanilla brigadier requirements are op-level checks (source.hasPermission(2)) that know
        // nothing about the minecraft.command.<name> node CraftBukkit registers for the same
        // command. Testing them here denies non-op players who were legitimately granted that
        // node, so the Bukkit permission wins for vanilla commands.
        if (vanillaWrapper) {
            String permission = command.getPermission();
            if (permission != null && !permission.isBlank() && command.testPermissionSilent(context.player())) {
                return Optional.of(new PermissionResult(true, ResolutionType.VANILLA, permission, command));
            }
        }

        if (nodeField == null && !vanillaWrapper) {
            return Optional.empty();
        }
        if (nodeField == null) {
            return Optional.of(PermissionResult.unknown(true, command));
        }

        try {
            nodeField.setAccessible(true);
            Object value = nodeField.get(command);
            if (!(value instanceof CommandNode<?> commandNode)) {
                return Optional.of(PermissionResult.unknown(true, command));
            }

            Object source = createCommandSource(command.getClass(), context.player());
            if (source == null) {
                return vanillaWrapper
                        ? Optional.of(PermissionResult.unknown(true, command))
                        : Optional.empty();
            }

            boolean allowed = testInvocation(commandNode, source, context.arguments());
            return Optional.of(new PermissionResult(
                    allowed,
                    resolutionType(command),
                    command.getPermission(),
                    command
            ));
        } catch (Throwable ignored) {
            // Native dispatch will still enforce the predicate. UNKNOWN makes the fail-open explicit.
            return vanillaWrapper
                    ? Optional.of(PermissionResult.unknown(true, command))
                    : Optional.empty();
        }
    }

    /**
     * Vanilla commands reach us as a CraftBukkit/Paper wrapper, as a command whose help namespace
     * is {@code minecraft}, or simply as a {@code minecraft:} invocation resolved by the pipeline.
     */
    private boolean isVanilla(CommandPermissionContext context) {
        Command command = context.command();
        String className = command.getClass().getName().toLowerCase(Locale.ROOT);
        return className.contains("vanillacommandwrapper")
                || resolutionType(command) == ResolutionType.VANILLA
                || "minecraft".equals(context.pluginName());
    }

    private Field findNodeField(Class<?> commandClass) {
        for (Class<?> type = commandClass; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && CommandNode.class.isAssignableFrom(field.getType())) {
                    return field;
                }
            }
        }
        return null;
    }

    private Object createCommandSource(Class<?> commandClass, CommandSender sender) throws ReflectiveOperationException {
        for (Class<?> type = commandClass; type != null; type = type.getSuperclass()) {
            try {
                Method getListener = type.getDeclaredMethod("getListener", CommandSender.class);
                if (!Modifier.isStatic(getListener.getModifiers())) {
                    continue;
                }
                getListener.setAccessible(true);
                return getListener.invoke(null, sender);
            } catch (NoSuchMethodException ignored) {
                // Continue up the wrapper hierarchy.
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private boolean testInvocation(CommandNode<?> initialNode, Object source, Iterable<String> arguments) {
        Set<CommandNode<?>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        CommandNode<?> node = initialNode;
        node = testNodeAndRedirects(node, source, visited);
        if (node == null) {
            return false;
        }

        // Literal subcommands retain their own requires predicates. Argument-node parsing
        // remains native-dispatch territory because doing it correctly needs the NMS source.
        for (String argument : arguments) {
            CommandNode<?> child = node.getChild(argument);
            if (child == null) {
                child = node.getChild(argument.toLowerCase(Locale.ROOT));
            }
            if (child == null) {
                break;
            }
            node = testNodeAndRedirects(child, source, visited);
            if (node == null) {
                return false;
            }
        }
        return true;
    }

    private CommandNode<?> testNodeAndRedirects(CommandNode<?> initialNode,
                                                Object source,
                                                Set<CommandNode<?>> visited) {
        CommandNode<?> node = initialNode;
        while (node != null && visited.add(node)) {
            Predicate<Object> requirement = (Predicate<Object>) node.getRequirement();
            if (!requirement.test(source)) {
                return null;
            }
            CommandNode<?> redirect = node.getRedirect();
            if (redirect == null) {
                return node;
            }
            node = redirect;
        }
        return node;
    }

    private ResolutionType resolutionType(Command command) {
        try {
            Field namespace = command.getClass().getField("helpCommandNamespace");
            Object value = namespace.get(command);
            if (value != null && "minecraft".equals(value.toString().toLowerCase(Locale.ROOT))) {
                return ResolutionType.VANILLA;
            }
        } catch (ReflectiveOperationException ignored) {
            // A plugin-owned Brigadier wrapper may not expose a help namespace.
        }
        return ResolutionType.BRIGADIER;
    }
}
