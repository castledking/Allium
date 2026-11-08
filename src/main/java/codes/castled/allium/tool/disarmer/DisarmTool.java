package codes.castled.allium.tool.disarmer;

import codes.castled.allium.message.LangMessage;
import codes.castled.allium.message.Messages;
import codes.castled.allium.tool.definition.ToolDefinition;
import codes.castled.allium.tool.definition.ToolSound;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * One loaded {@code allium:disarm} definition at runtime: its validated parameters, resolved
 * messages and per-player state (click guard and status cooldown). Each definition keeps its
 * own, so two disarmers never share a guard window or a status timer.
 */
public final class DisarmTool {

    private final ToolDefinition definition;
    private final DisarmParameters parameters;
    private final Map<String, LangMessage> messages;
    private final ClickGuard clickGuard;
    private final Map<UUID, Long> lastStatus = new ConcurrentHashMap<>();

    public DisarmTool(ToolDefinition definition, LongSupplier nanoClock) {
        this.definition = Objects.requireNonNull(definition, "definition");
        if (!DisarmBehaviorType.ID.equals(definition.behavior())) {
            throw new IllegalArgumentException(definition.id() + " binds " + definition.behavior() + ", not " + DisarmBehaviorType.ID);
        }
        this.parameters = definition.parameters(DisarmParameters.class);
        Map<String, LangMessage> resolved = new LinkedHashMap<>();
        new DisarmBehaviorType().messageSlots().forEach((slot, defaultKey) -> {
            Messages fallback = Messages.fromKey(defaultKey);
            if (fallback == null) {
                throw new IllegalStateException("message slot " + slot + " has no built-in entry " + defaultKey);
            }
            resolved.put(slot, new LangMessage(definition.message(slot), fallback));
        });
        this.messages = Map.copyOf(resolved);
        this.clickGuard = new ClickGuard(parameters.clickGuardMillis(), nanoClock);
    }

    public String id() {
        return definition.id();
    }

    public String name() {
        return definition.name();
    }

    public ToolDefinition definition() {
        return definition;
    }

    public DisarmParameters parameters() {
        return parameters;
    }

    public String usePermission() {
        return definition.permission(DisarmBehaviorType.PERMISSION_USE);
    }

    public String bypassPermission() {
        return definition.permission(DisarmBehaviorType.PERMISSION_BYPASS);
    }

    public LangMessage message(String slot) {
        LangMessage message = messages.get(slot);
        if (message == null) {
            throw new IllegalArgumentException("unknown message slot " + slot);
        }
        return message;
    }

    public ToolSound disarmSound() {
        return definition.sound(DisarmBehaviorType.SOUND_DISARM);
    }

    ClickGuard clickGuard() {
        return clickGuard;
    }

    /** Records a status message unless one was sent within the cooldown; returns whether to send. */
    boolean admitStatus(UUID playerId, long now) {
        Long last = lastStatus.get(playerId);
        if (last != null && now - last < parameters.statusCooldownMillis()) {
            return false;
        }
        lastStatus.put(playerId, now);
        return true;
    }

    void forget(UUID playerId) {
        clickGuard.forget(playerId);
        lastStatus.remove(playerId);
    }
}
