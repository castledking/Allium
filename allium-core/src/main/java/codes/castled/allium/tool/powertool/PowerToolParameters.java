package codes.castled.allium.tool.powertool;

import codes.castled.allium.tool.definition.ToolParameters;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Validated parameters for an {@code allium:powertool} definition (both saved in
 * {@code tools/<name>.yml} and inline on signed held items).
 */
public record PowerToolParameters(
        PowerToolUses uses,
        long clickGuardMillis,
        Map<PowerToolTrigger, PowerToolBinding> bindings
) implements ToolParameters {

    public static final long DEFAULT_CLICK_GUARD_MILLIS = 100L;

    public static final PowerToolParameters EMPTY = new PowerToolParameters(
            PowerToolUses.UNLIMITED,
            DEFAULT_CLICK_GUARD_MILLIS,
            Map.of()
    );

    public PowerToolParameters {
        uses = uses == null ? PowerToolUses.UNLIMITED : uses;
        clickGuardMillis = Math.max(0L, clickGuardMillis);
        if (bindings == null || bindings.isEmpty()) {
            bindings = Map.of();
        } else {
            Map<PowerToolTrigger, PowerToolBinding> copy = new LinkedHashMap<>();
            bindings.forEach((k, v) -> {
                if (k != null && v != null && !v.actions().isEmpty()) {
                    copy.put(k, v);
                }
            });
            bindings = Map.copyOf(copy);
        }
    }

    @Nullable
    public PowerToolBinding binding(PowerToolTrigger trigger) {
        return bindings.get(trigger);
    }

    public boolean hasBindings() {
        return !bindings.isEmpty();
    }

    public boolean hasConsoleActions() {
        for (PowerToolBinding binding : bindings.values()) {
            for (PowerToolAction action : binding.actions()) {
                if (action.isConsoleExecution()) {
                    return true;
                }
            }
        }
        return false;
    }

    public PowerToolParameters withBinding(PowerToolTrigger trigger, @Nullable PowerToolBinding binding) {
        Map<PowerToolTrigger, PowerToolBinding> next = new EnumMap<>(PowerToolTrigger.class);
        next.putAll(bindings);
        if (binding == null || binding.actions().isEmpty()) {
            next.remove(trigger);
        } else {
            next.put(trigger, binding);
        }
        return new PowerToolParameters(uses, clickGuardMillis, next);
    }

    public PowerToolParameters withUses(PowerToolUses nextUses) {
        return new PowerToolParameters(Objects.requireNonNull(nextUses, "uses"), clickGuardMillis, bindings);
    }

    public PowerToolParameters withClickGuardMillis(long millis) {
        return new PowerToolParameters(uses, millis, bindings);
    }
}
