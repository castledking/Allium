package codes.castled.allium.tradingcards.xp;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

public final class BridgeTestContext {
    public final Map<String, Plugin> plugins = new HashMap<>();
    public final Map<Class<?>, EventExecutor> executors = new HashMap<>();
    public final PluginManager manager = proxy(PluginManager.class, (p, m, a) -> switch (m.getName()) {
        case "getPlugin" -> plugins.get(a[0]);
        case "isPluginEnabled" -> plugins.containsKey(a[0]);
        case "registerEvent" -> { executors.put((Class<?>) a[0], (EventExecutor) a[3]); yield null; }
        default -> throw new UnsupportedOperationException(m.getName());
    });
    public final Server server = proxy(Server.class, (p, m, a) -> switch (m.getName()) {
        case "getPluginManager" -> manager;
        default -> throw new UnsupportedOperationException(m.getName());
    });
    public final Plugin owner = plugin("Allium");

    public Plugin plugin(String name) {
        return proxy(Plugin.class, (p, m, a) -> switch (m.getName()) {
            case "isEnabled" -> true;
            case "getName" -> name;
            case "getServer" -> server;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    public void fire(Class<?> registeredClass, Event event) throws Exception {
        executors.get(registeredClass).execute(null, event);
    }

    public static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
