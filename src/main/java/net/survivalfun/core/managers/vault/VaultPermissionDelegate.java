package net.survivalfun.core.managers.vault;

import net.milkbowl.vault.permission.Permission;

import java.lang.reflect.Method;

/**
 * Delegates to a Vault Permission provider via reflection.
 * Used when the provider (e.g. LuckPerms) uses a different classloader - avoids
 * "Permission is not an interface" from Proxy since Permission is an abstract class.
 */
public class VaultPermissionDelegate extends Permission {

    private final Object provider;

    public VaultPermissionDelegate(Object provider) {
        this.provider = provider;
    }

    private Object invoke(String methodName, Class<?>[] paramTypes, Object... args) {
        try {
            Method m = provider.getClass().getMethod(methodName, paramTypes);
            return m.invoke(provider, args);
        } catch (NoSuchMethodException e) {
            for (Method m : provider.getClass().getMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                    try {
                        return m.invoke(provider, args);
                    } catch (Exception ignored) {}
                }
            }
            throw new RuntimeException("Vault Permission delegate failed: " + methodName, e);
        } catch (Exception e) {
            throw new RuntimeException("Vault Permission delegate failed: " + methodName, e);
        }
    }

    @Override
    public String getName() {
        return (String) invoke("getName", new Class<?>[]{});
    }

    @Override
    public boolean isEnabled() {
        return (Boolean) invoke("isEnabled", new Class<?>[]{});
    }

    @Override
    public boolean hasSuperPermsCompat() {
        return (Boolean) invoke("hasSuperPermsCompat", new Class<?>[]{});
    }

    @Override
    public boolean playerHas(String world, String player, String permission) {
        return (Boolean) invoke("playerHas", new Class<?>[]{String.class, String.class, String.class}, world, player, permission);
    }

    @Override
    public boolean playerAdd(String world, String player, String permission) {
        return (Boolean) invoke("playerAdd", new Class<?>[]{String.class, String.class, String.class}, world, player, permission);
    }

    @Override
    public boolean playerRemove(String world, String player, String permission) {
        return (Boolean) invoke("playerRemove", new Class<?>[]{String.class, String.class, String.class}, world, player, permission);
    }

    @Override
    public boolean groupHas(String world, String group, String permission) {
        return (Boolean) invoke("groupHas", new Class<?>[]{String.class, String.class, String.class}, world, group, permission);
    }

    @Override
    public boolean groupAdd(String world, String group, String permission) {
        return (Boolean) invoke("groupAdd", new Class<?>[]{String.class, String.class, String.class}, world, group, permission);
    }

    @Override
    public boolean groupRemove(String world, String group, String permission) {
        return (Boolean) invoke("groupRemove", new Class<?>[]{String.class, String.class, String.class}, world, group, permission);
    }

    @Override
    public boolean playerInGroup(String world, String player, String group) {
        return (Boolean) invoke("playerInGroup", new Class<?>[]{String.class, String.class, String.class}, world, player, group);
    }

    @Override
    public boolean playerAddGroup(String world, String player, String group) {
        return (Boolean) invoke("playerAddGroup", new Class<?>[]{String.class, String.class, String.class}, world, player, group);
    }

    @Override
    public boolean playerRemoveGroup(String world, String player, String group) {
        return (Boolean) invoke("playerRemoveGroup", new Class<?>[]{String.class, String.class, String.class}, world, player, group);
    }

    @Override
    public String[] getPlayerGroups(String world, String player) {
        return (String[]) invoke("getPlayerGroups", new Class<?>[]{String.class, String.class}, world, player);
    }

    @Override
    public String getPrimaryGroup(String world, String player) {
        return (String) invoke("getPrimaryGroup", new Class<?>[]{String.class, String.class}, world, player);
    }

    @Override
    public String[] getGroups() {
        return (String[]) invoke("getGroups", new Class<?>[]{});
    }

    @Override
    public boolean hasGroupSupport() {
        return (Boolean) invoke("hasGroupSupport", new Class<?>[]{});
    }
}
