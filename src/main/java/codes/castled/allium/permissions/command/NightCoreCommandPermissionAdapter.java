package codes.castled.allium.permissions.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads NightCore's public command tree without adding a hard NightCore dependency.
 */
final class NightCoreCommandPermissionAdapter implements CommandPermissionAdapter {

    @Override
    public Optional<PermissionResult> resolve(CommandPermissionContext context) {
        Command command = context.command();
        try {
            Method getRoot = findMethod(command.getClass(), "getRoot");
            if (getRoot == null) {
                return Optional.empty();
            }

            Object root = invoke(getRoot, command);
            NodeMethods methods = NodeMethods.inspect(root);
            if (methods == null) {
                return Optional.empty();
            }

            NodeDecision rootDecision = testNode(methods, root, context.player());
            if (!rootDecision.allowed()) {
                return Optional.of(result(false, rootDecision.matchedPermission(), command));
            }

            List<String> arguments = context.arguments();
            if (arguments.isEmpty()) {
                return resolveDefaultExecutor(context, methods, root, rootDecision);
            }

            Object node = methods.child(root, arguments.getFirst().toLowerCase(Locale.ROOT));
            if (node == null) {
                // The framework will parse syntax and dynamic arguments during native dispatch.
                return rootDecision.inspectable()
                        ? Optional.of(result(true, rootDecision.matchedPermission(), command))
                        : Optional.empty();
            }

            String matchedPermission = rootDecision.matchedPermission();
            for (int argumentIndex = 0; node != null; argumentIndex++) {
                NodeMethods nodeMethods = NodeMethods.inspect(node);
                if (nodeMethods == null) {
                    break;
                }

                NodeDecision decision = testNode(nodeMethods, node, context.player());
                if (!decision.allowed()) {
                    return Optional.of(result(false, decision.matchedPermission(), command));
                }
                if (decision.matchedPermission() != null) {
                    matchedPermission = decision.matchedPermission();
                }

                if (argumentIndex + 1 >= arguments.size()) {
                    break;
                }
                node = nextNode(nodeMethods, node, arguments.get(argumentIndex + 1));
            }

            return Optional.of(result(true, matchedPermission, command));
        } catch (Throwable ignored) {
            // NightCore remains authoritative if its command tree cannot be inspected.
            return Optional.empty();
        }
    }

    private Optional<PermissionResult> resolveDefaultExecutor(CommandPermissionContext context,
                                                               NodeMethods methods,
                                                               Object root,
                                                               NodeDecision rootDecision) throws ReflectiveOperationException {
        if (methods.executor(root) != null) {
            Object openNode = methods.child(root, "open");
            NodeMethods openMethods = NodeMethods.inspect(openNode);
            if (openMethods != null) {
                String permission = openMethods.permission(openNode);
                if (permission != null && !permission.isBlank()) {
                    // NightCore hub commands commonly make their root executor the same action
                    // exposed by an "open" branch (ExcellentShop's /ah is one such command).
                    NodeDecision openDecision = testNode(openMethods, openNode, context.player());
                    return Optional.of(result(
                            openDecision.allowed(),
                            openDecision.matchedPermission(),
                            context.command()
                    ));
                }
            }
        }

        return rootDecision.inspectable()
                ? Optional.of(result(true, rootDecision.matchedPermission(), context.command()))
                : Optional.empty();
    }

    private Object nextNode(NodeMethods methods, Object node, String argument) throws ReflectiveOperationException {
        Object literal = methods.child(node, argument.toLowerCase(Locale.ROOT));
        if (literal != null) {
            return literal;
        }

        Collection<?> children = methods.children(node);
        return children.size() == 1 ? children.iterator().next() : null;
    }

    private NodeDecision testNode(NodeMethods methods,
                                  Object node,
                                  CommandSender sender) throws ReflectiveOperationException {
        String permission = methods.permission(node);
        if (!methods.hasPermission(node, sender)) {
            return new NodeDecision(false, permission, true);
        }
        if (!methods.canUse(node, sender)) {
            return new NodeDecision(false, null, true);
        }
        return new NodeDecision(true, permission, permission != null || methods.hasRequirements(node));
    }

    private PermissionResult result(boolean allowed, @Nullable String permission, Command command) {
        return new PermissionResult(allowed, ResolutionType.NIGHTCORE, permission, command);
    }

    private static @Nullable Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Continue through the hierarchy.
            }
        }
        return null;
    }

    private static Object invoke(Method method, Object target, Object... arguments) throws ReflectiveOperationException {
        return method.invoke(target, arguments);
    }

    private record NodeDecision(boolean allowed,
                                @Nullable String matchedPermission,
                                boolean inspectable) {
    }

    private record NodeMethods(Method hasPermission,
                               Method canUse,
                               Method getPermission,
                               Method getChild,
                               Method getChildren,
                               @Nullable Method getRequirements,
                               @Nullable Method getExecutor) {

        private static @Nullable NodeMethods inspect(@Nullable Object node) {
            if (node == null) {
                return null;
            }

            Class<?> type = node.getClass();
            Method hasPermission = findMethod(type, "hasPermission", CommandSender.class);
            Method canUse = findMethod(type, "canUse", CommandSender.class);
            Method getPermission = findMethod(type, "getPermission");
            Method getChild = findMethod(type, "getChild", String.class);
            Method getChildren = findMethod(type, "getChildren");
            if (hasPermission == null || canUse == null || getPermission == null
                    || getChild == null || getChildren == null) {
                return null;
            }

            return new NodeMethods(
                    hasPermission,
                    canUse,
                    getPermission,
                    getChild,
                    getChildren,
                    findMethod(type, "getRequirements"),
                    findMethod(type, "getExecutor")
            );
        }

        private boolean hasPermission(Object node, CommandSender sender) throws ReflectiveOperationException {
            return Boolean.TRUE.equals(invoke(hasPermission, node, sender));
        }

        private boolean canUse(Object node, CommandSender sender) throws ReflectiveOperationException {
            return Boolean.TRUE.equals(invoke(canUse, node, sender));
        }

        private @Nullable String permission(Object node) throws ReflectiveOperationException {
            Object value = invoke(getPermission, node);
            return value == null ? null : value.toString();
        }

        private @Nullable Object child(Object node, String name) throws ReflectiveOperationException {
            return invoke(getChild, node, name);
        }

        private Collection<?> children(Object node) throws ReflectiveOperationException {
            Object value = invoke(getChildren, node);
            return value instanceof Collection<?> collection ? collection : List.of();
        }

        private boolean hasRequirements(Object node) throws ReflectiveOperationException {
            if (getRequirements == null) {
                return false;
            }
            Object value = invoke(getRequirements, node);
            return value instanceof Collection<?> collection && !collection.isEmpty();
        }

        private @Nullable Object executor(Object node) throws ReflectiveOperationException {
            return getExecutor == null ? null : invoke(getExecutor, node);
        }
    }
}
