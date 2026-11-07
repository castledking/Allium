package codes.castled.allium.protection;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Asks protection plugins whether a player may act on an entity.
 *
 * <p>Dedicated hooks run first, in registration order; the first denial wins. When every hook
 * allows, the generic fallback fires {@link AlliumProtectionCheckEvent}, so any plugin can veto.
 * A hook that throws denies, with one diagnostic per hook, rather than silently allowing.
 */
public final class ProtectionService {

    public static final String EVENT_SOURCE = "event";

    private final List<ProtectionHook> hooks;
    @Nullable
    private final PluginManager pluginManager;
    private final Consumer<String> warningLogger;
    private final Set<String> warnedHooks = ConcurrentHashMap.newKeySet();

    public ProtectionService(List<ProtectionHook> hooks, @Nullable PluginManager pluginManager, Consumer<String> warningLogger) {
        this.hooks = List.copyOf(hooks);
        this.pluginManager = pluginManager;
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
    }

    public List<String> hookNames() {
        return hooks.stream().map(ProtectionHook::name).toList();
    }

    public ProtectionDecision checkEntityInteraction(Player player, Entity target, String action, @Nullable Event cause) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(action, "action");
        for (ProtectionHook hook : hooks) {
            ProtectionDecision decision;
            try {
                decision = hook.checkEntityInteraction(player, target, cause);
            } catch (RuntimeException | LinkageError e) {
                if (warnedHooks.add(hook.name())) {
                    warningLogger.accept("Protection hook " + hook.name() + " failed; denying '" + action
                            + "' until it works: " + e);
                }
                return ProtectionDecision.deny(hook.name(), null);
            }
            if (decision != null && !decision.allowed()) {
                return decision;
            }
        }
        if (pluginManager != null) {
            AlliumProtectionCheckEvent event = new AlliumProtectionCheckEvent(player, target, action, cause);
            pluginManager.callEvent(event);
            if (event.isCancelled()) {
                return ProtectionDecision.deny(EVENT_SOURCE, null);
            }
        }
        return ProtectionDecision.allow();
    }
}
