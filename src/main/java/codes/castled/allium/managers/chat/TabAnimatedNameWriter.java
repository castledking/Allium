package codes.castled.allium.managers.chat;

import java.lang.reflect.Method;
import java.util.UUID;

/** Uses TAB's public API without introducing a hard plugin dependency. */
final class TabAnimatedNameWriter implements AnimatedTabNameWriteCoordinator.TabNameWriter {

    private static final long DISCOVERY_RETRY_NANOS = 5_000_000_000L;

    private volatile ApiAccess apiAccess;
    private volatile long nextDiscoveryNanos;

    @Override
    public boolean setName(UUID playerId, String name) {
        ApiAccess access = getApiAccess();
        if (access == null) {
            return false;
        }

        try {
            return access.setName(playerId, name);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            apiAccess = null;
            nextDiscoveryNanos = System.nanoTime() + DISCOVERY_RETRY_NANOS;
            return false;
        }
    }

    private ApiAccess getApiAccess() {
        ApiAccess current = apiAccess;
        if (current != null) {
            return current;
        }

        long now = System.nanoTime();
        if (now < nextDiscoveryNanos) {
            return null;
        }

        synchronized (this) {
            if (apiAccess != null) {
                return apiAccess;
            }
            if (System.nanoTime() < nextDiscoveryNanos) {
                return null;
            }

            try {
                Class<?> tabApiClass = Class.forName("me.neznamy.tab.api.TabAPI");
                Class<?> tabPlayerClass = Class.forName("me.neznamy.tab.api.TabPlayer");
                Class<?> formatManagerClass = Class.forName("me.neznamy.tab.api.tablist.TabListFormatManager");
                apiAccess = new ApiAccess(
                        tabApiClass.getMethod("getInstance"),
                        tabApiClass.getMethod("getPlayer", UUID.class),
                        tabApiClass.getMethod("getTabListFormatManager"),
                        formatManagerClass.getMethod("setName", tabPlayerClass, String.class)
                );
                return apiAccess;
            } catch (ReflectiveOperationException | LinkageError ignored) {
                nextDiscoveryNanos = System.nanoTime() + DISCOVERY_RETRY_NANOS;
                return null;
            }
        }
    }

    private record ApiAccess(
            Method getInstance,
            Method getPlayer,
            Method getFormatManager,
            Method setName
    ) {
        boolean setName(UUID playerId, String name) throws ReflectiveOperationException {
            Object api = getInstance.invoke(null);
            if (api == null) {
                return false;
            }

            Object tabPlayer = getPlayer.invoke(api, playerId);
            Object formatManager = getFormatManager.invoke(api);
            if (tabPlayer == null || formatManager == null) {
                return false;
            }

            setName.invoke(formatManager, tabPlayer, name);
            return true;
        }
    }
}
