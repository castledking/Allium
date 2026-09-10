package codes.castled.allium.managers.core;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.query.QueryOptionsRegistry;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeLimitsOfflinePermissionTest {

    private static final UUID PLAYER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void offlinePlayerWithHomeNodesGetsHighestNumber() throws Exception {
        withLuckPerms(nodes("allium.sethome", "allium.sethome.3"), () -> {
            HomeLimits.OfflinePermissionResult result = HomeLimits.getOfflinePermissionMaxHomes(offlinePlayer());

            assertTrue(result.resolved());
            assertEquals(3, result.maxHomes());
        });
    }

    @Test
    void offlinePlayerWithOnlyBaseGrantGetsDefaultOne() throws Exception {
        withLuckPerms(nodes("allium.sethome"), () -> {
            HomeLimits.OfflinePermissionResult result = HomeLimits.getOfflinePermissionMaxHomes(offlinePlayer());

            assertTrue(result.resolved());
            assertEquals(1, result.maxHomes());
        });
    }

    @Test
    void offlinePlayerWithUnlimitedNodeReportsUnlimited() throws Exception {
        withLuckPerms(nodes("allium.sethome.unlimited"), () -> {
            HomeLimits.OfflinePermissionResult result = HomeLimits.getOfflinePermissionMaxHomes(offlinePlayer());

            assertTrue(result.resolved());
            assertEquals(HomeLimits.UNLIMITED, result.maxHomes());
        });
    }

    @Test
    void offlinePlayerWithNegatedBaseHasNoPermissionHomes() throws Exception {
        withLuckPerms(nodes("plain.other.node", "!allium.sethome"), () -> {
            HomeLimits.OfflinePermissionResult result = HomeLimits.getOfflinePermissionMaxHomes(offlinePlayer());

            assertTrue(result.resolved());
            assertEquals(0, result.maxHomes());
        });
    }

    @Test
    void withoutLuckPermsResolutionIsUnresolved() {
        HomeLimits.OfflinePermissionResult result = HomeLimits.getOfflinePermissionMaxHomes(offlinePlayer());

        assertFalse(result.resolved());
        assertEquals(0, result.maxHomes());
    }

    private void withLuckPerms(Set<Node> nodes, Runnable body) throws Exception {
        Object queryOptions = proxy(QueryOptions.class, (method, args) -> defaultValue(method.getReturnType()));
        Object queryOptionsRegistry = proxy(QueryOptionsRegistry.class, (method, args) ->
                method.getName().equals("defaultNonContextualOptions")
                        ? queryOptions
                        : defaultValue(method.getReturnType()));
        LuckPerms luckPerms = proxy(LuckPerms.class, (method, args) -> switch (method.getName()) {
            case "getUserManager" -> userManager(nodes);
            case "getQueryOptionsRegistry" -> queryOptionsRegistry;
            default -> defaultValue(method.getReturnType());
        });

        Field providerInstance = LuckPermsProvider.class.getDeclaredField("instance");
        providerInstance.setAccessible(true);
        Object previous = providerInstance.get(null);
        try {
            providerInstance.set(null, luckPerms);
            body.run();
        } finally {
            providerInstance.set(null, previous);
        }
    }

    private Object userManager(Set<Node> nodes) {
        User user = proxy(User.class, (method, args) ->
                method.getName().equals("resolveInheritedNodes")
                        ? nodes
                        : defaultValue(method.getReturnType()));
        return proxy(UserManager.class, (method, args) ->
                method.getName().equals("getUser") && PLAYER_UUID.equals(args[0])
                        ? user
                        : defaultValue(method.getReturnType()));
    }

    private Set<Node> nodes(String... keys) {
        Set<Node> result = new HashSet<>();
        for (String key : keys) {
            result.add(node(key));
        }
        return result;
    }

    private Node node(String key) {
        return proxy(Node.class, (method, args) ->
                method.getName().equals("getKey") ? key : defaultValue(method.getReturnType()));
    }

    private OfflinePlayer offlinePlayer() {
        return (OfflinePlayer) Proxy.newProxyInstance(
                OfflinePlayer.class.getClassLoader(),
                new Class<?>[]{OfflinePlayer.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) {
                        return PLAYER_UUID;
                    }
                    if (method.getName().equals("getPlayer")) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, Answer answer) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> answer.answer(method, args)
        );
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }

    @FunctionalInterface
    private interface Answer {
        Object answer(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }
}